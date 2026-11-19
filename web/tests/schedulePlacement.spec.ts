import { test, expect, type Page } from "@playwright/test";
import { createPrismaClient } from "../lib/portalDatabase";
import { initialAvailability } from "../lib/technicianAvailability";
import { DEFAULT_CLIENT_ID } from "../lib/clients";
import { placementPreview, placementPreviewRequest, placementSaveRequest, type PlacementPreview } from "../lib/contracts";

const prisma = createPrismaClient();
test.afterAll(async () => { await prisma.$disconnect(); });

const DATE = "2026-10-05"; // a Monday; America/Chicago is UTC-5, so 15:00 UTC is 10 AM
const utc = (hour: number, minute = 0) => `${DATE}T${String(hour).padStart(2, "0")}:${String(minute).padStart(2, "0")}:00.000Z`;
const LOCAL_START_MINUTE = 8 * 60; // the timeline starts at the 8 AM shift start
const HOUR_PX = 64;

type Fixture = { metroId: string; first: string; second: string; north: string; south: string; job: string; unlocated: string; cleanup: () => Promise<void> };

/**
 * Two technicians working 8 AM to 5 PM: the first has North (10 AM window) and South (1 PM window), the second has
 * none. Two jobs wait for the calendar: one located, one without coordinates.
 */
async function seed(): Promise<Fixture> {
  const metro = await prisma.metro.create({ data: { name: "Placement browser metro", timezone: "America/Chicago" } });
  const dealership = await prisma.dealership.create({ data: { clientId: DEFAULT_CLIENT_ID, name: "Placement browser dealership" } });
  const depot = await prisma.depot.create({ data: { metroId: metro.id, dealershipId: dealership.id, name: "Placement browser depot", lat: 41.25, lng: -95.93,
    endpointPolicies: { create: { effectiveDate: new Date("1900-01-01T00:00:00Z"), departure: "DEPOT", returnTo: "HOME" } } } });
  const technician = (name: string, color: string) => prisma.technician.create({ data: { clientId: DEFAULT_CLIENT_ID, name, color,
    homeLat: 41.3, homeLng: -95.95, shiftStartMin: 480, shiftEndMin: 1020, availabilityVersions: initialAvailability(480, 1020),
    depotAssignments: { create: { depotId: depot.id, effectiveDate: new Date("1900-01-01T00:00:00Z") } } } });
  const first = await technician("Place First Tech", "#dc2626");
  const second = await technician("Place Second Tech", "#059669");
  const service = await prisma.serviceCatalog.create({ data: { code: "UI_PLACE_TEST", name: "Softener install", estDurationMin: 60 } });
  const customer = async (lastName: string, located: boolean) => {
    const person = await prisma.customer.create({ data: { clientId: DEFAULT_CLIENT_ID, firstName: "Place", lastName, email: `${lastName.toLowerCase()}@example.invalid`, phone: "4025550100" } });
    const address = await prisma.address.create({ data: { customerId: person.id, line1: `1 ${lastName} St`, city: "Omaha", state: "NE", postalCode: "68102",
      lat: located ? 41.27 : null, lng: located ? -95.9 : null, geocodePrecision: located ? "ROOFTOP" : null } });
    return { person, address };
  };
  const visit = async (lastName: string, sequence: number, hour: number) => {
    const { person, address } = await customer(lastName, true);
    const job = await prisma.job.create({ data: { customerId: person.id, addressId: address.id, serviceId: service.id, durationMin: 45, status: "SCHEDULED" } });
    const appointment = await prisma.appointment.create({ data: { jobId: job.id, technicianId: first.id, serviceDate: new Date(`${DATE}T00:00:00Z`),
      windowStart: new Date(utc(hour)), windowEnd: new Date(utc(hour + 2)), plannedStart: new Date(utc(hour, 15)), plannedEnd: new Date(utc(hour + 1)), sequence } });
    return { person, address, job, appointment };
  };
  const pending = async (lastName: string, located: boolean) => {
    const { person, address } = await customer(lastName, located);
    const job = await prisma.job.create({ data: { customerId: person.id, addressId: address.id, serviceId: service.id, durationMin: 60, status: "PENDING" } });
    return { person, address, job };
  };
  const north = await visit("North", 0, 15);
  const south = await visit("South", 1, 18);
  const waiting = await pending("Waiting", true);
  const unlocated = await pending("Unlocated", false);
  return { metroId: metro.id, first: first.id, second: second.id, north: north.appointment.id, south: south.appointment.id,
    job: waiting.job.id, unlocated: unlocated.job.id, cleanup: async () => {
      for (const item of [north, south]) await prisma.appointment.delete({ where: { id: item.appointment.id } });
      for (const item of [north, south, waiting, unlocated]) {
        await prisma.job.delete({ where: { id: item.job.id } });
        await prisma.address.delete({ where: { id: item.address.id } }); await prisma.customer.delete({ where: { id: item.person.id } });
      }
      await prisma.serviceCatalog.delete({ where: { id: service.id } });
      // A real undo locks the days it writes, which leaves their schedule_day rows behind.
      await prisma.scheduleDay.deleteMany({ where: { technicianId: { in: [first.id, second.id] } } });
      await prisma.technician.deleteMany({ where: { id: { in: [first.id, second.id] } } });
      await prisma.depot.delete({ where: { id: depot.id } }); await prisma.dealership.delete({ where: { id: dealership.id } });
      await prisma.metro.delete({ where: { id: metro.id } });
    } };
}

const totals = (driveMinutes: number, costCents: number) => ({ driveMinutes, meters: driveMinutes * 1000, paidMinutes: 480, costCents, bufferSeconds: 0 });
const leg = (minutes: number) => ({ seconds: minutes * 50, meters: minutes * 900, bufferedMinutes: minutes });
const start = (hour: number, minute: number, timed: ReturnType<typeof totals>, windowHour = hour, keepsWindow?: boolean) => ({ plannedStart: utc(hour, minute),
  window: { start: utc(windowHour), end: utc(windowHour + 2) }, timed, ...(keepsWindow === undefined ? {} : { keepsWindow }) });
const gap = (after: string | undefined, before: string | undefined, added: number, starts: ReturnType<typeof start>[], replaced = true) => ({
  ...(after === undefined ? {} : { afterAppointmentId: after }), ...(before === undefined ? {} : { beforeAppointmentId: before }), routable: true,
  toJob: leg(added), fromJob: leg(8), ...(replaced ? { replaced: leg(8) } : {}), addedSeconds: added * 50, addedMeters: added * 900, addedBufferedMinutes: added, starts });

/** The week's answer for the waiting job: nothing fits before North, two starts between North and South, one after South, one on the second route. */
function jobPreview(fixture: Fixture): PlacementPreview {
  return placementPreview.parse({ original: null, job: { id: fixture.job, durationMinutes: 60 }, answer: { routingIdentity: "omaha", skippedTechnicianDays: [], technicianDays: [
    { technicianId: fixture.first, serviceDate: DATE, holdsAsGiven: true, qualified: true, asGiven: totals(60, 15000), gaps: [
      gap(undefined, fixture.north, 20, []),
      gap(fixture.north, fixture.south, 6, [start(16, 30, totals(66, 15900), 16), start(17, 0, totals(66, 16000), 17)]),
      gap(fixture.south, undefined, 12, [start(19, 30, totals(72, 16500), 19)])] },
    { technicianId: fixture.second, serviceDate: DATE, holdsAsGiven: true, qualified: true, asGiven: totals(0, 0), gaps: [
      gap(undefined, undefined, 30, [start(14, 0, totals(30, 9000), 14)], false)] },
  ] } });
}

/** The week's answer for moving South: its own place after North keeps its 1 PM window; the second route keeps it at 1 PM or changes it at 3:30 PM. */
function movePreview(fixture: Fixture): PlacementPreview {
  return placementPreview.parse({ job: { id: fixture.south, durationMinutes: 45 },
    original: { appointmentId: fixture.south, technicianId: fixture.first, serviceDate: DATE, window: { start: utc(18), end: utc(20) },
      plannedStart: utc(18, 15), afterAppointmentId: fixture.north },
    answer: { routingIdentity: "omaha", skippedTechnicianDays: [], technicianDays: [
      { technicianId: fixture.first, serviceDate: DATE, holdsAsGiven: true, qualified: true, asGiven: totals(40, 11000), gaps: [
        gap(undefined, fixture.north, 25, []),
        gap(fixture.north, undefined, 14, [start(18, 0, totals(54, 13500), 18, true), start(18, 30, totals(54, 13600), 18, true)])] },
      { technicianId: fixture.second, serviceDate: DATE, holdsAsGiven: true, qualified: true, asGiven: totals(0, 0), gaps: [
        gap(undefined, undefined, 9, [start(18, 0, totals(9, 2200), 18, true), start(20, 30, totals(9, 2400), 20, false)], false)] },
    ] } });
}

/** Answers the preview with `preview` and the save with `saved`, keeping every body sent to either. */
async function mockPlacement(page: Page, preview: PlacementPreview, saved: { status: number; body: unknown }) {
  const previews: unknown[] = [], saves: unknown[] = [];
  await page.route("**/api/schedule/placement", async route => {
    previews.push(route.request().postDataJSON());
    await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(preview) });
  });
  await page.route("**/api/schedule/placement/save", async route => {
    saves.push(route.request().postDataJSON());
    await route.fulfill({ status: saved.status, contentType: "application/json", body: JSON.stringify(saved.body) });
  });
  return { previews, saves };
}

const grid = (page: Page) => page.locator('[data-schedule-view="day"]');
const panel = (page: Page) => page.getByRole("complementary", { name: "Place a job" });
const cell = (page: Page, technicianId: string) => grid(page).locator(`[data-drop-technician="${technicianId}"][data-drop-date="${DATE}"]`);
const marker = (page: Page, technicianId: string, after: string | undefined, before: string | undefined) =>
  grid(page).locator(`[data-gap-key="${technicianId}|${DATE}|${after ?? "start"}|${before ?? "end"}"]`);

/** A real mouse drag from a card to a technician's timeline at a local minute of the day. */
async function dragTo(page: Page, from: ReturnType<Page["locator"]>, technicianId: string, localMinute: number) {
  const source = await from.boundingBox();
  const target = await cell(page, technicianId).boundingBox();
  if (source === null || target === null) throw new Error("The card or the timeline is not on screen");
  await page.mouse.move(source.x + source.width / 2, source.y + source.height / 2);
  await page.mouse.down();
  await page.mouse.move(target.x + target.width / 2, target.y + ((localMinute - LOCAL_START_MINUTE) / 60) * HOUR_PX, { steps: 12 });
  await page.mouse.up();
}

test.describe("placing and moving on the schedule", () => {
  let fixture: Fixture;
  test.beforeEach(async ({ page }) => {
    fixture = await seed();
    await page.setViewportSize({ width: 1600, height: 1400 });
  });
  test.afterEach(async () => { await fixture.cleanup(); });

  test("a waiting job is placed from one preview: every gap shows its drive, and the chosen start is saved", async ({ page }) => {
    const sent = await mockPlacement(page, jobPreview(fixture), { status: 200, body: { saved: true, appointmentId: fixture.job, kind: "NEW", undoId: "undo-1" } });
    await page.goto(`/work-orders/schedule?metroId=${fixture.metroId}&date=${DATE}`);
    await expect(panel(page).locator(`[data-job-id="${fixture.unlocated}"]`)).toBeDisabled();
    await expect(panel(page).locator(`[data-job-id="${fixture.unlocated}"]`)).toContainText("Needs its pin confirmed");
    await panel(page).locator(`[data-job-id="${fixture.job}"]`).click();

    // The cheapest gap is recommended; every other gap is said against it.
    await expect(marker(page, fixture.first, fixture.north, fixture.south)).toHaveText("Best, +6 min drive");
    await expect(marker(page, fixture.first, fixture.north, fixture.south)).toHaveAttribute("data-gap-best", "true");
    // A place where no start fits is not marked at all; dropping there still says why.
    await expect(marker(page, fixture.first, undefined, fixture.north)).toHaveCount(0);
    await expect(marker(page, fixture.second, undefined, undefined)).toHaveText("+24 min vs best");
    await expect(marker(page, fixture.first, fixture.south, undefined)).toHaveText("+6 min vs best");
    // The four best arrival windows to offer, the recommendation first, each with its added driving.
    const options = panel(page).getByRole("list", { name: "Recommended times" }).getByRole("button");
    await expect(options).toHaveCount(4);
    await expect(options.nth(0)).toContainText("11:00 AM to 1:00 PM");
    await expect(options.nth(0)).toContainText("Mon, Oct 5, Place First Tech, from 11:30 AM");
    await expect(options.nth(0)).toHaveAttribute("data-option-best", "true");
    await expect(options.nth(0)).toContainText("+6 min");
    await expect(options.nth(1)).toContainText("12:00 PM to 2:00 PM");
    await expect(options.nth(2)).toContainText("2:00 PM to 4:00 PM");
    await expect(options.nth(2)).toContainText("+12 min");
    await expect(options.nth(3)).toContainText("9:00 AM to 11:00 AM");
    await expect(options.nth(3)).toContainText("Place Second Tech");
    await expect(options.nth(3)).toContainText("+30 min");
    expect(sent.previews).toHaveLength(1);
    expect(placementPreviewRequest.parse(sent.previews[0])).toEqual({ metroId: fixture.metroId, monday: DATE, jobId: fixture.job,
      timedDates: [DATE], withRestOfWeek: true });

    // Hovering and choosing are worked out on the page: no further preview is asked for.
    // The selected job opens on the recommended place, ready to save.
    await expect(options.nth(0)).toHaveAttribute("aria-pressed", "true");
    await expect(panel(page).getByRole("radio", { name: "11:30 AM" })).toBeChecked();
    await expect(panel(page).locator("[data-placement-state]")).toHaveAttribute("data-placement-state", "NEW");
    // Another option, a start chip and a place on the calendar all choose the same way, and the list follows.
    await options.nth(1).click();
    await expect(options.nth(1)).toHaveAttribute("aria-pressed", "true");
    await expect(panel(page).getByRole("radio", { name: "12:00 PM" })).toBeChecked();
    await expect(grid(page).locator('[data-placement-ghost="NEW"]')).toBeVisible();
    await expect(grid(page).locator('[data-placement-ghost="NEW"]')).toContainText("6 min there");
    await marker(page, fixture.first, fixture.south, undefined).click();
    await expect(options.nth(2)).toHaveAttribute("aria-pressed", "true");
    await expect(panel(page).getByRole("radio", { name: "11:30 AM" })).toHaveCount(0);
    await options.nth(0).click();
    await panel(page).getByRole("radio", { name: "12:00 PM" }).check();
    await expect(options.nth(1)).toHaveAttribute("aria-pressed", "true");
    expect(sent.previews).toHaveLength(1);

    await panel(page).getByRole("button", { name: "Save" }).click();
    await expect(page.locator("[data-placement-saved]")).toContainText("is on the calendar");
    expect(placementSaveRequest.parse(sent.saves[0])).toEqual({ metroId: fixture.metroId, jobId: fixture.job, acknowledged: false,
      spot: { technicianId: fixture.first, serviceDate: DATE, afterAppointmentId: fixture.north, beforeAppointmentId: fixture.south }, plannedStart: utc(17) });
  });

  test("a Place on calendar link opens on its answer: the request it starts as the page mounts is not cancelled", async ({ page }) => {
    // The dev server runs React's Strict Mode, which rehearses an unmount straight after mounting. The placement this
    // link starts is asked in a mount effect, so a cleanup that cancelled requests on that rehearsal left it loading.
    const sent = await mockPlacement(page, jobPreview(fixture), { status: 200, body: {} });
    await page.goto(`/work-orders/schedule?metroId=${fixture.metroId}&date=${DATE}&placeJob=${fixture.job}`);
    await expect(panel(page).getByRole("radio", { name: "11:30 AM" })).toBeChecked();
    await expect(panel(page).getByRole("list", { name: "Recommended times" }).getByRole("button")).toHaveCount(4);
    expect(sent.previews).toHaveLength(1);
  });

  test("clicking a visit asks once for every place it could go and lists the best ones, moving nothing until one is picked", async ({ page }) => {
    const sent = await mockPlacement(page, movePreview(fixture), { status: 200, body: { saved: true, appointmentId: fixture.south, kind: "KEEPS_PROMISE", undoId: "undo-1" } });
    await page.goto(`/work-orders/schedule?metroId=${fixture.metroId}&date=${DATE}`);
    await grid(page).locator(`[data-appointment-id="${fixture.south}"]`).click();
    await expect(page.getByRole("dialog")).toHaveCount(0);
    await expect(panel(page)).toContainText("Promised Mon, Oct 5, 1:00 PM to 3:00 PM");
    // Places that keep the promised window, one start each: the second route first, then where it is now.
    const options = panel(page).getByRole("list", { name: "Recommended times" }).getByRole("button");
    await expect(options).toHaveCount(2);
    await expect(options.nth(0)).toContainText("Place Second Tech, 1:00 PM");
    await expect(options.nth(0)).toContainText("+9 min");
    await expect(options.nth(1)).toContainText("Place First Tech, 1:00 PM");
    await expect(options.nth(1)).toContainText("where it is now");
    expect(placementPreviewRequest.parse(sent.previews[0])).toEqual({ metroId: fixture.metroId, monday: DATE, appointmentId: fixture.south,
      timedDates: [DATE], withRestOfWeek: true });
    await expect(grid(page).locator("[data-placement-ghost]")).toHaveCount(0);

    await options.nth(0).click();
    await expect(cell(page, fixture.second).locator("[data-placement-ghost]")).toHaveAttribute("data-placement-ghost", "KEEPS_PROMISE");
    // A start that is not among the options is listed above them as the pick.
    await options.nth(1).click();
    await panel(page).getByRole("radio", { name: "1:30 PM" }).check();
    await expect(options).toHaveCount(3);
    await expect(options.nth(0)).toContainText("Your pick");
    await expect(options.nth(0)).toHaveAttribute("aria-pressed", "true");
    // The visit's details are a click away.
    await panel(page).getByRole("button", { name: "Details" }).click();
    await expect(page.getByRole("dialog")).toContainText("Appointment details");
    expect(sent.previews).toHaveLength(1);
  });

  test("the preview is asked as a visit is pressed, and the click it becomes takes that answer over", async ({ page }) => {
    let release = () => {};
    const held = new Promise<void>(resolve => { release = resolve; });
    const previews: unknown[] = [];
    await page.route("**/api/schedule/placement", async route => {
      previews.push(route.request().postDataJSON());
      await held;
      await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(movePreview(fixture)) });
    });
    await page.goto(`/work-orders/schedule?metroId=${fixture.metroId}&date=${DATE}`);
    const box = await grid(page).locator(`[data-appointment-id="${fixture.south}"]`).boundingBox();
    if (box === null) throw new Error("The visit is not on screen");
    await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2);
    await page.mouse.down();
    // Asked before the button comes up, and nothing is shown for a press alone.
    await expect.poll(() => previews.length).toBe(1);
    await expect(panel(page)).not.toContainText("Moving");
    await page.mouse.up();
    await expect(panel(page)).toContainText("Moving");
    await expect(panel(page)).toContainText("Finding the best times this week");
    release();
    await expect(panel(page).getByRole("list", { name: "Recommended times" }).getByRole("button")).toHaveCount(2);
    expect(previews).toHaveLength(1);
  });

  test("paging to another day while placing asks for that day's timed stops alone, once, and joins them in", async ({ page }) => {
    const NEXT = "2026-10-06";
    const previews: Array<Record<string, unknown>> = [];
    const week = jobPreview(fixture);
    // The day paged to: the second route is empty and takes the job at 9 AM.
    const nextDay = placementPreview.parse({ ...week, answer: { routingIdentity: "omaha", skippedTechnicianDays: [], technicianDays: [
      { technicianId: fixture.second, serviceDate: NEXT, holdsAsGiven: true, qualified: true, asGiven: totals(0, 0), stops: [], gaps: [
        gap(undefined, undefined, 30, [{ ...start(14, 0, totals(30, 9000), 14), plannedStart: `${NEXT}T14:00:00.000Z`,
          window: { start: `${NEXT}T14:00:00.000Z`, end: `${NEXT}T16:00:00.000Z` } }], false)] }] } });
    await page.route("**/api/schedule/placement", async route => {
      const body = placementPreviewRequest.parse(route.request().postDataJSON());
      previews.push(body);
      await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(body.withRestOfWeek ? week : nextDay) });
    });
    await page.goto(`/work-orders/schedule?metroId=${fixture.metroId}&date=${DATE}`);
    await panel(page).locator(`[data-job-id="${fixture.job}"]`).click();
    await expect(marker(page, fixture.first, fixture.north, fixture.south)).toBeVisible();
    expect(previews).toEqual([expect.objectContaining({ timedDates: [DATE], withRestOfWeek: true })]);

    await page.getByRole("button", { name: "Next day" }).click();
    const nextMarker = grid(page).locator(`[data-gap-key="${fixture.second}|${NEXT}|start|end"]`);
    await expect(nextMarker).toBeVisible();
    expect(previews).toHaveLength(2);
    expect(previews[1]).toMatchObject({ monday: DATE, jobId: fixture.job, timedDates: [NEXT], withRestOfWeek: false });
    // The rest of the week is kept: back on the first day, its places are still there and nothing is asked.
    await page.getByRole("button", { name: "Previous day" }).click();
    await expect(marker(page, fixture.first, fixture.north, fixture.south)).toBeVisible();
    await page.getByRole("button", { name: "Next day" }).click();
    await expect(nextMarker).toBeVisible();
    expect(previews).toHaveLength(2);
  });

  test("dragging a visit to another technician inside its window compares it with where it was, and saves without acknowledgement", async ({ page }) => {
    const sent = await mockPlacement(page, movePreview(fixture), { status: 200, body: { saved: true, appointmentId: fixture.south, kind: "KEEPS_PROMISE", undoId: "undo-1" } });
    await page.goto(`/work-orders/schedule?metroId=${fixture.metroId}&date=${DATE}`);
    await dragTo(page, grid(page).locator(`[data-appointment-id="${fixture.south}"]`), fixture.second, 13 * 60 + 5);
    await expect(panel(page).locator("[data-placement-state]")).toHaveAttribute("data-placement-state", "KEEPS_PROMISE");
    await expect(panel(page).locator("[data-placement-comparison]")).toHaveAttribute("data-placement-comparison", "NEW");
    await expect(panel(page)).toContainText("The new assignment is better: with the move, the routes drive 5 minutes less");
    await expect(panel(page).getByRole("alert")).toHaveCount(0);
    expect(placementPreviewRequest.parse(sent.previews[0])).toEqual({ metroId: fixture.metroId, monday: DATE, appointmentId: fixture.south,
      timedDates: [DATE], withRestOfWeek: true });
    await panel(page).getByRole("button", { name: "Save" }).click();
    await expect(page.locator("[data-placement-saved]")).toContainText("moved");
    expect(placementSaveRequest.parse(sent.saves[0])).toMatchObject({ appointmentId: fixture.south, acknowledged: false, plannedStart: utc(18) });
  });

  test("while dragging, the place a drop would land is drawn under the pointer, worked out without asking again", async ({ page }) => {
    const sent = await mockPlacement(page, movePreview(fixture), { status: 200, body: { saved: true, appointmentId: fixture.south, kind: "KEEPS_PROMISE", undoId: "undo-1" } });
    await page.goto(`/work-orders/schedule?metroId=${fixture.metroId}&date=${DATE}`);
    const pointAt = async (technicianId: string, localMinute: number) => {
      const target = await cell(page, technicianId).boundingBox();
      if (target === null) throw new Error("The timeline is not on screen");
      await page.mouse.move(target.x + target.width / 2, target.y + ((localMinute - LOCAL_START_MINUTE) / 60) * HOUR_PX, { steps: 6 });
    };
    const source = await grid(page).locator(`[data-appointment-id="${fixture.south}"]`).boundingBox();
    if (source === null) throw new Error("The card is not on screen");
    await page.mouse.move(source.x + source.width / 2, source.y + source.height / 2);
    await page.mouse.down();

    // Inside the promised window on the second route: the nearest start, and the drive against the recommendation.
    await pointAt(fixture.second, 13 * 60 + 5);
    const hover = cell(page, fixture.second).locator("[data-hover-ghost]");
    await expect(hover).toHaveAttribute("data-hover-ghost", "KEEPS_PROMISE");
    await expect(hover).toContainText("1:00 PM: Best, +9 min drive");
    // Later the same route only offers a start outside the window, which is said on the preview.
    await pointAt(fixture.second, 15 * 60 + 30);
    await expect(hover).toHaveAttribute("data-hover-ghost", "CHANGES_PROMISE");
    await expect(hover).toContainText("3:30 PM: Best, +9 min drive, changes the promised window");
    // Before North on the first route nothing fits: the preview turns into the problem it would be.
    await pointAt(fixture.first, 9 * 60);
    await expect(cell(page, fixture.first).locator("[data-hover-ghost]")).toHaveAttribute("data-hover-ghost", "CANNOT_HOLD");
    await expect(cell(page, fixture.first).locator("[data-hover-ghost]")).toContainText("No start fits here");
    await expect(hover).toHaveCount(0);
    await expect(grid(page).locator("[data-placement-ghost]")).toHaveCount(0);
    expect(sent.previews).toHaveLength(1);

    // Dropping where the preview was keeps it as the chosen place.
    await pointAt(fixture.second, 13 * 60 + 5);
    await page.mouse.up();
    await expect(grid(page).locator("[data-hover-ghost]")).toHaveCount(0);
    await expect(cell(page, fixture.second).locator('[data-placement-ghost="KEEPS_PROMISE"]')).toBeVisible();
    await expect(panel(page).getByRole("radio", { name: "1:00 PM" })).toBeChecked();
    expect(sent.previews).toHaveLength(1);
  });

  test("a moved visit can be picked up again and again until Cancel, its legs and its neighbors' taken from the one answer", async ({ page }) => {
    const sent = await mockPlacement(page, movePreview(fixture), { status: 200, body: { saved: true, appointmentId: fixture.south, kind: "KEEPS_PROMISE", undoId: "undo-1" } });
    const leg = (minutes: number) => ({ seconds: minutes * 50, meters: minutes * 900, minutes });
    await page.route(url => url.pathname === "/api/schedule/drive", route => route.fulfill({ status: 200, contentType: "application/json",
      body: JSON.stringify({ serviceDate: DATE, skipped: [], technicians: [
        { technicianId: fixture.first, departure: "DEPOT", returnTo: "HOME", segments: [{ appointmentIds: [fixture.north, fixture.south], legs: [leg(17), leg(12), leg(15)] }] },
        { technicianId: fixture.second, departure: "DEPOT", returnTo: "DEPOT", segments: [] }] }) }));
    await page.goto(`/work-orders/schedule?metroId=${fixture.metroId}&date=${DATE}`);
    const northAfter = cell(page, fixture.first).locator(`[data-appointment-id="${fixture.north}"] [data-drive="after"]`);
    await expect(northAfter).toHaveAttribute("title", "12 min to next stop");

    // First move, to the second route's empty day: it drives from that route's depot and back to it.
    await dragTo(page, grid(page).locator(`[data-appointment-id="${fixture.south}"]`), fixture.second, 13 * 60 + 5);
    const onSecond = cell(page, fixture.second).locator("[data-placement-ghost]");
    await expect(onSecond).toHaveAttribute("data-placement-ghost", "KEEPS_PROMISE");
    await expect(onSecond.locator('[data-drive="before"]')).toHaveText("9 min from depot");
    await expect(onSecond.locator('[data-drive="after"]')).toHaveText("8 min to depot");
    // South has left the first route: North now drives home, and South's old place is drawn without its legs.
    await expect(northAfter).toHaveAttribute("title", "8 min to home");
    await expect(cell(page, fixture.first).locator(`[data-appointment-id="${fixture.south}"] [data-drive]`)).toHaveCount(0);
    // The first route's header says what the move does to its driving: 17 + 12 + 15 becomes 17 + 8.
    await expect(grid(page).locator(`[data-technician-column="${fixture.first}"] [data-technician-drive-change]`)).toHaveText("-19 min with this place");

    // Second move: the preview itself is picked up and dropped back after North at 1:30 PM.
    await dragTo(page, onSecond, fixture.first, 13 * 60 + 30);
    const onFirst = cell(page, fixture.first).locator("[data-placement-ghost]");
    await expect(onFirst).toHaveAttribute("data-placement-ghost", "KEEPS_PROMISE");
    await expect(panel(page).getByRole("radio", { name: "1:30 PM" })).toBeChecked();
    await expect(northAfter).toHaveAttribute("title", "14 min to next stop");
    await expect(onFirst.locator('[data-drive="before"]')).toHaveCount(0);
    await expect(onFirst.locator('[data-drive="after"]')).toHaveText("8 min to home");
    await expect(cell(page, fixture.second).locator("[data-placement-ghost]")).toHaveCount(0);

    // Third move, outside the promised window: the violation comes back, and still nothing is asked again.
    await dragTo(page, onFirst, fixture.second, 15 * 60 + 30);
    await expect(cell(page, fixture.second).locator("[data-placement-ghost]")).toHaveAttribute("data-placement-ghost", "CHANGES_PROMISE");
    await expect(panel(page).getByRole("alert").filter({ hasText: "Dealership policy violation" })).toBeVisible();
    expect(sent.previews).toHaveLength(1);

    // Cancel puts every leg back as it is booked and saves nothing.
    await panel(page).getByRole("button", { name: "Cancel" }).click();
    await expect(grid(page).locator("[data-placement-ghost]")).toHaveCount(0);
    await expect(northAfter).toHaveAttribute("title", "12 min to next stop");
    await expect(cell(page, fixture.first).locator(`[data-appointment-id="${fixture.south}"] [data-drive="after"]`)).toHaveAttribute("title", "15 min to home");
    expect(sent.saves).toHaveLength(0);
  });

  test("the preview moves every later visit to the time the scheduler gave it, from the one answer, and Cancel puts them back", async ({ page }) => {
    // The job's answer with timed stops: at 11:30 AM South keeps 1:15 PM; at 12:00 PM it is pushed to 1:30 PM.
    const stop = (appointmentId: string, sequence: number, from: [number, number], to: [number, number]) =>
      ({ appointmentId, sequence, plannedStart: utc(...from), plannedEnd: utc(...to) });
    const timed = placementPreview.parse({ original: null, job: { id: fixture.job, durationMinutes: 60 }, answer: { routingIdentity: "omaha", skippedTechnicianDays: [], technicianDays: [
      { technicianId: fixture.first, serviceDate: DATE, holdsAsGiven: true, qualified: true, asGiven: totals(60, 15000), gaps: [
        { ...gap(fixture.north, fixture.south, 6, []), starts: [
          { ...start(16, 30, totals(66, 15900), 16), stops: [stop(fixture.north, 0, [15, 15], [16, 0]), stop(fixture.job, 1, [16, 30], [17, 30]), stop(fixture.south, 2, [18, 15], [19, 0])] },
          { ...start(17, 0, totals(66, 16000), 17), stops: [stop(fixture.north, 0, [15, 15], [16, 0]), stop(fixture.job, 1, [17, 0], [18, 0]), stop(fixture.south, 2, [18, 30], [19, 15])] }] }] },
    ] } });
    const sent = await mockPlacement(page, timed, { status: 200, body: { saved: true, appointmentId: fixture.job, kind: "NEW", undoId: "undo-1" } });
    await page.goto(`/work-orders/schedule?metroId=${fixture.metroId}&date=${DATE}`);
    const south = cell(page, fixture.first).locator(`[data-appointment-id="${fixture.south}"]`);
    await expect(south).toContainText("1:15 PM - 2:00 PM");

    await panel(page).locator(`[data-job-id="${fixture.job}"]`).click();
    await marker(page, fixture.first, fixture.north, fixture.south).click();
    await expect(panel(page).getByRole("radio", { name: "11:30 AM" })).toBeChecked();
    await expect(south).not.toHaveAttribute("data-retimed");
    await expect(south).toContainText("1:15 PM - 2:00 PM");

    await panel(page).getByRole("radio", { name: "12:00 PM" }).check();
    await expect(south).toHaveAttribute("data-retimed", "true");
    await expect(south).toContainText("1:30 PM - 2:15 PM");
    await expect(cell(page, fixture.first).locator(`[data-appointment-id="${fixture.north}"]`)).not.toHaveAttribute("data-retimed");
    expect(sent.previews).toHaveLength(1);

    await panel(page).getByRole("button", { name: "Cancel" }).click();
    await expect(south).not.toHaveAttribute("data-retimed");
    await expect(south).toContainText("1:15 PM - 2:00 PM");
    expect(sent.saves).toHaveLength(0);
  });

  test("a move to another arrival window is a policy violation that saves only once acknowledged", async ({ page }) => {
    const sent = await mockPlacement(page, movePreview(fixture), { status: 200, body: { saved: true, appointmentId: fixture.south, kind: "CHANGES_PROMISE", undoId: "undo-1" } });
    await page.goto(`/work-orders/schedule?metroId=${fixture.metroId}&date=${DATE}`);
    await dragTo(page, grid(page).locator(`[data-appointment-id="${fixture.south}"]`), fixture.second, 15 * 60 + 30);
    const violation = panel(page).getByRole("alert").filter({ hasText: "Dealership policy violation" });
    await expect(violation).toContainText("from Mon, Oct 5, 1:00 PM to 3:00 PM to Mon, Oct 5, 3:00 PM to 5:00 PM");
    await expect(grid(page).locator('[data-placement-ghost="CHANGES_PROMISE"]')).toBeVisible();
    const save = panel(page).getByRole("button", { name: "Save" });
    await expect(save).toBeDisabled();
    await violation.getByRole("checkbox").check();
    await save.click();
    await expect(page.locator("[data-placement-saved]")).toContainText("changed promise was recorded");
    expect(placementSaveRequest.parse(sent.saves[0])).toMatchObject({ appointmentId: fixture.south, acknowledged: true, plannedStart: utc(20, 30) });
  });

  test("a save the schedule overtook is explained, and the visit stays where it was", async ({ page }) => {
    await mockPlacement(page, movePreview(fixture), { status: 409, body: { error: "That place no longer fits. The schedule changed since the preview; preview it again." } });
    await page.goto(`/work-orders/schedule?metroId=${fixture.metroId}&date=${DATE}`);
    await dragTo(page, grid(page).locator(`[data-appointment-id="${fixture.south}"]`), fixture.second, 13 * 60 + 5);
    await panel(page).getByRole("button", { name: "Save" }).click();
    await expect(panel(page).getByRole("alert")).toContainText("That place no longer fits.");
    await expect(cell(page, fixture.first).locator(`[data-appointment-id="${fixture.south}"]`)).toBeVisible();
  });

  test("a new job from the form is placed like a waiting one", async ({ page }) => {
    const created: unknown[] = [];
    await page.route("**/api/schedule/placement/job", async route => {
      created.push(route.request().postDataJSON());
      await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ jobId: fixture.job }) });
    });
    const sent = await mockPlacement(page, jobPreview(fixture), { status: 200, body: { saved: true, appointmentId: fixture.job, kind: "NEW", undoId: "undo-1" } });
    await page.goto(`/work-orders/schedule?metroId=${fixture.metroId}&date=${DATE}`);
    await panel(page).getByRole("button", { name: "New job" }).click();
    const form = panel(page).getByRole("form", { name: "New job" });
    for (const [label, value] of [["First name", "Fresh"], ["Last name", "Customer"], ["Email", "fresh@example.invalid"], ["Phone", "4025550111"],
      ["Street", "2 Elm St"], ["City", "Omaha"], ["State", "NE"], ["ZIP", "68102"]] as const) await form.getByLabel(label).fill(value);
    await form.getByLabel("Reason code").selectOption({ label: "Softener install" });
    await form.getByRole("button", { name: "Create and place" }).click();
    await expect(panel(page).getByRole("heading", { name: "Fresh Customer, Softener install" })).toBeVisible();
    await expect(marker(page, fixture.first, fixture.north, fixture.south)).toHaveText("Best, +6 min drive");
    expect(created).toHaveLength(1);
    expect(created[0]).toMatchObject({ firstName: "Fresh", serviceCode: "UI_PLACE_TEST", line1: "2 Elm St" });
    expect(sent.previews).toEqual([{ metroId: fixture.metroId, monday: DATE, jobId: fixture.job, timedDates: [DATE], withRestOfWeek: true }]);
  });

  test("each visit is one block with its drive: from the depot before the first, to the next stop, and home after the last", async ({ page }) => {
    const leg = (minutes: number) => ({ seconds: minutes * 50, meters: minutes * 900, minutes });
    const asked: string[] = [];
    await page.route(url => url.pathname === "/api/schedule/drive", async route => {
      asked.push(route.request().url());
      await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ serviceDate: DATE, skipped: [], technicians: [
        { technicianId: fixture.first, departure: "DEPOT", returnTo: "HOME", segments: [{ appointmentIds: [fixture.north, fixture.south], legs: [leg(17), leg(12), leg(15)] }] }] }) });
    });
    await page.goto(`/work-orders/schedule?metroId=${fixture.metroId}&date=${DATE}`);
    const north = cell(page, fixture.first).locator(`[data-appointment-id="${fixture.north}"]`);
    const south = cell(page, fixture.first).locator(`[data-appointment-id="${fixture.south}"]`);
    await expect(north.locator('[data-drive="before"]')).toHaveAttribute("title", "17 min from depot");
    await expect(north.locator('[data-drive="after"]')).toHaveAttribute("title", "12 min to next stop");
    await expect(south.locator('[data-drive="before"]')).toHaveCount(0);
    await expect(south.locator('[data-drive="after"]')).toHaveAttribute("title", "15 min to home");
    await expect(grid(page).locator(`[data-technician-drive="44"]`)).toHaveText("44 min driving");
    expect(new URL(asked[0] ?? "http://invalid").searchParams.get("date")).toBe(DATE);

    // North starts at 10:15 AM; its block starts 17 minutes earlier, at 9:58 AM, on the timeline that starts at 8 AM.
    const box = await north.boundingBox(), timeline = await cell(page, fixture.first).boundingBox();
    if (box === null || timeline === null) throw new Error("The block or the timeline is not on screen");
    expect(Math.abs(box.y - timeline.y - (118 / 60) * HOUR_PX)).toBeLessThan(2);
    expect(Math.abs(box.height - ((17 + 45 + 12) / 60) * HOUR_PX)).toBeLessThan(2);
  });

  test("drive times that cannot be loaded are said, and visits are drawn without them", async ({ page }) => {
    await page.route(url => url.pathname === "/api/schedule/drive", route =>
      route.fulfill({ status: 503, contentType: "application/json", body: JSON.stringify({ error: "Scheduling is not connected for this client." }) }));
    await page.goto(`/work-orders/schedule?metroId=${fixture.metroId}&date=${DATE}`);
    await expect(page.locator("[data-drive-unavailable]")).toContainText("Drive times are unavailable: Scheduling is not connected for this client.");
    await expect(grid(page).locator(`[data-appointment-id="${fixture.north}"]`)).toBeVisible();
    await expect(grid(page).locator("[data-drive]")).toHaveCount(0);
    await expect(grid(page).locator("[data-technician-drive]")).toHaveCount(0);
  });
});

/** The technician-days' lastModified as the public API reads it, so a test can make the undo record a save would. */
async function stamps(days: Array<{ technicianId: string; serviceDate: string }>) {
  const rows = await prisma.$queryRaw<Array<{ technicianId: string; lastModified: string }>>`
    SELECT t.id AS "technicianId",
      to_char(GREATEST(t."factsChangedAt", c."changedAt") AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"') AS "lastModified"
    FROM technician t LEFT JOIN technician_day_change c ON c."technicianId" = t.id AND c."serviceDate" = ${new Date(`${DATE}T00:00:00Z`)}
    WHERE t.id IN (${days[0]?.technicianId}, ${days[1]?.technicianId})`;
  return days.map(day => {
    const row = rows.find(item => item.technicianId === day.technicianId);
    if (row === undefined) throw new Error(`No stamp for ${day.technicianId}`);
    return { ...day, lastModified: row.lastModified };
  });
}

/** Tells the page its tab was returned to, which checks the schedule's version at once rather than in 20 seconds. */
const returnToTab = (page: Page) => page.evaluate(() => window.dispatchEvent(new Event("focus")));

/**
 * Returns to the tab until `seen` passes. The server-rendered page is on screen before its scripts listen for focus,
 * so a single event can come too early and be lost; the page itself would find the change at its next 20-second check.
 */
async function returnUntil(page: Page, seen: () => Promise<void>) {
  await expect(async () => { await returnToTab(page); await seen(); }).toPass({ timeout: 15_000, intervals: [250, 500, 1000] });
}

test.describe("following the schedule and undoing a save", () => {
  let fixture: Fixture;
  const undoIds: string[] = [];
  test.beforeEach(async ({ page }) => {
    fixture = await seed();
    await page.setViewportSize({ width: 1600, height: 1400 });
  });
  test.afterEach(async () => {
    await prisma.placementUndo.deleteMany({ where: { id: { in: undoIds.splice(0) } } });
    await fixture.cleanup();
  });

  /**
   * Answers the preview with the move answer and the save by doing what the portal's save does to the database: South
   * moves to the second technician and the undo record is written, with the days' stamps as the move left them.
   */
  async function saveMovesSouth(page: Page) {
    await page.route("**/api/schedule/placement", route => route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(movePreview(fixture)) }));
    await page.route("**/api/schedule/placement/save", async route => {
      const before = await prisma.appointment.findMany({ where: { id: { in: [fixture.north, fixture.south] } } });
      await prisma.appointment.update({ where: { id: fixture.south }, data: { technicianId: fixture.second, sequence: 0 } });
      const days = await stamps([{ technicianId: fixture.first, serviceDate: DATE }, { technicianId: fixture.second, serviceDate: DATE }]);
      const record = await prisma.placementUndo.create({ data: { clientId: DEFAULT_CLIENT_ID, payload: { kind: "KEEPS_PROMISE", technicianDays: days,
        assignments: before.map(item => ({ appointmentId: item.id, technicianId: item.technicianId, serviceDate: DATE, sequence: item.sequence,
          plannedStart: item.plannedStart.toISOString(), plannedEnd: item.plannedEnd.toISOString() })) } } });
      undoIds.push(record.id);
      await route.fulfill({ status: 200, contentType: "application/json",
        body: JSON.stringify({ saved: true, appointmentId: fixture.south, kind: "KEEPS_PROMISE", undoId: record.id }) });
    });
    await page.goto(`/work-orders/schedule?metroId=${fixture.metroId}&date=${DATE}`);
    await dragTo(page, grid(page).locator(`[data-appointment-id="${fixture.south}"]`), fixture.second, 13 * 60 + 5);
    await panel(page).getByRole("button", { name: "Save" }).click();
    await expect(page.locator("[data-placement-saved]")).toContainText("moved");
    await expect(cell(page, fixture.second).locator(`[data-appointment-id="${fixture.south}"]`)).toBeVisible();
  }

  test("Undo puts a saved move back where it was, once", async ({ page }) => {
    await saveMovesSouth(page);
    const banner = page.locator("[data-placement-saved]");
    await banner.getByRole("button", { name: "Undo" }).click();
    await expect(banner).toContainText("is back where it was");
    await expect(banner.getByRole("button", { name: "Undo" })).toHaveCount(0);
    await expect(cell(page, fixture.first).locator(`[data-appointment-id="${fixture.south}"]`)).toBeVisible();
    const south = await prisma.appointment.findUniqueOrThrow({ where: { id: fixture.south } });
    expect({ technicianId: south.technicianId, sequence: south.sequence }).toEqual({ technicianId: fixture.first, sequence: 1 });
    expect((await prisma.placementUndo.findUniqueOrThrow({ where: { id: undoIds[0] } })).usedAt).not.toBeNull();
    // Our own save and undo are not announced as someone else's change.
    await returnToTab(page);
    await expect(page.locator("[data-schedule-updated]")).toHaveCount(0);
  });

  test("Undo is refused once anything on those days changed since the save, and nothing is overwritten", async ({ page }) => {
    await saveMovesSouth(page);
    // Another dispatcher shifts North on the first route after the save.
    await prisma.appointment.update({ where: { id: fixture.north }, data: { plannedStart: new Date(utc(15, 30)) } });
    await page.locator("[data-placement-saved]").getByRole("button", { name: "Undo" }).click();
    await expect(page.locator("[data-placement-saved]").getByRole("alert")).toHaveText("The schedule changed since this was saved. Move it back by hand.");
    expect((await prisma.appointment.findUniqueOrThrow({ where: { id: fixture.south } })).technicianId).toBe(fixture.second);
    expect((await prisma.placementUndo.findUniqueOrThrow({ where: { id: undoIds[0] } })).usedAt).toBeNull();
  });

  test("a change made elsewhere appears without a reload, and says so", async ({ page }) => {
    await page.goto(`/work-orders/schedule?metroId=${fixture.metroId}&date=${DATE}`);
    await expect(cell(page, fixture.first).locator(`[data-appointment-id="${fixture.south}"]`)).toBeVisible();
    await prisma.appointment.update({ where: { id: fixture.south }, data: { technicianId: fixture.second, sequence: 0 } });
    await returnUntil(page, () => expect(cell(page, fixture.second).locator(`[data-appointment-id="${fixture.south}"]`)).toBeVisible({ timeout: 1000 }));
    await expect(page.locator("[data-schedule-updated]")).toHaveText("The schedule was updated.");
  });

  test("an open placement asks for its places again after a change elsewhere, and a chosen place that is gone is cleared", async ({ page }) => {
    const previews: unknown[] = [];
    const week = jobPreview(fixture);
    // After the change, nothing fits between North and South any more.
    const changed = placementPreview.parse({ ...week, answer: { ...week.answer, technicianDays: week.answer.technicianDays.map(day =>
      ({ ...day, gaps: day.gaps.map(item => item.afterAppointmentId === fixture.north && item.beforeAppointmentId === fixture.south ? { ...item, starts: [] } : item) })) } });
    await page.route("**/api/schedule/placement", async route => {
      previews.push(route.request().postDataJSON());
      await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(previews.length === 1 ? week : changed) });
    });
    await page.goto(`/work-orders/schedule?metroId=${fixture.metroId}&date=${DATE}`);
    await panel(page).locator(`[data-job-id="${fixture.job}"]`).click();
    await expect(panel(page).getByRole("radio", { name: "11:30 AM" })).toBeChecked();

    await prisma.appointment.update({ where: { id: fixture.north }, data: { plannedEnd: new Date(utc(16, 30)) } });
    await returnUntil(page, () => expect(panel(page).locator("[data-placement-notice]"))
      .toHaveText("The schedule changed and that place is gone. Pick again.", { timeout: 1000 }));
    // Asked again once per change seen; a repeated focus before the reload lands may ask once more, never less.
    expect(previews.length).toBeGreaterThanOrEqual(2);
    expect(previews.at(-1)).toMatchObject({ jobId: fixture.job, timedDates: [DATE], withRestOfWeek: true });
    await expect(panel(page).getByRole("radio", { name: "11:30 AM" })).toHaveCount(0);
    await expect(panel(page).getByRole("button", { name: "Save" })).toHaveCount(0);
  });
});
