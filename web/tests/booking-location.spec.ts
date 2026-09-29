import { test, expect } from "@playwright/test";
import { PrismaClient } from "@prisma/client";
import { locationFixture, mockBookingLocation } from "./bookingLocationFixture";
import { bookingRequest } from "../lib/contracts";

const prisma = new PrismaClient();
test.afterAll(() => prisma.$disconnect());
for (const hasMatch of [true, false]) {
  test(`manual placement with ${hasMatch ? "exact match correction" : "no candidate"}, edits and tile outage`, async ({ page }) => {
    const service = await prisma.serviceCatalog.create({ data: { code: `PIN_${hasMatch}`, name: "Pin service", estDurationMin: 30 } });
    try {
      await mockBookingLocation(page);
      await page.route("**/api/book/location", route => route.fulfill({ json: { ...locationFixture,
        status: hasMatch ? "MATCHED" : "NO_MATCH", candidates: hasMatch ? locationFixture.candidates : [] } }));
      let bookings = 0;
      await page.route("**/api/book", async route => {
        const body = bookingRequest.parse(route.request().postDataJSON()); bookings++;
        expect(body.confirmedPin?.manuallyConfirmed).toBe(true);
        expect(body.confirmedPin?.lat).not.toBe(locationFixture.candidates[0]?.lat);
        await route.fulfill({ status: 422, json: { status: "UNROUTABLE", error: "Place the pin at your driveway entrance." } });
      });
      await page.goto("/book");
      await page.getByRole("radio", { name: /Pin service/ }).check();
      for (const [label, value] of Object.entries({ "First name": "Pin", "Last name": "Test", Email: "pin@example.invalid", Phone: "4025550100", City: "Omaha", State: "NE", ZIP: "68130" }))
        await page.locator("label").filter({ hasText: new RegExp(`^${label}$`) }).locator("..").locator("input").fill(value);
      await page.getByPlaceholder("Street address").fill("123 Cascio Drive");
      await page.getByRole("button", { name: "Review service location" }).click();
      const confirm = page.getByRole("button", { name: "Confirm pin and see times" });
      const map = page.getByLabel("Service location map");
      await expect(map.locator("canvas")).toBeVisible();
      if (!hasMatch) await expect(confirm).toBeDisabled();
      else await expect(confirm).toBeEnabled();
      await map.click({ position: { x: 150, y: 200 } });
      await expect(confirm).toBeEnabled();
      expect(bookings).toBe(0);
      await confirm.click();
      await expect(page.getByRole("main").getByRole("alert")).toHaveText("Place the pin at your driveway entrance.");
      expect(bookings).toBe(1);
      await page.getByRole("button", { name: "Edit address" }).click();
      await page.getByPlaceholder("Street address").fill("125 Cascio Drive");
      await page.route("http://localhost:8083/omaha.json", route => route.fulfill({ status: 503 }));
      await page.getByRole("button", { name: "Review service location" }).click();
      await expect(confirm).toBeDisabled();
      await expect(page.getByText(/Pin confirmation is blocked/)).toBeVisible();
      await page.route("**/api/book", async route => {
        const body = bookingRequest.parse(route.request().postDataJSON());
        expect(body.followUp).toBe(true); expect(body.confirmedPin).toBeUndefined();
        await route.fulfill({ json: { jobId: "follow-up", pendingReference: "follow-up", status: "FOLLOW_UP" } });
      });
      await page.getByRole("button", { name: "Request follow-up" }).click();
      await expect(page.getByRole("heading", { name: "We will follow up" })).toBeVisible();
    } finally { await prisma.serviceCatalog.delete({ where: { id: service.id } }); }
  });
}

test("address edits abort lookup and prevent stale results from replacing a newer address", async ({ page }) => {
  const service = await prisma.serviceCatalog.create({ data: { code: "PIN_STALE", name: "Stale pin service", estDurationMin: 30 } });
  let release: (() => void) | undefined;
  try {
    await mockBookingLocation(page);
    await page.route("**/api/book/location", async route => {
      const body = route.request().postDataJSON() as unknown;
      const address = (await import("../lib/contracts")).bookingAddress.parse(body);
      if (address.line1 === "Old address") await new Promise<void>(resolve => { release = resolve; });
      try { await route.fulfill({ json: locationFixture }); } catch { /* An edited address cancels this response. */ }
    });
    await page.goto("/book");
    await page.getByRole("radio", { name: /Stale pin service/ }).check();
    for (const [label, value] of Object.entries({ "First name": "Pin", "Last name": "Test", Email: "pin@example.invalid", Phone: "4025550100", City: "Omaha", State: "NE", ZIP: "68130" }))
      await page.locator("label").filter({ hasText: new RegExp(`^${label}$`) }).locator("..").locator("input").fill(value);
    await page.getByPlaceholder("Street address").fill("Old address");
    await page.getByRole("button", { name: "Review service location" }).click();
    await expect.poll(() => release !== undefined).toBe(true);
    await page.getByPlaceholder("Street address").fill("New address");
    await page.getByRole("button", { name: "Review service location" }).click();
    await expect(page.getByText("New address, Omaha, NE 68130")).toBeVisible();
    release?.();
    await expect(page.getByText("New address, Omaha, NE 68130")).toBeVisible();
    await expect(page.getByText("Old address, Omaha, NE 68130")).toHaveCount(0);
  } finally { release?.(); await prisma.serviceCatalog.delete({ where: { id: service.id } }); }
});
