import { test, expect } from "@playwright/test";
import { PrismaClient } from "@prisma/client";
import { initialAvailability } from "../lib/technicianAvailability";
import { DEFAULT_CLIENT_ID } from "../lib/clients";

const prisma = new PrismaClient();
test.afterAll(async () => { await prisma.$disconnect(); });

test("dispatch and weekly schedule use the depot metro effective on each date", async ({ page }) => {
  const firstMetro = await prisma.metro.create({ data: { name: "Lincoln browser fixture", timezone: "America/Chicago" } });
  const secondMetro = await prisma.metro.create({ data: { name: "Omaha browser fixture", timezone: "America/Chicago" } });
  const dealer = await prisma.dealership.create({ data: { clientId: DEFAULT_CLIENT_ID, name: "Two metro browser fixture" } });
  const firstDepot = await prisma.depot.create({ data: { dealershipId: dealer.id, metroId: firstMetro.id, name: "Lincoln browser depot", lat: 40.8, lng: -96.7,
    endpointPolicies: { create: { effectiveDate: new Date("1900-01-01T00:00:00Z"), departure: "HOME", returnTo: "HOME" } } } });
  const secondDepot = await prisma.depot.create({ data: { dealershipId: dealer.id, metroId: secondMetro.id, name: "Omaha browser depot", lat: 41.25, lng: -95.93,
    endpointPolicies: { create: { effectiveDate: new Date("1900-01-01T00:00:00Z"), departure: "HOME", returnTo: "HOME" } } } });
  const technician = await prisma.technician.create({ data: { clientId: DEFAULT_CLIENT_ID, name: "Moving browser technician", color: "#2563eb", homeLat: 40.8, homeLng: -96.7,
    shiftStartMin: 480, shiftEndMin: 1020, availabilityVersions: initialAvailability(480, 1020),
    depotAssignments: { create: [{ depotId: firstDepot.id, effectiveDate: new Date("1900-01-01T00:00:00Z") },
      { depotId: secondDepot.id, effectiveDate: new Date("2026-10-08T00:00:00Z") }] } } });
  try {
    await page.route("**/api/dispatch/geometry?**", route => {
      const date = new URL(route.request().url()).searchParams.get("date");
      void route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ type: "FeatureCollection", routingIdentity: "fixture",
        serviceDate: date, phase: "current", features: [], stops: [], endpoints: [] }) });
    });
    await page.goto(`/dispatch?metroId=${firstMetro.id}&date=2026-10-07`);
    await expect(page.getByText("Moving browser technician")).toBeVisible();
    await page.goto(`/dispatch?metroId=${firstMetro.id}&date=2026-10-08`);
    await expect(page.getByText("Moving browser technician")).toHaveCount(0);
    await page.goto(`/dispatch?metroId=${secondMetro.id}&date=2026-10-08`);
    await expect(page.getByText("Moving browser technician")).toBeVisible();
    await page.goto(`/schedule?metroId=${firstMetro.id}&week=2026-10-05`);
    await expect(page.getByText("Assigned to another metro").first()).toBeVisible();
    await page.goto(`/schedule?metroId=${secondMetro.id}&week=2026-10-05`);
    await expect(page.getByText("Assigned to another metro").first()).toBeVisible();
  } finally {
    await prisma.technician.delete({ where: { id: technician.id } });
    await prisma.depot.deleteMany({ where: { id: { in: [firstDepot.id, secondDepot.id] } } });
    await prisma.dealership.delete({ where: { id: dealer.id } });
    await prisma.metro.deleteMany({ where: { id: { in: [firstMetro.id, secondMetro.id] } } });
  }
});
