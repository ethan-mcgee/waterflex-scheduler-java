import { test, expect } from "@playwright/test";
import { PrismaClient } from "@prisma/client";
import { z } from "zod";
import { required } from "../lib/contracts";

const prisma = new PrismaClient();
test.afterAll(async () => { await prisma.$disconnect(); });

for (const limit of [1, 2, 4]) {
  test(`renders and selects from ${limit} appointment offers`, async ({ page }) => {
    const service = await prisma.serviceCatalog.create({ data: { code: `UI_OFFER_LIMIT_${limit}`, name: "Offer limit service", estDurationMin: 60 } });
    const offers = Array.from({ length: limit }, (_, index) => ({
      offerId: `offer-${index}`, date: "2099-10-05", windowStart: `2099-10-05T${14 + index}:00:00Z`,
      windowEnd: `2099-10-05T${16 + index}:00:00Z`, expiresAt: "2099-10-05T13:00:00Z",
    }));
    const chosen = required(offers[limit - 1]);
    try {
      await page.route("**/api/book", route => route.fulfill({ status: 200, contentType: "application/json",
        body: JSON.stringify({ jobId: "limit-job", offers, search: { outcome: "AVAILABLE", prescribedSearchCompleted: true, elapsedMs: 1, retryable: false } }) }));
      await page.route("**/api/book/select", async route => {
        const body = z.object({ jobId: z.string(), offerId: z.string() }).parse(route.request().postDataJSON());
        expect(body).toEqual({ jobId: "limit-job", offerId: chosen.offerId });
        await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({
          holdId: "selected-hold", appointmentId: "limit-appointment", expiresAt: chosen.expiresAt,
          windowStart: chosen.windowStart, windowEnd: chosen.windowEnd,
        }) });
      });
      await page.goto("/book");
      await page.getByRole("radio", { name: /Offer limit service/ }).check();
      for (const [label, value] of Object.entries({ "First name": "Offer", "Last name": "Limit", Email: "customer@example.invalid", Phone: "4025550100", City: "Omaha", State: "NE", ZIP: "68102" }))
        await page.locator("label").filter({ hasText: new RegExp(`^${label}$`) }).locator("..").locator("input").fill(value);
      await page.getByPlaceholder("Street address").fill("1 Main St");
      await page.getByRole("button", { name: /see available times/i }).click();
      await expect(page.getByRole("heading", { name: "Choose a time" })).toBeVisible();
      const choices = page.getByRole("button", { name: "Select", exact: true });
      await expect(choices).toHaveCount(limit);
      await choices.nth(limit - 1).click();
      await expect(page.getByRole("heading", { name: "You're booked!" })).toBeVisible();
      await expect(page.getByText("Confirmation #limit-appointment")).toBeVisible();
    } finally {
      await prisma.serviceCatalog.delete({ where: { id: service.id } });
    }
  });
}
