import { mockBookingLocation } from "./bookingLocationFixture";
import { test, expect } from "@playwright/test";
import { PrismaClient } from "@prisma/client";
import { z } from "zod";

const prisma = new PrismaClient();
test.afterAll(async () => { await prisma.$disconnect(); });

test("Start over retries release, then returns to the populated form with a new request ID", async ({ page }) => {
  const service = await prisma.serviceCatalog.create({ data: { code: "UI_START_OVER", name: "Start over service", estDurationMin: 60 } });
  const offerId = "offer-for-start-over";
  const requestIds: string[] = [];
  const releaseBodies: Array<{ jobId: string; offerId: string }> = [];
  let releaseCount = 0;
  let refreshCount = 0;
  try {
    await page.route("**/api/book", async route => {
      if (route.request().method() !== "POST") return route.continue();
      const body: unknown = route.request().postDataJSON();
      if (typeof body !== "object" || body === null || !("requestId" in body) || typeof body.requestId !== "string") throw new Error("Missing request ID");
      requestIds.push(body.requestId);
      return route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ jobId: "pending-job", search: { outcome: "AVAILABLE", prescribedSearchCompleted: false, elapsedMs: 1, retryable: false }, offers: [{ offerId, date: "2099-10-05", windowStart: "2099-10-05T14:00:00Z", windowEnd: "2099-10-05T16:00:00Z", expiresAt: "2099-10-05T13:00:00Z" }] }) });
    });
    await page.route("**/api/book/release", async route => {
      releaseBodies.push(z.object({ jobId: z.string(), offerId: z.string() }).parse(route.request().postDataJSON()));
      releaseCount++;
      return route.fulfill({ status: releaseCount === 1 ? 503 : 200, contentType: "application/json", body: JSON.stringify(releaseCount === 1 ? { error: "Scheduling service unavailable" } : { success: true }) });
    });
    await page.route("**/api/book/search**", async route => {
      if (route.request().method() === "POST") {
        z.object({ jobId: z.literal("pending-job"), requestId: z.uuid(), refresh: z.literal(true) }).parse(route.request().postDataJSON());
        refreshCount++;
      }
      return route.fulfill({ json: { id: "00000000-0000-4000-8000-000000000001", jobId: "pending-job", state: "AVAILABLE", phase: "FINISHED",
        elapsedMs: 12, queueMs: 0, completedWork: 20, stopReason: "COMPLETED",
        offers: [{ offerId, date: "2099-10-05", windowStart: "2099-10-05T14:00:00Z", windowEnd: "2099-10-05T18:00:00Z", expiresAt: "2099-10-05T13:00:00Z" }] } });
    });
    await mockBookingLocation(page);
    await page.goto("/book");
    await page.getByRole("radio", { name: /Start over service/ }).check();
    for (const [label, value] of Object.entries({ "First name": "Correct", "Last name": "Me", Email: "customer@example.invalid", Phone: "4025550100", City: "Omaha", State: "NE", ZIP: "68102" }))
      await page.locator("label").filter({ hasText: new RegExp(`^${label}$`) }).locator("..").locator("input").fill(value);
    await page.getByPlaceholder("Street address").fill("1 Main St");
    await page.getByRole("button", { name: /review service location/i }).click();
    await page.getByRole("button", { name: "Confirm pin and see times" }).click();
    await expect(page.getByRole("heading", { name: "Choose a time" })).toBeVisible();
    await page.getByRole("button", { name: "Refresh times", exact: true }).click();
    await expect(page.getByRole("progressbar", { name: "Finding available appointments" })).not.toBeVisible();
    expect(refreshCount).toBe(1);
    await page.getByRole("button", { name: /Start over/i }).click();
    await expect(page.getByText("Scheduling service unavailable")).toBeVisible();
    await expect(page.getByRole("heading", { name: "Choose a time" })).toBeVisible();
    expect(requestIds).toHaveLength(1);
    await page.getByRole("button", { name: /Start over/i }).click();
    await expect(page.getByRole("heading", { name: "Book a service visit" })).toBeVisible();
    await expect(page.getByPlaceholder("Street address")).toHaveValue("1 Main St");
    await expect(page.locator("label").filter({ hasText: /^First name$/ }).locator("..").locator("input")).toHaveValue("Correct");
    expect(releaseBodies).toEqual([{ jobId: "pending-job", offerId }, { jobId: "pending-job", offerId }]);
    await page.getByRole("button", { name: /review service location/i }).click();
    await page.getByRole("button", { name: "Confirm pin and see times" }).click();
    await expect(page.getByRole("heading", { name: "Choose a time" })).toBeVisible();
    expect(requestIds).toHaveLength(2);
    expect(requestIds[1]).not.toBe(requestIds[0]);
  } finally {
    await prisma.serviceCatalog.delete({ where: { id: service.id } });
  }
});

test("a direct search through the scheduling API refreshes directly, never through the background search", async ({ page }) => {
  const service = await prisma.serviceCatalog.create({ data: { code: "UI_DIRECT_REFRESH", name: "Direct refresh service", estDurationMin: 60 } });
  const offer = (offerId: string, hour: number) => ({ offerId, date: "2099-10-05", windowStart: `2099-10-05T${hour}:00:00Z`,
    windowEnd: `2099-10-05T${hour + 2}:00:00Z`, expiresAt: "2099-10-05T13:00:00Z" });
  const refreshBodies: unknown[] = [];
  let backgroundSearches = 0;
  try {
    await page.route("**/api/book", async route => {
      if (route.request().method() !== "POST") return route.continue();
      return route.fulfill({ json: { jobId: "direct-job", searchMode: "DIRECT", offers: [offer("first-offer", 14)],
        search: { outcome: "AVAILABLE", prescribedSearchCompleted: true, elapsedMs: 40, retryable: false } } });
    });
    await page.route("**/api/book/refresh", async route => {
      refreshBodies.push(route.request().postDataJSON());
      return route.fulfill({ json: { jobId: "direct-job", offers: [offer("refreshed-offer", 16)],
        search: { outcome: "AVAILABLE", prescribedSearchCompleted: true, elapsedMs: 35, retryable: false } } });
    });
    await page.route("**/api/book/search**", async route => { backgroundSearches++; return route.fulfill({ status: 404, json: { error: "Not used" } }); });
    await mockBookingLocation(page);
    await page.goto("/book");
    await page.getByRole("radio", { name: /Direct refresh service/ }).check();
    for (const [label, value] of Object.entries({ "First name": "Direct", "Last name": "Search", Email: "direct@example.invalid", Phone: "4025550100", City: "Omaha", State: "NE", ZIP: "68102" }))
      await page.locator("label").filter({ hasText: new RegExp(`^${label}$`) }).locator("..").locator("input").fill(value);
    await page.getByPlaceholder("Street address").fill("1 Main St");
    await page.getByRole("button", { name: /review service location/i }).click();
    await page.getByRole("button", { name: "Confirm pin and see times" }).click();
    await expect(page.getByRole("heading", { name: "Choose a time" })).toBeVisible();
    await page.getByRole("button", { name: "Refresh times", exact: true }).click();
    await expect(page.getByRole("progressbar", { name: "Finding available appointments" })).not.toBeVisible();
    expect(refreshBodies).toEqual([{ jobId: "direct-job" }]);
    expect(backgroundSearches).toBe(0);
  } finally {
    await prisma.serviceCatalog.delete({ where: { id: service.id } });
  }
});
