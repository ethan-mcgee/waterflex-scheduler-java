import { test, expect, type Locator, type Page } from "@playwright/test";
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
  const bookedJob = await prisma.job.create({ data: { customerId: customer.id, addressId: address.id, serviceId: softener.id, durationMin: 45, status: "SCHEDULED", workOrderType: "Maintenance" } });
  const visit = await prisma.appointment.create({ data: { jobId: bookedJob.id, technicianId: first.id, serviceDate: new Date(`${DATE}T00:00:00Z`),
    windowStart: at(15), windowEnd: at(17), plannedStart: at(15, 15), plannedEnd: at(16), sequence: 0 } });
  const waitingAddress = await prisma.address.create({ data: { customerId: customer.id, line1: "9 Waiting Ave", city: "Bellevue", state: "NE", postalCode: "68005",
    lat: 41.15, lng: -95.92, geocodePrecision: "ROOFTOP" } });
  const waitingJob = await prisma.job.create({ data: { customerId: customer.id, addressId: waitingAddress.id, serviceId: repair.id, durationMin: 60, status: "PENDING", workOrderType: "Repair",
    reasonCodes: { create: { position: 2, reasonCodeId: softener.id } } } });
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

const frame = (page: Page) => page.locator("[data-portal-frame]");
const calendar = (page: Page) => page.locator('[data-schedule-view="day"]');
const unassigned = (page: Page) => page.getByRole("complementary", { name: "Place a job" });

test.describe("the work orders section", () => {
  let fixture: Fixture;
  test.beforeEach(async ({ page }) => {
    fixture = await seed();
    await page.setViewportSize({ width: 1440, height: 1100 });
  });
  test.afterEach(async () => { await fixture.cleanup(); });

  test("opens in the portal frame, and every work orders page is one click away", async ({ page }) => {
    await page.goto("/work-orders");
    await expect(page).toHaveURL(/\/work-orders\/schedule/);
    await expect(frame(page)).toHaveAttribute("data-section", "work-orders");
    await expect(page.getByRole("heading", { name: "Work Orders Schedule" })).toBeVisible();

    const sections = page.getByRole("navigation", { name: "Pages", exact: true });
    for (const [label, path, title] of [["Recurring", "recurring", "Recurring work orders"], ["Outstanding", "outstanding", "Outstanding work orders"],
      ["Statistics", "statistics", "Work Orders Statistics"], ["Archive", "archive", "Work Orders Archive"], ["Schedule", "schedule", "Work Orders Schedule"]] as const) {
      await sections.getByRole("link", { name: label, exact: true }).click();
      await expect(page).toHaveURL(new RegExp(`/work-orders/${path}`));
      await expect(page.getByRole("heading", { name: title, level: 1 })).toBeVisible();
      await expect(sections.getByRole("link", { name: label, exact: true })).toHaveAttribute("aria-current", "page");
    }
    // A new work order is made on the schedule, so the menu has no page for it.
    await expect(sections.getByRole("link", { name: "New work order" })).toHaveCount(0);
  });

  test("the placeholder sections show no rows and keep their filters disabled", async ({ page }) => {
    await page.goto("/work-orders/archive");
    await expect(page.getByRole("main").getByRole("status")).toContainText("Archive is not built yet");
    for (const label of ["Technician", "Work order type", "Date from", "Date to"]) await expect(page.getByLabel(label, { exact: true })).toBeDisabled();
    await expect(page.locator("table")).toHaveCount(0);
  });

  test("the day calendar shows the metro's technicians, the booked visit at its time, and the waiting work order", async ({ page }) => {
    await page.goto(`/work-orders/schedule?metroId=${fixture.metroId}&date=${DATE}`);
    await expect(page.locator(`[data-schedule-date="${DATE}"]`)).toBeVisible();
    await expect(page.getByRole("navigation", { name: "Schedule view" }).getByRole("link", { name: "Calendar" })).toHaveAttribute("aria-current", "page");
    await expect(calendar(page).locator(`[data-technician-column="${fixture.first}"]`)).toContainText("Wo First Tech");
    await expect(calendar(page).locator(`[data-technician-column="${fixture.second}"]`)).toContainText("Wo Second Tech");

    const visit = calendar(page).locator(`[data-appointment-id="${fixture.visit}"]`);
    await expect(visit).toContainText("10:15");
    await expect(visit).toContainText("Wo Customer");
    await expect(visit).toHaveAttribute("title", "Wo Customer, Wo softener service");
    await expect(visit.getByRole("img", { name: "Scheduled" })).toBeVisible();
    await expect(unassigned(page).locator(`[data-waiting-order="${fixture.waitingJob}"]`)).toContainText("Wo repair call +1 more, 60 min");
    await expect(unassigned(page)).toContainText("Unassigned 1");

    await expect(page.getByLabel("Reason code", { exact: true })).toBeEnabled();
    await expect(page.getByLabel("Branch", { exact: true })).toBeDisabled();
  });

  test("filters hide visits and waiting work orders on the page and clear back", async ({ page }) => {
    await page.goto(`/work-orders/schedule?metroId=${fixture.metroId}&date=${DATE}`);
    const visit = calendar(page).locator(`[data-appointment-id="${fixture.visit}"]`);
    const waiting = unassigned(page).locator(`[data-waiting-order="${fixture.waitingJob}"]`);
    await expect(visit).toBeVisible();

    await page.getByLabel("Work order type").selectOption({ label: "Repair" });
    await expect(visit).toHaveCount(0);
    await expect(waiting).toBeVisible();

    await page.getByRole("button", { name: "Clear filters" }).click();
    await page.getByLabel("Reason code", { exact: true }).selectOption({ label: "Wo repair call" });
    await expect(visit).toHaveCount(0);
    await expect(waiting).toBeVisible();
    // A work order matches on any of its reason codes: the waiting one's second code is the visit's first.
    await page.getByLabel("Reason code", { exact: true }).selectOption({ label: "Wo softener service" });
    await expect(visit).toBeVisible();
    await expect(waiting).toBeVisible();

    await page.getByRole("button", { name: "Clear filters" }).click();
    await page.getByLabel("Status", { exact: true }).selectOption({ label: "Scheduled" });
    await expect(visit).toBeVisible();
    await expect(waiting).toHaveCount(0);
    await expect(unassigned(page)).toContainText("No waiting jobs match these filters.");

    await page.getByRole("button", { name: "Clear filters" }).click();
    await page.getByLabel("City / ZIP").fill("68005");
    await expect(visit).toHaveCount(0);
    await expect(waiting).toBeVisible();

    await page.getByLabel("City / ZIP").fill("Lincoln");
    await expect(visit).toHaveCount(0);
    await expect(waiting).toHaveCount(0);
    // The count is of everything waiting, not of what the filters show.
    await expect(unassigned(page)).toContainText("Unassigned 1");
  });

  test("technicians can be hidden and shown again, and Unassigned folds away", async ({ page }) => {
    await page.goto(`/work-orders/schedule?metroId=${fixture.metroId}&date=${DATE}`);
    await page.getByRole("button", { name: "Hide Wo Second Tech" }).click();
    await expect(calendar(page).locator(`[data-technician-column="${fixture.second}"]`)).toHaveCount(0);
    await page.getByLabel("Show a hidden technician").selectOption({ label: "Wo Second Tech" });
    await expect(calendar(page).locator(`[data-technician-column="${fixture.second}"]`)).toBeVisible();

    await page.getByRole("button", { name: "Collapse unassigned" }).click();
    await expect(page.locator("[data-unassigned-column]")).toHaveCount(0);
    await page.getByRole("button", { name: "Expand unassigned" }).click();
    await expect(page.locator("[data-unassigned-column]")).toBeVisible();
  });

  test("the calendar keeps one layout on a laptop and on a wide monitor", async ({ page }) => {
    const box = async (locator: Locator) => {
      const found = await locator.boundingBox();
      if (found === null) throw new Error("Expected the element on screen");
      return found;
    };
    for (const [width, height] of [[1280, 720], [2560, 1440]] as const) {
      await page.setViewportSize({ width, height });
      await page.goto(`/work-orders/schedule?metroId=${fixture.metroId}&date=${DATE}`);
      // The day and the technician picker share the calendar's head row.
      const day = await box(page.locator(`h2[data-schedule-date="${DATE}"]`));
      const picker = await box(page.getByRole("group", { name: "Display calendar for" }));
      expect(picker.y).toBeLessThan(day.y + day.height);
      expect(day.y).toBeLessThan(picker.y + picker.height);

      // Unassigned is the first column: its head starts on the technicians' line and is as tall as theirs.
      const head = await box(page.locator("[data-unassigned-head]"));
      const column = await box(calendar(page).locator(`[data-technician-column="${fixture.first}"]`));
      expect(Math.abs(head.y - column.y)).toBeLessThan(1);
      expect(Math.abs(head.height - column.height)).toBeLessThan(1);
      // The grid fills the board to the legend's edge rather than stopping at a fixed width.
      const grid = await box(calendar(page));
      const legend = await box(page.getByLabel("Schedule legend"));
      expect(Math.abs(grid.x + grid.width - (legend.x + legend.width))).toBeLessThan(2);

      // Folded, the strip runs the calendar's height and its count sits clear of the name.
      await page.getByRole("button", { name: "Collapse unassigned" }).click();
      const count = await box(page.locator("[data-unassigned-count]"));
      const label = await box(page.locator("[data-unassigned-label]"));
      expect(count.y + count.height <= label.y || label.y + label.height <= count.y).toBe(true);
      const strip = await box(unassigned(page));
      expect(strip.height).toBeGreaterThanOrEqual((await box(calendar(page))).height - 1);
      await page.getByRole("button", { name: "Expand unassigned" }).click();
    }
  });

  test("the map pages its own days, and Assign opens the day calendar with that work order being placed", async ({ page }) => {
    await page.goto(`/work-orders/schedule?metroId=${fixture.metroId}&date=${DATE}&view=map`);
    await page.getByRole("button", { name: "Next day" }).click();
    await expect(page.getByRole("heading", { name: "Thursday, 10/08/26" })).toBeVisible();
    await page.getByRole("button", { name: "Previous day" }).click();
    await expect(page.getByRole("heading", { name: "Wednesday, 10/07/26" })).toBeVisible();
    await expect(page).toHaveURL(new RegExp(`view=map.*date=${DATE}|date=${DATE}.*view=map`));

    const routes = page.getByRole("complementary", { name: "Routes" });
    await expect(routes.getByRole("region", { name: "Wo First Tech's route" })).toContainText("1 stop");
    await expect(routes.getByRole("region", { name: "Unassigned work orders" })).toContainText("Bellevue, NE 68005");
    await expect(page.locator("[data-work-orders-map]")).toBeVisible();

    await page.getByRole("navigation", { name: "Schedule view" }).getByRole("link", { name: "Dual View" }).click();
    await expect(calendar(page)).toBeVisible();
    await expect(page.locator("[data-work-orders-map]")).toBeVisible();

    await page.getByRole("navigation", { name: "Schedule view" }).getByRole("link", { name: "Map View" }).click();
    await routes.getByRole("link", { name: "Assign" }).click();
    await expect(page).toHaveURL(new RegExp(`/work-orders/schedule\\?.*placeJob=${fixture.waitingJob}`));
    await expect(calendar(page)).toBeVisible();
    await expect(unassigned(page).getByRole("heading", { name: "Wo Customer, Wo repair call" })).toBeVisible();
  });

  test("the dealership menu names the active client and no longer leads to a classic portal", async ({ page }) => {
    await page.goto("/work-orders/schedule");
    await page.locator("[data-dealership-menu]").click();
    const menu = page.getByRole("menu", { name: "Dealership" });
    await expect(menu.getByRole("menuitemradio", { checked: true })).toBeVisible();
    await expect(menu.getByRole("menuitem", { name: "Classic portal" })).toHaveCount(0);
  });

  test("switching dealership acts for the chosen client", async ({ page }) => {
    const other = await prisma.client.create({ data: { id: `wo-other-${Date.now()}`, name: "Work orders other client" } });
    try {
      await page.goto("/work-orders/schedule");
      await page.locator("[data-dealership-menu]").click();
      await page.getByRole("menuitemradio", { name: "Work orders other client" }).click();
      await expect(page.locator("[data-dealership-menu]")).toContainText("Work orders other client");
      await expect(page.getByRole("main").getByRole("status")).toContainText("No metro is set up yet");
    } finally {
      await page.context().clearCookies();
      await prisma.client.delete({ where: { id: other.id } });
    }
  });
});

test("every page opens in the one portal frame, under its own section, with no classic sidebar", async ({ page }) => {
  for (const [path, section, link] of [["/", "work-orders", null], ["/work-orders/schedule", "work-orders", "Schedule"],
    ["/work-orders/follow-up", "work-orders", "Follow-up"], ["/dispatch", "dispatch", "Optimize routes"], ["/technicians", "technicians", "Roster"],
    ["/technicians/time-off", "technicians", "Time off"], ["/branches", "branches", "Dealerships and depots"], ["/settings/solver", "settings", "Solver settings"]] as const) {
    await page.goto(path);
    await expect(frame(page)).toHaveAttribute("data-section", section);
    await expect(page.locator(".app-sidebar, #client-switcher")).toHaveCount(0);
    // The frame holds the page's only main landmark; pages draw no main of their own.
    await expect(page.getByRole("main")).toHaveCount(1);
    if (link !== null) await expect(page.getByRole("navigation", { name: "Pages", exact: true }).getByRole("link", { name: link, exact: true })).toHaveAttribute("aria-current", "page");
  }
  await page.getByRole("navigation", { name: "Sections" }).getByRole("link", { name: "Technicians" }).click();
  await expect(page).toHaveURL(/\/technicians$/);
  await page.getByRole("navigation", { name: "Sections" }).getByRole("link", { name: "Work orders" }).click();
  await expect(page).toHaveURL(/\/work-orders\/schedule/);
});

test("the pages' old addresses lead to their places in the portal, keeping the query", async ({ page }) => {
  for (const [from, to] of [["/book", /\/work-orders\/schedule\?new=1$/], ["/work-orders/new", /\/work-orders\/schedule\?new=1$/], ["/dispatch/follow-up", /\/work-orders\/follow-up$/],
    ["/time-off?status=ready", /\/technicians\/time-off\?status=ready$/], ["/dealerships", /\/branches$/], ["/settings", /\/settings\/solver$/],
    ["/schedule?week=2026-10-07&placeJob=job-1", /\/work-orders\/schedule\?week=2026-10-07&placeJob=job-1$/]] as const) {
    await page.goto(from);
    await expect(page).toHaveURL(to);
  }
});
