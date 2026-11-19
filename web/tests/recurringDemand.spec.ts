import { test, expect } from "@playwright/test";
import { createPrismaClient } from "../lib/portalDatabase";
import { DEFAULT_CLIENT_ID } from "../lib/clients";
import { initialAvailability } from "../lib/technicianAvailability";
import { localMidnightUtc, todayInTz } from "../lib/date";
import { demandWeeks, weekdays } from "../lib/recurringDemandCore";

const prisma = createPrismaClient();
test.afterAll(async () => { await prisma.$disconnect(); });

const TZ = "America/Chicago";
// Far from every other fixture's depot, so these accounts belong to this branch only.
const DEPOT = { lat: 35.08, lng: -106.65 };

async function seed() {
  const today = todayInTz(TZ);
  const week = demandWeeks(today)[0];
  if (week === undefined) throw new Error("No demand week");
  const day = weekdays(week).find(date => date >= today);
  if (day === undefined) throw new Error("No working day left in the first week");
  const metro = await prisma.metro.create({ data: { name: "Recurring browser metro", timezone: TZ, serviceRadiusMi: 30 } });
  const dealership = await prisma.dealership.create({ data: { clientId: DEFAULT_CLIENT_ID, name: "Recurring browser dealership" } });
  const depot = await prisma.depot.create({ data: { metroId: metro.id, dealershipId: dealership.id, name: "Recurring browser branch", ...DEPOT } });
  const tech = await prisma.technician.create({ data: { clientId: DEFAULT_CLIENT_ID, name: "Recurring Tech", color: "#0c59b3", homeLat: DEPOT.lat, homeLng: DEPOT.lng,
    shiftStartMin: 480, shiftEndMin: 1020, availabilityVersions: initialAvailability(480, 1020),
    depotAssignments: { create: { depotId: depot.id, effectiveDate: new Date("1900-01-01T00:00:00Z") } } } });
  const filter = await prisma.serviceCatalog.create({ data: { code: "UI_RECUR_FILTER", name: "Recurring filter change", estDurationMin: 45, bufferPct: 0 } });
  const account = (number: string, name: string, offset: number | null) => prisma.account.create({ data: { clientId: DEFAULT_CLIENT_ID,
    accountNumber: number, name, phone: "4025550111", email: `${number.toLowerCase()}@example.invalid`, line1: `${number} Recurring Way`, city: "Albuquerque",
    state: "NM", postalCode: "87102", lat: offset === null ? null : DEPOT.lat + offset, lng: offset === null ? null : DEPOT.lng, source: "WATERFLEX_IMPORT", importedAt: new Date() } });
  const accounts = [await account("UI-RECUR-1", "Recurring First", 0.01), await account("UI-RECUR-2", "Recurring Second", 0.02), await account("UI-RECUR-3", "Recurring Unlocated", null)];
  const series = await Promise.all(accounts.map(item => prisma.recurringSeries.create({ data: { clientId: DEFAULT_CLIENT_ID, accountId: item.id, serviceId: filter.id,
    workOrderType: "Maintenance", intervalUnit: "MONTH", intervalCount: 3, startDate: new Date(`${day}T00:00:00Z`) } })));
  // A booked visit near the due customers puts the technician in the area that day, from 9:00 to 10:00.
  const customer = await prisma.customer.create({ data: { clientId: DEFAULT_CLIENT_ID, firstName: "Booked", lastName: "Neighbor", email: "n@example.invalid", phone: "4025550100" } });
  const address = await prisma.address.create({ data: { customerId: customer.id, line1: "1 Neighbor St", city: "Albuquerque", state: "NM", postalCode: "87102",
    lat: DEPOT.lat + 0.015, lng: DEPOT.lng } });
  const job = await prisma.job.create({ data: { customerId: customer.id, addressId: address.id, serviceId: filter.id, durationMin: 60, status: "SCHEDULED" } });
  const midnight = localMidnightUtc(day, TZ).getTime();
  const at = (minute: number) => new Date(midnight + minute * 60_000);
  const visit = await prisma.appointment.create({ data: { jobId: job.id, technicianId: tech.id, serviceDate: new Date(`${day}T00:00:00Z`),
    windowStart: at(540), windowEnd: at(660), plannedStart: at(540), plannedEnd: at(600), sequence: 0 } });

  const cleanup = async () => {
    const customers = (await prisma.customer.findMany({ where: { OR: [{ id: customer.id }, { accountId: { in: accounts.map(item => item.id) } }] }, select: { id: true } }))
      .map(item => item.id);
    await prisma.reservation.deleteMany({ where: { seriesId: { in: series.map(item => item.id) } } });
    await prisma.recurringSeries.deleteMany({ where: { id: { in: series.map(item => item.id) } } });
    await prisma.appointment.deleteMany({ where: { id: visit.id } });
    await prisma.job.deleteMany({ where: { customerId: { in: customers } } });
    await prisma.address.deleteMany({ where: { customerId: { in: customers } } });
    await prisma.customer.deleteMany({ where: { id: { in: customers } } });
    await prisma.account.deleteMany({ where: { id: { in: accounts.map(item => item.id) } } });
    await prisma.technicianDepotAssignment.deleteMany({ where: { technicianId: tech.id } });
    await prisma.technicianAvailabilityVersion.deleteMany({ where: { technicianId: tech.id } });
    await prisma.technician.delete({ where: { id: tech.id } });
    await prisma.serviceCatalog.delete({ where: { id: filter.id } });
    await prisma.depot.delete({ where: { id: depot.id } });
    await prisma.dealership.delete({ where: { id: dealership.id } });
    await prisma.metro.delete({ where: { id: metro.id } });
  };
  const [first, second, unlocated] = series;
  if (first === undefined || second === undefined || unlocated === undefined) throw new Error("Series were not created");
  return { day, week, depotId: depot.id, metroId: metro.id, techId: tech.id, first, second, unlocated, cleanup };
}

test("due customers are reserved in free time on a day the technician is in the area, then released or booked", async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 1100 });
  const fixture = await seed();
  try {
    await page.goto("/work-orders/recurring");
    await expect(page.getByRole("navigation", { name: "Recurring views" }).getByRole("link", { name: "Demand" })).toHaveAttribute("aria-current", "page");
    const cell = page.locator(`[data-demand-cell="${fixture.depotId}|${fixture.week}"]`);
    // Two located customers are due; the unlocated one has no branch. The shift's 9 hours less the 1 hour visit are free.
    await expect(cell).toContainText("2 not booked");
    await expect(cell).toContainText("0 booked · 0 reserved");
    await cell.click();
    await expect(cell).toHaveAttribute("aria-pressed", "true");
    await expect(page.locator(`[data-week-day="${fixture.day}"]`)).toContainText("9:00 · Booked Neighbor");

    const panel = page.getByRole("complementary", { name: "Reserve times" });
    await expect(panel.getByRole("heading", { name: "Reserve times for 2 customers" })).toBeVisible();
    const near = panel.getByRole("list", { name: "Days in the area" });
    await expect(near.getByRole("checkbox")).toBeChecked();
    await expect(near).toContainText("Recurring Tech");
    await panel.getByRole("button", { name: "Reserve 2 times" }).click();
    await expect(panel.getByRole("status")).toHaveText("Reserved 2 times.");

    // The free time before the visit holds one 45-minute reservation; the next starts after the visit, never on it.
    const reservations = await prisma.reservation.findMany({ where: { seriesId: { in: [fixture.first.id, fixture.second.id] } }, orderBy: { startMin: "asc" } });
    expect(reservations.map(item => [item.startMin, item.endMin, item.status, item.technicianId])).toEqual([
      [480, 525, "RESERVED", fixture.techId], [600, 645, "RESERVED", fixture.techId]]);
    await expect(page.locator(`[data-week-day="${fixture.day}"] [data-reserved]`)).toHaveCount(2);
    await expect(cell).toContainText("0 booked · 2 reserved");

    // A second run for a reserved customer is refused rather than reserving it twice.
    const again = await page.request.post("/api/recurring/reservations", { data: { branchId: fixture.depotId, week: fixture.week,
      days: [{ technicianId: fixture.techId, date: fixture.day }], seriesIds: [fixture.first.id] } });
    expect(again.status()).toBe(409);

    const [early, late] = reservations;
    if (early === undefined || late === undefined) throw new Error("Two reservations expected");
    const released = panel.locator(`[data-series="${early.seriesId}"]`);
    await released.getByRole("button", { name: "Release" }).click();
    await expect(released).toContainText("Not reserved");
    expect((await prisma.reservation.findUniqueOrThrow({ where: { id: early.id } })).status).toBe("RELEASED");

    const booked = panel.locator(`[data-series="${late.seriesId}"]`);
    await booked.getByRole("button", { name: "Book" }).click();
    const form = booked.getByRole("form");
    await form.getByLabel("First name").fill("Recurring");
    await form.getByLabel("Last name").fill("Customer");
    await expect(form.getByLabel("Phone")).toHaveValue("4025550111");
    await form.getByRole("button", { name: "Create work order" }).click();
    const place = booked.getByRole("link", { name: "Place on calendar" });
    await expect(place).toBeVisible();
    const row = await prisma.reservation.findUniqueOrThrow({ where: { id: late.id }, include: { job: { include: { customer: true, service: true } } } });
    expect(row.status).toBe("BOOKED");
    expect(row.job).toMatchObject({ status: "PENDING", workOrderType: "Maintenance", durationMin: 45, service: { code: "UI_RECUR_FILTER" },
      customer: { firstName: "Recurring", lastName: "Customer" } });
    await expect(place).toHaveAttribute("href", `/work-orders/schedule?metroId=${fixture.metroId}&date=${fixture.day}&placeJob=${row.jobId ?? ""}`);
    await expect(cell).toContainText("1 not booked");
    await expect(cell).toContainText("1 booked · 0 reserved");

    await page.getByRole("navigation", { name: "Recurring views" }).getByRole("link", { name: "List" }).click();
    await expect(page.locator(`[data-series-row="${late.seriesId}"]`)).toContainText("Booked");
    await expect(page.locator(`[data-series-row="${early.seriesId}"]`)).toContainText("Recurring browser branch");
    await expect(page.locator(`[data-series-row="${fixture.unlocated.id}"]`)).toContainText("None: no WaterFlex coordinates");
  } finally {
    await fixture.cleanup();
  }
});
