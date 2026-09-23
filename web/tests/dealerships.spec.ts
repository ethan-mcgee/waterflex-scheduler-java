import { test, expect } from "@playwright/test";
import { PrismaClient } from "@prisma/client";

const prisma = new PrismaClient();
test.afterAll(async () => { await prisma.$disconnect(); });

test("dealership policy controls save independent departure and return endpoints", async ({ page }) => {
  const metro = await prisma.metro.create({ data: { name: "Dealership UI metro", timezone: "America/Chicago" } });
  const dealership = await prisma.dealership.create({ data: { name: "Dealership UI fixture" } });
  const depot = await prisma.depot.create({ data: { metroId: metro.id, dealershipId: dealership.id, name: "Dealership UI depot", lat: 41.25, lng: -95.93,
    endpointPolicies: { create: { effectiveDate: new Date("1900-01-01T00:00:00Z"), departure: "HOME", returnTo: "HOME" } } } });
  try {
    let submitted: unknown = null;
    await page.route(`**/api/depots/${depot.id}/policy`, async route => {
      submitted = route.request().postDataJSON() as unknown;
      await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ success: true }) });
    });
    await page.goto("/dealerships");
    const editor = page.locator("div").filter({ has: page.getByRole("heading", { name: "Dealership UI depot" }) }).last();
    await editor.getByLabel("Departure").selectOption("DEPOT");
    await editor.getByLabel("Return").selectOption("HOME");
    await editor.getByRole("button", { name: "Save route policy" }).click();
    await expect(page.getByText("Saved Dealership UI depot. Booked routes were checked.")).toBeVisible();
    expect(submitted).toEqual({ departure: "DEPOT", returnTo: "HOME" });
  } finally {
    await prisma.depot.delete({ where: { id: depot.id } });
    await prisma.dealership.delete({ where: { id: dealership.id } });
    await prisma.metro.delete({ where: { id: metro.id } });
  }
});
