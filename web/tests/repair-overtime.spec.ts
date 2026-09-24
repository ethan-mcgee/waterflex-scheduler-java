import { test, expect } from "@playwright/test";
import { PrismaClient } from "@prisma/client";
import { initialAvailability } from "../lib/technicianAvailability";

const prisma = new PrismaClient();
test.afterAll(async () => { await prisma.$disconnect(); });

test("repair overtime approval is explicit and identifies the reviewed preview", async ({ page }) => {
  const metro = await prisma.metro.create({ data: { name: "Overtime UI metro", timezone: "America/Chicago" } });
  const dealer = await prisma.dealership.create({ data: { name: "Overtime UI dealer" } });
  const depot = await prisma.depot.create({ data: { metroId: metro.id, dealershipId: dealer.id, name: "Overtime UI depot", lat: 41.2, lng: -95.9,
    endpointPolicies: { create: { effectiveDate: new Date("1900-01-01T00:00:00Z"), departure: "HOME", returnTo: "HOME" } } } });
  const technician = await prisma.technician.create({ data: { name: "Overtime Reviewer", color: "#2563eb", homeLat: 41.2, homeLng: -95.9,
    shiftStartMin: 480, shiftEndMin: 1020,
    availabilityVersions: initialAvailability(480, 1020), depotAssignments: { create: { depotId: depot.id, effectiveDate: new Date("1900-01-01T00:00:00Z") } } } });
  const metric = (overtime: number) => ({ route_minutes: 150, overtime_minutes: overtime, drive_minutes: 30,
    waiting_minutes: 0, distance_meters: 6000, modeled_cost_cents: 9000 });
  const request = await prisma.timeOffRequest.create({ data: { technicianId: technician.id, category: "Other", reason: "Review additional overtime", status: "READY",
    intervals: { create: { serviceDate: new Date("2099-10-05T00:00:00Z"), startMin: 480, endMin: 1020 } },
    report: { create: { status: "READY", progress: 100, data: { technician_id: technician.id, days: [
      { service_date: "2099-10-05", start_min: 480, end_min: 1020, status: "REPAIR_PREVIEW", run_id: "reviewed-run",
        daily_before: metric(0), daily_after: metric(60) },
    ] } } } } });
  try {
    await page.route("**/api/time-off/approve", async route => {
      expect(route.request().postDataJSON()).toEqual({ id: request.id, allowAdditionalOvertime: true, approvedRepairIds: ["reviewed-run"] });
      await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ requestId: request.id, status: "APPROVED" }) });
    });
    await page.goto("/time-off?status=ready");
    const approve = page.getByRole("button", { name: "Approve", exact: true });
    await expect(approve).toBeDisabled();
    await page.getByRole("checkbox", { name: "Approve 60 additional overtime minutes to preserve appointments" }).check();
    await expect(approve).toBeEnabled();
    await approve.click();
    await expect(page.getByText(`Request ${request.id} approved.`, { exact: true })).toBeVisible();
  } finally {
    await prisma.timeOffRequest.delete({ where: { id: request.id } });
    await prisma.technician.delete({ where: { id: technician.id } });
    await prisma.depot.delete({ where: { id: depot.id } });
    await prisma.dealership.delete({ where: { id: dealer.id } });
    await prisma.metro.delete({ where: { id: metro.id } });
  }
});
