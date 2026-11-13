import { test, expect, type Page } from "@playwright/test";
import { createPrismaClient } from "../lib/portalDatabase";
import { initialAvailability } from "../lib/technicianAvailability";
import { DEFAULT_CLIENT_ID } from "../lib/clients";
import { moveCheckRequest, type MoveCheckResult } from "../lib/contracts";

const prisma = createPrismaClient();
test.afterAll(async () => { await prisma.$disconnect(); });

const DATE = "2026-10-05"; // a Monday
const at = (hour: number, minute = 0) => `${DATE}T${String(hour).padStart(2, "0")}:${String(minute).padStart(2, "0")}:00.000Z`;

type Fixture = { metroId: string; first: { id: string }; second: { id: string }; north: string; south: string; cleanup: () => Promise<void> };

/** A week with two technicians in one metro: the first has two visits on Monday, the second none. */
async function seed(): Promise<Fixture> {
  const metro = await prisma.metro.create({ data: { name: "Move browser metro", timezone: "America/Chicago" } });
  const dealership = await prisma.dealership.create({ data: { clientId: DEFAULT_CLIENT_ID, name: "Move browser dealership" } });
  const depot = await prisma.depot.create({ data: { metroId: metro.id, dealershipId: dealership.id, name: "Move browser depot", lat: 41.25, lng: -95.93,
    endpointPolicies: { create: { effectiveDate: new Date("1900-01-01T00:00:00Z"), departure: "DEPOT", returnTo: "HOME" } } } });
  const technician = (name: string, color: string) => prisma.technician.create({ data: { clientId: DEFAULT_CLIENT_ID, name, color,
    homeLat: 41.3, homeLng: -95.95, shiftStartMin: 480, shiftEndMin: 1020, availabilityVersions: initialAvailability(480, 1020),
    depotAssignments: { create: { depotId: depot.id, effectiveDate: new Date("1900-01-01T00:00:00Z") } } } });
  const first = await technician("Move First Tech", "#dc2626");
  const second = await technician("Move Second Tech", "#059669");
  const service = await prisma.serviceCatalog.create({ data: { code: "UI_MOVE_TEST", name: "Softener service", estDurationMin: 45 } });
  const visit = async (lastName: string, sequence: number, hour: number) => {
    const customer = await prisma.customer.create({ data: { clientId: DEFAULT_CLIENT_ID, firstName: "Move", lastName, email: `${lastName.toLowerCase()}@example.invalid`, phone: "4025550100" } });
    const address = await prisma.address.create({ data: { customerId: customer.id, line1: `${sequence + 1} Main St`, city: "Omaha", state: "NE", postalCode: "68102", lat: 41.27, lng: -95.9,
      geocodePrecision: "ROOFTOP", pinConfirmedAt: new Date("2026-09-01T00:00:00Z") } });
    const job = await prisma.job.create({ data: { customerId: customer.id, addressId: address.id, serviceId: service.id, durationMin: 45, status: "SCHEDULED" } });
    const appointment = await prisma.appointment.create({ data: { jobId: job.id, technicianId: first.id, serviceDate: new Date(`${DATE}T00:00:00Z`),
      windowStart: new Date(at(hour)), windowEnd: new Date(at(hour + 2)), plannedStart: new Date(at(hour, 15)), plannedEnd: new Date(at(hour + 1)), sequence } });
    return { customer, address, job, appointment };
  };
  const north = await visit("North", 0, 15);
  const south = await visit("South", 1, 18);
  return { metroId: metro.id, first, second, north: north.appointment.id, south: south.appointment.id, cleanup: async () => {
    for (const item of [north, south]) {
      await prisma.appointment.delete({ where: { id: item.appointment.id } }); await prisma.job.delete({ where: { id: item.job.id } });
      await prisma.address.delete({ where: { id: item.address.id } }); await prisma.customer.delete({ where: { id: item.customer.id } });
    }
    await prisma.serviceCatalog.delete({ where: { id: service.id } });
    await prisma.technician.deleteMany({ where: { id: { in: [first.id, second.id] } } });
    await prisma.depot.delete({ where: { id: depot.id } }); await prisma.dealership.delete({ where: { id: dealership.id } });
    await prisma.metro.delete({ where: { id: metro.id } });
  } };
}

const answer = (outcome: MoveCheckResult["outcome"], message: string): MoveCheckResult =>
  ({ outcome, message, overtimeMinutes: outcome === "FEASIBLE" ? 0 : null, saved: false });

/** Answers the move check with each response in turn, and keeps every request body it was sent. */
async function mockMoveCheck(page: Page, responses: Array<{ status: number; body: unknown }>) {
  const sent: unknown[] = [];
  await page.route("**/api/schedule/move-check", async route => {
    sent.push(route.request().postDataJSON());
    const next = responses.shift();
    if (next === undefined) throw new Error("An unexpected move check was sent");
    await route.fulfill({ status: next.status, contentType: "application/json", body: JSON.stringify(next.body) });
  });
  return sent;
}

const grid = (page: Page) => page.locator('[aria-label="Weekly technician schedule"]');
const card = (page: Page, appointmentId: string) => grid(page).locator(`[data-appointment-id="${appointmentId}"]`);
const banner = (page: Page) => page.locator("[data-move-check]");
const cell = (page: Page, technicianId: string, date = DATE) => grid(page).locator(`[data-drop-technician="${technicianId}"][data-drop-date="${date}"]`);

/** A real mouse drag: press on the card, move in steps, release over the cell. */
async function dragTo(page: Page, appointmentId: string, technicianId: string, date = DATE) {
  const from = await card(page, appointmentId).boundingBox();
  const to = await cell(page, technicianId, date).boundingBox();
  if (from === null || to === null) throw new Error("The card or the cell is not on screen");
  await page.mouse.move(from.x + from.width / 2, from.y + from.height / 2);
  await page.mouse.down();
  await page.mouse.move(to.x + to.width / 2, to.y + 60, { steps: 12 });
  await page.mouse.up();
}

test.describe("checking a move on the weekly schedule", () => {
  let fixture: Fixture;
  test.beforeEach(async ({ page }) => {
    fixture = await seed();
    await page.setViewportSize({ width: 1500, height: 1500 });
    await page.goto(`/schedule?metroId=${fixture.metroId}&week=${DATE}`);
    await expect(card(page, fixture.north)).toBeVisible();
  });
  test.afterEach(async () => { await fixture.cleanup(); });

  test("dragging a visit to another technician shows the answer, saves nothing and records the timing", async ({ page }) => {
    const sent = await mockMoveCheck(page, [{ status: 200, body: answer("FEASIBLE", "Every arrival window, shift and paid limit still holds.") }]);
    await dragTo(page, fixture.north, fixture.second.id);
    await expect(banner(page)).toHaveAttribute("role", "status");
    await expect(banner(page)).toContainText("Feasible: Move North on Move Second Tech's route.");
    await expect(banner(page)).toContainText("Nothing was saved.");
    expect(sent).toHaveLength(1);
    expect(moveCheckRequest.parse(sent[0])).toEqual({ metroId: fixture.metroId, serviceDate: DATE, appointmentId: fixture.north, targetTechnicianId: fixture.second.id });
    await expect(cell(page, fixture.first.id).locator(`[data-appointment-id="${fixture.north}"]`)).toBeVisible();
    await expect.poll(() => page.evaluate(() => performance.getEntriesByName("waterflex.move-check", "measure").length)).toBe(1);
    await expect(page.getByRole("dialog")).toHaveCount(0);
  });

  test("a move that does not fit and a check that fails are both shown", async ({ page }) => {
    await mockMoveCheck(page, [{ status: 200, body: answer("NOT_FEASIBLE", "With this move a customer's arrival window, a shift or a paid limit would be missed.") },
      { status: 503, body: { error: "The scheduler is busy. Try again shortly." } }]);
    await dragTo(page, fixture.south, fixture.second.id);
    await expect(page.locator("[data-move-check]")).toHaveAttribute("data-move-check", "NOT_FEASIBLE");
    await expect(banner(page)).toContainText("Not feasible: Move South on Move Second Tech's route.");
    await dragTo(page, fixture.north, fixture.second.id);
    await expect(banner(page)).toHaveAttribute("role", "alert");
    await expect(banner(page)).toContainText("Could not check Move North on Move Second Tech's route: The scheduler is busy. Try again shortly.");
  });

  test("a drop on the same technician or another day sends nothing, and a click still opens the visit", async ({ page }) => {
    const sent = await mockMoveCheck(page, []);
    await dragTo(page, fixture.north, fixture.first.id);
    await dragTo(page, fixture.north, fixture.second.id, "2026-10-06");
    await expect(page.locator("[data-move-check]")).toHaveCount(0);
    expect(sent).toHaveLength(0);
    await card(page, fixture.north).click();
    await expect(page.getByRole("dialog")).toContainText("Appointment details");
  });

  test("the move can be checked from the keyboard in the visit's details", async ({ page }) => {
    const sent = await mockMoveCheck(page, [{ status: 200, body: answer("FEASIBLE", "Every arrival window, shift and paid limit still holds.") }]);
    await card(page, fixture.south).focus();
    await page.keyboard.press("Enter");
    const dialog = page.getByRole("dialog");
    await dialog.getByLabel("Check a move to").selectOption({ label: "Move Second Tech" });
    await dialog.getByRole("button", { name: "Check move" }).click();
    await expect(banner(page)).toContainText("Feasible: Move South on Move Second Tech's route.");
    expect(moveCheckRequest.parse(sent[0]).appointmentId).toBe(fixture.south);
  });
});
