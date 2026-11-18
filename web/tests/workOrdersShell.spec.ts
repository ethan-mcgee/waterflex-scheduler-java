import { test, expect, type Page } from "@playwright/test";
import { createPrismaClient } from "../lib/portalDatabase";
import { initialAvailability } from "../lib/technicianAvailability";
import { DEFAULT_CLIENT_ID } from "../lib/clients";

const prisma = createPrismaClient();
test.afterAll(async () => { await prisma.$disconnect(); });

const DATE = "2026-10-07"; // a Wednesday
const at = (hour: number, minute = 0) => new Date(`${DATE}T${String(hour).padStart(2, "0")}:${String(minute).padStart(2, "0")}:00.000Z`);

type Fixture = { metroId: string; first: string; second: string; visit: string; waitingJob: string; cleanup: () => Promise<void> };

/**
 * A metro with two technicians: the first has one booked visit at 10:15 local (15:15 UTC) on DATE, and the client has
 * one more job waiting for a visit, of another type.
 */
async function seed(): Promise<Fixture> {
  const metro = await prisma.metro.create({ data: { name: "Work orders metro", timezone: "America/Chicago" } });
  const dealership = await prisma.dealership.create({ data: { clientId: DEFAULT_CLIENT_ID, name: "Work orders dealership" } });
  const depot = await prisma.depot.create({ data: { metroId: metro.id, dealershipId: dealership.id, name: "Work orders depot", lat: 41.25, lng: -95.93,
    endpointPolicies: { create: { effectiveDate: new Date("1900-01-01T00:00:00Z"), departure: "DEPOT", returnTo: "HOME" } } } });
  const technician = (name: string, color: string) => prisma.technician.create({ data: { clientId: DEFAULT_CLIENT_ID, name, color,
    homeLat: 41.3, homeLng: -95.95, shiftStartMin: 480, shiftEndMin: 1020, availabilityVersions: initialAvailability(480, 1020),
    depotAssignments: { create: { depotId: depot.id, effectiveDate: new Date("1900-01-01T00:00:00Z") } } } });
  const first = await technician("Wo First Tech", "#c2185b");
  const second = await technician("Wo Second Tech", "#5e35b1");
  const softener = await prisma.serviceCatalog.create({ data: { code: "UI_WO_SOFTENER", name: "Wo softener service", estDurationMin: 45 } });
  const repair = await prisma.serviceCatalog.create({ data: { code: "UI_WO_REPAIR", name: "Wo repair call", estDurationMin: 60 } });
  const customer = await prisma.customer.create({ data: { clientId: DEFAULT_CLIENT_ID, firstName: "Wo", lastName: "Customer", email: "wo@example.invalid", phone: "4025550100" } });
  const address = await prisma.address.create({ data: { customerId: customer.id, line1: "1 Work Order St", city: "Omaha", state: "NE", postalCode: "68102",
    lat: 41.27, lng: -95.9, geocodePrecision: "ROOFTOP", pinConfirmedAt: new Date("2026-09-01T00:00:00Z") } });
  const bookedJob = await prisma.job.create({ data: { customerId: customer.id, addressId: address.id, serviceId: softener.id, durationMin: 45, status: "SCHEDULED" } });
  const visit = await prisma.appointment.create({ data: { jobId: bookedJob.id, technicianId: first.id, serviceDate: new Date(`${DATE}T00:00:00Z`),
    windowStart: at(15), windowEnd: at(17), plannedStart: at(15, 15), plannedEnd: at(16), sequence: 0 } });
  const waitingAddress = await prisma.address.create({ data: { customerId: customer.id, line1: "9 Waiting Ave", city: "Bellevue", state: "NE", postalCode: "68005",
    lat: 41.15, lng: -95.92, geocodePrecision: "ROOFTOP" } });
  const waitingJob = await prisma.job.create({ data: { customerId: customer.id, addressId: waitingAddress.id, serviceId: repair.id, durationMin: 60, status: "PENDING" } });
  return { metroId: metro.id, first: first.id, second: second.id, visit: visit.id, waitingJob: waitingJob.id, cleanup: async () => {
    await prisma.appointment.delete({ where: { id: visit.id } });
    await prisma.job.deleteMany({ where: { id: { in: [bookedJob.id, waitingJob.id] } } });
    await prisma.address.deleteMany({ where: { id: { in: [address.id, waitingAddress.id] } } });
    await prisma.customer.delete({ where: { id: customer.id } });
    await prisma.serviceCatalog.deleteMany({ where: { id: { in: [softener.id, repair.id] } } });
    await prisma.technician.deleteMany({ where: { id: { in: [first.id, second.id] } } });
    await prisma.depot.delete({ where: { id: depot.id } }); await prisma.dealership.delete({ where: { id: dealership.id } });
    await prisma.metro.delete({ where: { id: metro.id } });
  } };
}

const shell = (page: Page) => page.locator("[data-work-orders-shell]");
const calendar = (page: Page) => page.locator("[data-work-orders-calendar]");

test.describe("the work orders section", () => {
  let fixture: Fixture;
  test.beforeEach(async ({ page }) => {
    fixture = await seed();
    await page.setViewportSize({ width: 1440, height: 1100 });
  });
  test.afterEach(async () => { await fixture.cleanup(); });

  test("draws its own frame instead of the classic sidebar, and every section is one click away", async ({ page }) => {
    await page.goto("/work-orders");
    await expect(page).toHaveURL(/\/work-orders\/schedule/);
    await expect(shell(page)).toBeVisible();
    await expect(page.locator(".app-sidebar")).toHaveCount(0);
    await expect(page.getByRole("heading", { name: "Work Orders Schedule" })).toBeVisible();

    const sections = page.getByRole("navigation", { name: "Work order sections" });
    for (const [label, path, title] of [["Recurring", "recurring", "Recurring work orders"], ["Outstanding", "outstanding", "Outstanding work orders"],
      ["Statistics", "statistics", "Work Orders Statistics"], ["Archive", "archive", "Work Orders Archive"], ["Schedule", "schedule", "Work Orders Schedule"]] as const) {
      await sections.getByRole("link", { name: label, exact: true }).click();
      await expect(page).toHaveURL(new RegExp(`/work-orders/${path}`));
      await expect(page.getByRole("heading", { name: title, level: 1 })).toBeVisible();
      await expect(sections.getByRole("link", { name: label, exact: true })).toHaveAttribute("aria-current", "page");
    }
  });

  test("the placeholder sections show no rows and keep their filters disabled", async ({ page }) => {
    await page.goto("/work-orders/recurring");
    await expect(page.getByRole("status")).toContainText("Recurring is not built yet");
    for (const label of ["Work order type", "Technician", "Branch", "City / ZIP", "Outreach status"]) await expect(page.getByLabel(label, { exact: true })).toBeDisabled();
    await expect(page.locator("table")).toHaveCount(0);
  });

  test("the calendar shows the metro's technicians, the booked visit at its time, and the waiting work order", async ({ page }) => {
    await page.goto(`/work-orders/schedule?metroId=${fixture.metroId}&date=${DATE}`);
    await expect(page.getByRole("heading", { name: "Wednesday, 10/07/26" })).toBeVisible();
    await expect(calendar(page).locator(`[data-technician-column="${fixture.first}"]`)).toHaveText("Wo First Tech");
    await expect(calendar(page).locator(`[data-technician-column="${fixture.second}"]`)).toHaveText("Wo Second Tech");

    const visit = calendar(page).locator(`[data-work-order="${fixture.visit}"]`);
    await expect(visit).toContainText("10:15");
    await expect(visit).toContainText("Omaha, NE 68102");
    await expect(visit).toContainText("Wo softener service");
    await expect(visit.getByRole("img", { name: "Scheduled" })).toBeVisible();
    await expect(calendar(page).locator(`[data-waiting-order="${fixture.waitingJob}"]`)).toContainText("Wo repair call");

    await expect(page.getByLabel("Reason code", { exact: true })).toBeDisabled();
    await expect(page.getByLabel("Branch", { exact: true })).toBeDisabled();
  });

  test("filters narrow the calendar on the page and clear back", async ({ page }) => {
    await page.goto(`/work-orders/schedule?metroId=${fixture.metroId}&date=${DATE}`);
    const visit = calendar(page).locator(`[data-work-order="${fixture.visit}"]`);
    const waiting = calendar(page).locator(`[data-waiting-order="${fixture.waitingJob}"]`);

    await page.getByLabel("Work order type").selectOption({ label: "Wo repair call" });
    await expect(visit).toHaveCount(0);
    await expect(waiting).toBeVisible();

    await page.getByRole("button", { name: "Clear filters" }).click();
    await page.getByLabel("Status", { exact: true }).selectOption({ label: "Scheduled" });
    await expect(visit).toBeVisible();
    await expect(waiting).toHaveCount(0);

    await page.getByRole("button", { name: "Clear filters" }).click();
    await page.getByLabel("City / ZIP").fill("68005");
    await expect(visit).toHaveCount(0);
    await expect(waiting).toBeVisible();

    await page.getByLabel("City / ZIP").fill("Lincoln");
    await expect(page.getByRole("status").filter({ hasText: "No work orders match these filters" })).toBeVisible();
  });

  test("technicians can be hidden and shown again, and Unassigned folds away", async ({ page }) => {
    await page.goto(`/work-orders/schedule?metroId=${fixture.metroId}&date=${DATE}`);
    await page.getByRole("button", { name: "Hide Wo Second Tech" }).click();
    await expect(calendar(page).locator(`[data-technician-column="${fixture.second}"]`)).toHaveCount(0);
    await page.getByLabel("Show a hidden technician").selectOption({ label: "Wo Second Tech" });
    await expect(calendar(page).locator(`[data-technician-column="${fixture.second}"]`)).toBeVisible();

    await page.getByRole("button", { name: "Collapse unassigned" }).click();
    await expect(page.locator("[data-unassigned-column]")).toBeHidden();
    await page.getByRole("button", { name: "Expand unassigned" }).click();
    await expect(page.locator("[data-unassigned-column]")).toBeVisible();
  });

  test("the day stepper and the view toggle keep the metro and the day", async ({ page }) => {
    await page.goto(`/work-orders/schedule?metroId=${fixture.metroId}&date=${DATE}`);
    await page.getByRole("link", { name: "Next day" }).click();
    await expect(page.getByRole("heading", { name: "Thursday, 10/08/26" })).toBeVisible();
    await page.getByRole("link", { name: "Previous day" }).click();
    await expect(page.getByRole("heading", { name: "Wednesday, 10/07/26" })).toBeVisible();

    await page.getByRole("navigation", { name: "Schedule view" }).getByRole("link", { name: "Map View" }).click();
    await expect(page).toHaveURL(new RegExp(`view=map.*date=${DATE}|date=${DATE}.*view=map`));
    const routes = page.getByRole("complementary", { name: "Routes" });
    await expect(routes.getByRole("region", { name: "Wo First Tech's route" })).toContainText("1 stop");
    await expect(routes.getByRole("region", { name: "Unassigned work orders" })).toContainText("Bellevue, NE 68005");
    await expect(routes.getByRole("link", { name: "Assign" })).toHaveAttribute("href", new RegExp(`/schedule\\?.*placeJob=${fixture.waitingJob}`));
    await expect(page.locator("[data-work-orders-map]")).toBeVisible();

    await page.getByRole("navigation", { name: "Schedule view" }).getByRole("link", { name: "Dual View" }).click();
    await expect(calendar(page)).toBeVisible();
    await expect(page.locator("[data-work-orders-map]")).toBeVisible();
  });

  test("the dealership menu names the active client and leads back to the classic portal", async ({ page }) => {
    await page.goto("/work-orders/schedule");
    await page.locator("[data-dealership-menu]").click();
    const menu = page.getByRole("menu", { name: "Dealership" });
    await expect(menu.getByRole("menuitemradio", { checked: true })).toBeVisible();
    await menu.getByRole("menuitem", { name: "Classic portal" }).click();
    await expect(page).toHaveURL(/\/schedule$/);
    await expect(page.locator(".app-sidebar")).toBeVisible();
  });

  test("switching dealership acts for the chosen client, like the classic client switcher", async ({ page }) => {
    const other = await prisma.client.create({ data: { id: `wo-other-${Date.now()}`, name: "Work orders other client" } });
    try {
      await page.goto("/work-orders/schedule");
      await page.locator("[data-dealership-menu]").click();
      await page.getByRole("menuitemradio", { name: "Work orders other client" }).click();
      await expect(page.locator("[data-dealership-menu]")).toContainText("Work orders other client");
      await expect(page.getByRole("status")).toContainText("No metro is set up yet");
    } finally {
      await page.context().clearCookies();
      await prisma.client.delete({ where: { id: other.id } });
    }
  });
});

test("the classic pages keep their sidebar shell and link to the work orders section", async ({ page }) => {
  for (const path of ["/schedule", "/dispatch", "/book"]) {
    await page.goto(path);
    await expect(page.locator(".app-sidebar")).toBeVisible();
    await expect(page.locator("[data-work-orders-shell]")).toHaveCount(0);
  }
  await page.getByRole("navigation", { name: "Scheduler sections" }).getByRole("link", { name: "Work orders" }).click();
  await expect(page).toHaveURL(/\/work-orders\/schedule/);
  await expect(page.locator("[data-work-orders-shell]")).toBeVisible();
});
