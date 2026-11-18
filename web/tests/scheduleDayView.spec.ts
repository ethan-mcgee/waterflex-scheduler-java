import { test, expect, type Page } from "@playwright/test";
import { createPrismaClient } from "../lib/portalDatabase";
import { initialAvailability } from "../lib/technicianAvailability";
import { DEFAULT_CLIENT_ID } from "../lib/clients";
import { addCalendarDays } from "../lib/date";

const prisma = createPrismaClient();
test.afterAll(async () => { await prisma.$disconnect(); });

const DATE = "2026-10-07"; // a Wednesday
const at = (date: string, hour: number, minute = 0) => `${date}T${String(hour).padStart(2, "0")}:${String(minute).padStart(2, "0")}:00.000Z`;

type Fixture = { metroId: string; first: { id: string }; second: { id: string }; east: string; west: string; later: string; cleanup: () => Promise<void> };

/**
 * Two technicians in one metro with a visit each at 10:15 local (15:15 UTC) on DATE, so the day view must draw them
 * side by side at the same height, and one more visit for the first technician on the next day.
 */
async function seed(): Promise<Fixture> {
  const metro = await prisma.metro.create({ data: { name: "Day view metro", timezone: "America/Chicago" } });
  const dealership = await prisma.dealership.create({ data: { clientId: DEFAULT_CLIENT_ID, name: "Day view dealership" } });
  const depot = await prisma.depot.create({ data: { metroId: metro.id, dealershipId: dealership.id, name: "Day view depot", lat: 41.25, lng: -95.93,
    endpointPolicies: { create: { effectiveDate: new Date("1900-01-01T00:00:00Z"), departure: "DEPOT", returnTo: "HOME" } } } });
  const technician = (name: string, color: string) => prisma.technician.create({ data: { clientId: DEFAULT_CLIENT_ID, name, color,
    homeLat: 41.3, homeLng: -95.95, shiftStartMin: 480, shiftEndMin: 1020, availabilityVersions: initialAvailability(480, 1020),
    depotAssignments: { create: { depotId: depot.id, effectiveDate: new Date("1900-01-01T00:00:00Z") } } } });
  const first = await technician("Day First Tech", "#dc2626");
  const second = await technician("Day Second Tech", "#059669");
  const service = await prisma.serviceCatalog.create({ data: { code: "UI_DAY_VIEW_TEST", name: "Softener service", estDurationMin: 45 } });
  const visit = async (lastName: string, technicianId: string, date: string) => {
    const customer = await prisma.customer.create({ data: { clientId: DEFAULT_CLIENT_ID, firstName: "Day", lastName, email: `${lastName.toLowerCase()}@example.invalid`, phone: "4025550100" } });
    const address = await prisma.address.create({ data: { customerId: customer.id, line1: `1 ${lastName} St`, city: "Omaha", state: "NE", postalCode: "68102", lat: 41.27, lng: -95.9,
      geocodePrecision: "ROOFTOP", pinConfirmedAt: new Date("2026-09-01T00:00:00Z") } });
    const job = await prisma.job.create({ data: { customerId: customer.id, addressId: address.id, serviceId: service.id, durationMin: 45, status: "SCHEDULED" } });
    const appointment = await prisma.appointment.create({ data: { jobId: job.id, technicianId, serviceDate: new Date(`${date}T00:00:00Z`),
      windowStart: new Date(at(date, 15)), windowEnd: new Date(at(date, 17)), plannedStart: new Date(at(date, 15, 15)), plannedEnd: new Date(at(date, 16)), sequence: 0 } });
    return { customer, address, job, appointment };
  };
  const east = await visit("East", first.id, DATE);
  const west = await visit("West", second.id, DATE);
  const later = await visit("Later", first.id, addCalendarDays(DATE, 1));
  return { metroId: metro.id, first, second, east: east.appointment.id, west: west.appointment.id, later: later.appointment.id, cleanup: async () => {
    for (const item of [east, west, later]) {
      await prisma.appointment.delete({ where: { id: item.appointment.id } }); await prisma.job.delete({ where: { id: item.job.id } });
      await prisma.address.delete({ where: { id: item.address.id } }); await prisma.customer.delete({ where: { id: item.customer.id } });
    }
    await prisma.serviceCatalog.delete({ where: { id: service.id } });
    await prisma.technician.deleteMany({ where: { id: { in: [first.id, second.id] } } });
    await prisma.depot.delete({ where: { id: depot.id } }); await prisma.dealership.delete({ where: { id: dealership.id } });
    await prisma.metro.delete({ where: { id: metro.id } });
  } };
}

const dayGrid = (page: Page) => page.locator('[data-schedule-view="day"]');
const card = (page: Page, appointmentId: string) => dayGrid(page).locator(`[data-appointment-id="${appointmentId}"]`);

test.describe("the daily schedule", () => {
  let fixture: Fixture;
  test.beforeEach(async ({ page }) => {
    fixture = await seed();
    await page.setViewportSize({ width: 1500, height: 1500 });
  });
  test.afterEach(async () => { await fixture.cleanup(); });

  test("opens by default with technicians as columns, and the same time lines up across them", async ({ page }) => {
    await page.goto(`/work-orders/schedule?metroId=${fixture.metroId}&date=${DATE}`);
    await expect(dayGrid(page)).toBeVisible();
    await expect(page.locator('[data-schedule-view="week"]')).toHaveCount(0);
    await expect(page.getByRole("heading", { name: "Daily schedule" })).toBeVisible();
    await expect(page.getByRole("link", { name: "Day" })).toHaveAttribute("aria-current", "page");

    const firstHeader = dayGrid(page).locator(`[data-technician-column="${fixture.first.id}"]`);
    await expect(firstHeader).toContainText("Day First Tech");
    await expect(firstHeader).toContainText("8 AM to 5 PM");
    await expect(firstHeader).toHaveCSS("border-top-color", "rgb(220, 38, 38)");

    const east = await card(page, fixture.east).boundingBox(), west = await card(page, fixture.west).boundingBox();
    if (east === null || west === null) throw new Error("Both visits must be on screen");
    expect(Math.abs(east.y - west.y)).toBeLessThan(1);
    expect(Math.abs(east.height - west.height)).toBeLessThan(1);
    expect(west.x).toBeGreaterThan(east.x + east.width - 1);
    await expect(card(page, fixture.later)).toHaveCount(0);

    // With no date at all, the page still opens on a day, not a week.
    await page.goto(`/work-orders/schedule?metroId=${fixture.metroId}`);
    await expect(dayGrid(page)).toBeVisible();
  });

  test("pages forward and back a day at a time, across the end of the week", async ({ page }) => {
    await page.goto(`/work-orders/schedule?metroId=${fixture.metroId}&date=${DATE}`);
    await expect(card(page, fixture.east)).toBeVisible();

    await page.getByRole("button", { name: "Next day" }).click();
    const next = addCalendarDays(DATE, 1);
    await expect(page).toHaveURL(new RegExp(`date=${next}`));
    await expect(page.locator(`[data-schedule-date="${next}"]`)).toBeVisible();
    await expect(card(page, fixture.later)).toBeVisible();
    await expect(card(page, fixture.east)).toHaveCount(0);

    for (let day = 2; day <= 5; day++) await page.getByRole("button", { name: "Next day" }).click();
    await expect(page.locator(`[data-schedule-date="${addCalendarDays(DATE, 5)}"]`)).toBeVisible();

    for (let day = 5; day > 0; day--) await page.getByRole("button", { name: "Previous day" }).click();
    await expect(page.locator(`[data-schedule-date="${DATE}"]`)).toBeVisible();
    await expect(card(page, fixture.east)).toBeVisible();

    await page.getByLabel("Go to date").fill(next);
    await expect(page.locator(`[data-schedule-date="${next}"]`)).toBeVisible();
  });

  test("keeps the weekly grid for week links and the view switch", async ({ page }) => {
    await page.goto(`/work-orders/schedule?metroId=${fixture.metroId}&week=${DATE}`);
    await expect(page.locator('[data-schedule-view="week"]')).toBeVisible();
    await expect(page.getByRole("heading", { name: "Weekly schedule" })).toBeVisible();
    await expect(page.getByRole("link", { name: "Week" })).toHaveAttribute("aria-current", "page");

    await page.getByRole("link", { name: "Day" }).click();
    await expect(page.locator(`[data-schedule-date="${DATE}"]`)).toBeVisible();
    await expect(dayGrid(page)).toBeVisible();

    await page.getByRole("link", { name: "Week" }).click();
    await expect(page.locator('[data-schedule-view="week"]')).toBeVisible();
    await page.goto(`/work-orders/schedule?metroId=${fixture.metroId}&view=week`);
    await expect(page.locator('[data-schedule-view="week"]')).toBeVisible();
  });
});
