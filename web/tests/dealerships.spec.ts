import { test, expect } from "@playwright/test";
import { PrismaClient } from "@prisma/client";

const prisma = new PrismaClient();
test.afterAll(async () => { await prisma.$disconnect(); });

test("dealership policy controls save independent departure and return endpoints", async ({ page }) => {
  const metro = await prisma.metro.create({ data: { name: "Dealership UI metro", timezone: "America/Chicago" } });
  const depot = await prisma.depot.create({ data: { metroId: metro.id, name: "Dealership UI depot", lat: 41.25, lng: -95.93 } });
  const dealership = await prisma.dealership.create({ data: { metroId: metro.id, depotId: depot.id, name: "Dealership UI fixture",
    endpointPolicies: { create: { effectiveDate: new Date("1900-01-01T00:00:00Z"), departure: "HOME", returnTo: "HOME" } } } });
  try {
    await page.goto("/dealerships");
    const editor = page.locator("div").filter({ has: page.getByRole("heading", { name: "Dealership UI fixture" }) }).last();
    await editor.getByLabel("Departure").selectOption("DEPOT");
    await editor.getByLabel("Return").selectOption("HOME");
    await editor.getByRole("button", { name: "Save route policy" }).click();
    await expect(page.getByText("Saved Dealership UI fixture. Booked routes were checked.")).toBeVisible();
    const updated = await prisma.dealership.findUniqueOrThrow({ where: { id: dealership.id } });
    expect(updated.departure).toBe("DEPOT");
    expect(updated.returnTo).toBe("HOME");
  } finally {
    await prisma.dealership.delete({ where: { id: dealership.id } });
    await prisma.depot.delete({ where: { id: depot.id } });
    await prisma.metro.delete({ where: { id: metro.id } });
  }
});
