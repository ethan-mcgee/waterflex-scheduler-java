import { test, expect, type Page } from "@playwright/test";
import { PrismaClient } from "@prisma/client";
import { initialAvailability } from "../lib/technicianAvailability";
import { DEFAULT_CLIENT_ID } from "../lib/clients";
import { cancelRequestBody, previewRequest } from "../lib/contracts";

const prisma = new PrismaClient();
test.afterAll(async () => { await prisma.$disconnect(); });

const DATE = "2026-10-05";
const at = (hour: number, minute = 0) => `${DATE}T${String(hour).padStart(2, "0")}:${String(minute).padStart(2, "0")}:00.000Z`;

type Fixture = { metroId: string; first: { id: string }; second: { id: string }; north: string; south: string; cleanup: () => Promise<void> };

/** A metro day with two technicians: the first has two visits, the second none, and the depot sends routes out from the depot and home at night. */
async function seed(): Promise<Fixture> {
  const metro = await prisma.metro.create({ data: { name: "Board browser metro", timezone: "America/Chicago" } });
  const dealership = await prisma.dealership.create({ data: { clientId: DEFAULT_CLIENT_ID, name: "Board browser dealership" } });
  const depot = await prisma.depot.create({ data: { metroId: metro.id, dealershipId: dealership.id, name: "Board browser depot", lat: 41.25, lng: -95.93,
    endpointPolicies: { create: { effectiveDate: new Date("1900-01-01T00:00:00Z"), departure: "DEPOT", returnTo: "HOME" } } } });
  const technician = (name: string, color: string, homeLat: number) => prisma.technician.create({ data: { clientId: DEFAULT_CLIENT_ID, name, color,
    homeLat, homeLng: -95.95, shiftStartMin: 480, shiftEndMin: 1020, availabilityVersions: initialAvailability(480, 1020),
    depotAssignments: { create: { depotId: depot.id, effectiveDate: new Date("1900-01-01T00:00:00Z") } } } });
  const first = await technician("Board First Tech", "#dc2626", 41.3);
  const second = await technician("Board Second Tech", "#059669", 41.2);
  const service = await prisma.serviceCatalog.create({ data: { code: "UI_BOARD_TEST", name: "Softener service", estDurationMin: 45 } });
  const visit = async (lastName: string, lat: number, sequence: number, hour: number, pinned: boolean) => {
    const customer = await prisma.customer.create({ data: { clientId: DEFAULT_CLIENT_ID, firstName: "Board", lastName, email: `${lastName.toLowerCase()}@example.invalid`, phone: "4025550100" } });
    const address = await prisma.address.create({ data: { customerId: customer.id, line1: `${sequence + 1} Main St`, city: "Omaha", state: "NE", postalCode: "68102", lat, lng: -95.9,
      geocodePrecision: "ROOFTOP", pinConfirmedAt: pinned ? new Date("2026-09-01T00:00:00Z") : null } });
    const job = await prisma.job.create({ data: { customerId: customer.id, addressId: address.id, serviceId: service.id, durationMin: 45, status: "SCHEDULED" } });
    const appointment = await prisma.appointment.create({ data: { jobId: job.id, technicianId: first.id, serviceDate: new Date(`${DATE}T00:00:00Z`),
      windowStart: new Date(at(hour)), windowEnd: new Date(at(hour + 2)), plannedStart: new Date(at(hour, 15)), plannedEnd: new Date(at(hour + 1)), sequence } });
    return { customer, address, job, appointment };
  };
  const north = await visit("North", 41.28, 0, 15, true);
  const south = await visit("South", 41.22, 1, 17, false);
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

const leg = (technicianId: string, legIndex: number, from: [number, number], to: [number, number]) => ({ type: "Feature",
  properties: { technicianId, interval: `${technicianId}:0`, legIndex, seconds: 1200, meters: 10729 }, geometry: { type: "LineString", coordinates: [from, to] } });

function geometry(fixture: Fixture, phase: "current" | "proposal", costCents: number | null) {
  const depot: [number, number] = [-95.93, 41.25], firstHome: [number, number] = [-95.95, 41.3], secondHome: [number, number] = [-95.95, 41.2];
  const north: [number, number] = [-95.9, 41.28], south: [number, number] = [-95.9, 41.22];
  const { first, second } = fixture;
  const moved = phase === "proposal";
  const stops = [{ id: fixture.north, technicianId: first.id, sequence: 0, plannedStart: at(15, 15), lat: north[1], lng: north[0] },
    { id: fixture.south, technicianId: moved ? second.id : first.id, sequence: moved ? 0 : 1, plannedStart: moved ? at(17, 5) : at(17, 15), lat: south[1], lng: south[0] }];
  const features = moved
    ? [leg(first.id, 0, depot, north), leg(first.id, 1, north, firstHome), leg(second.id, 0, depot, south), leg(second.id, 1, south, secondHome)]
    : [leg(first.id, 0, depot, north), leg(first.id, 1, north, south), leg(first.id, 2, south, firstHome)];
  const endpoint = (technicianId: string, home: [number, number]) => ({ technicianId, departure: "DEPOT", returnTo: "HOME",
    departureLat: depot[1], departureLng: depot[0], returnLat: home[1], returnLng: home[0] });
  const seconds = features.length * 1200, meters = features.length * 10729;
  return { type: "FeatureCollection", routingIdentity: "board-test", serviceDate: DATE, phase, features, stops,
    endpoints: [endpoint(first.id, firstHome), endpoint(second.id, secondHome)], skippedTechnicianDays: [],
    totals: costCents === null ? { seconds, meters, costCents: null, costUnavailableReason: "The current plan does not hold within its shifts" } : { seconds, meters, costCents } };
}

function proposal(fixture: Fixture, state: "OPEN" | "COMMITTED" = "OPEN") {
  return { proposalId: "board-proposal", serviceDate: DATE, createdAt: "2026-10-04T15:00:00Z", decision: "IMPROVED", reason: "Lower modeled cost", costCents: 150000,
    state, committedAt: state === "COMMITTED" ? "2026-10-04T15:05:00Z" : null, refusal: null,
    routes: [{ technicianId: fixture.first.id, serviceDate: DATE, stops: [{ appointmentId: fixture.north, sequence: 0, plannedStart: at(15, 15), plannedEnd: at(16) }] },
      { technicianId: fixture.second.id, serviceDate: DATE, stops: [{ appointmentId: fixture.south, sequence: 0, plannedStart: at(17, 5), plannedEnd: at(17, 50) }] }],
    unresolvedAppointmentIds: [], skippedTechnicianDays: [],
    changes: [{ appointmentId: fixture.south, fromTechnicianId: fixture.first.id, toTechnicianId: fixture.second.id, fromSequence: 1, toSequence: 0, fromStart: at(17, 15), toStart: at(17, 5) }],
    overnightRunId: null };
}

const json = (body: unknown, status = 200) => ({ status, contentType: "application/json", body: JSON.stringify(body) });

async function mockApis(page: Page, fixture: Fixture, options: { currentCost?: number | null; proposalGeometryStatus?: number } = {}) {
  const calls = { current: 0, commit: 0 };
  await page.route("http://localhost:8083/**", route => route.fulfill({ status: 404 }));
  await page.route("**/api/dispatch/geometry?**", route => { calls.current++; return route.fulfill(json(geometry(fixture, "current", options.currentCost === undefined ? 184600 : options.currentCost))); });
  await page.route(url => url.pathname === "/api/dispatch/proposals", route =>
    route.fulfill(json(route.request().method() === "GET" ? { proposals: [] } : proposal(fixture))));
  await page.route("**/api/dispatch/proposals/board-proposal/geometry", route => route.fulfill(options.proposalGeometryStatus === 409
    ? json({ error: "The schedule changed since this proposal was made. Preview the day again." }, 409) : json(geometry(fixture, "proposal", 150000))));
  await page.route("**/api/dispatch/proposals/commit", route => { calls.commit++; return route.fulfill(json(proposal(fixture, "COMMITTED"))); });
  return calls;
}

test("the board optimizes, compares and commits a metro day on the map", async ({ page }) => {
  const fixture = await seed();
  try {
    const calls = await mockApis(page, fixture);
    await page.goto(`/dispatch?metroId=${fixture.metroId}&date=${DATE}`);
    await expect(page.getByRole("heading", { name: "Ready to optimize" })).toBeVisible();
    await expect(page.getByText("$1,846", { exact: true })).toBeVisible();
    await expect(page.getByText("1h 00m", { exact: true })).toBeVisible();
    await expect(page.getByText("20 mi", { exact: true })).toBeVisible();
    await expect(page.getByRole("list", { name: "Map legend" })).toContainText("Technician home");
    await expect(page.getByRole("list", { name: "Map legend" })).toContainText("Depot");
    const markers = page.locator('[class*="stopMarker"]');
    await expect(markers).toHaveCount(2);
    await expect(markers.first()).toHaveCSS("border-top-color", "rgb(220, 38, 38)");
    await expect(page.getByRole("img", { name: "Depot" })).toHaveCount(1);
    await expect(page.getByRole("img", { name: "Board First Tech's home" })).toHaveCount(1);
    await expect(page.getByRole("img", { name: "Board Second Tech's home" })).toHaveCount(0);

    await page.getByRole("button", { name: "Optimize routes" }).click();
    await expect(page.getByRole("heading", { name: "Optimized plan ready to review" })).toBeVisible();
    await expect(page.getByText("$1,500", { exact: true })).toBeVisible();
    await expect(page.getByText("−$346", { exact: true })).toBeVisible();
    await expect(page.getByRole("button", { name: "Compare" })).toHaveAttribute("aria-pressed", "true");
    await expect(page.getByRole("heading", { name: "Current vs. optimized" })).toBeVisible();
    await expect(page.getByRole("img", { name: "Board Second Tech's home" })).toHaveCount(1);
    const row = page.getByRole("row").filter({ hasText: "Board South" });
    await expect(row).toContainText("Reassigned");
    await expect(row).toContainText("Board Second Tech");
    await expect(page.getByRole("button", { name: /Board South.*\(changed\)/ })).toHaveCSS("box-shadow", /rgb\(1, 87, 182\)/);

    await row.getByRole("button", { name: /Board Second Tech/ }).click();
    await expect(row.getByRole("button", { name: /Board Second Tech/ })).toHaveAttribute("aria-pressed", "true");
    await expect(page.getByRole("button", { name: /Board North/ })).toHaveClass(/markerDimmed/);
    await row.getByRole("button", { name: /Board Second Tech/ }).click();
    await expect(page.getByRole("button", { name: /Board North/ })).not.toHaveClass(/markerDimmed/);

    await page.getByRole("button", { name: "Optimized", exact: true }).click();
    await expect(page.getByRole("heading", { name: "Optimized plan", exact: true })).toBeVisible();
    await page.getByRole("button", { name: /Board South/ }).click();
    const detail = page.getByRole("complementary", { name: "Board South" });
    await expect(detail.getByRole("heading", { name: "Board South" })).toBeVisible();
    await expect(detail).toContainText("Stop 1 of 1 · Board Second Tech · Optimized plan");
    await expect(detail).toContainText("Rooftop match");
    await expect(detail).toContainText("Scheduled");
    await detail.getByRole("button", { name: "Close stop details" }).click();
    await expect(detail).toHaveCount(0);

    page.once("dialog", dialog => void dialog.accept());
    await page.getByRole("button", { name: "Commit optimized plan" }).click();
    await expect(page.getByRole("heading", { name: "The optimized plan is live" })).toBeVisible();
    await expect(page.getByRole("button", { name: "Commit optimized plan" })).toHaveCount(0);
    expect(calls.commit).toBe(1);
    await expect.poll(() => calls.current).toBe(2);
    await page.getByRole("button", { name: "Done" }).click();
    await expect(page.getByRole("heading", { name: "Ready to optimize" })).toBeVisible();
  } finally { await fixture.cleanup(); }
});

test("a day that changed after the proposal cannot be committed, and an unpriced day is never shown as free", async ({ page }) => {
  const fixture = await seed();
  try {
    await mockApis(page, fixture, { currentCost: null, proposalGeometryStatus: 409 });
    await page.goto(`/dispatch?metroId=${fixture.metroId}&date=${DATE}`);
    const costTile = page.locator('[class*="_tile_"]').filter({ hasText: "Estimated cost" });
    await expect(costTile).toContainText("Unavailable");
    await expect(costTile).not.toContainText("$0");
    await page.getByRole("button", { name: "Optimize routes" }).click();
    await expect(page.getByRole("alert").filter({ hasText: "The schedule changed since this proposal was made." })).toBeVisible();
    await expect(page.getByRole("button", { name: "Commit optimized plan" })).toBeDisabled();
    await expect(page.getByRole("group", { name: "Plan shown on map" })).toHaveCount(0);
    await page.getByRole("button", { name: "Discard" }).click();
    await expect(page.getByRole("heading", { name: "Ready to optimize" })).toBeVisible();
  } finally { await fixture.cleanup(); }
});

test("a running optimization can be cancelled and the board returns to ready", async ({ page }) => {
  const fixture = await seed();
  try {
    await mockApis(page, fixture);
    let optimizeRequestId: string | null = null;
    let cancelledId: string | null = null;
    let release: () => void = () => { };
    const cancelled = new Promise<void>(resolve => { release = resolve; });
    // The optimize call stays open until the board cancels it, then answers as the portal does for a cancelled scheduler call.
    await page.route(url => url.pathname === "/api/dispatch/proposals", async route => {
      if (route.request().method() === "GET") return route.fulfill(json({ proposals: [] }));
      optimizeRequestId = previewRequest.parse(route.request().postDataJSON()).requestId;
      await cancelled;
      return route.fulfill(json({ error: "Optimization cancelled. Nothing was changed." }, 409));
    });
    await page.route("**/api/dispatch/proposals/cancel", route => {
      cancelledId = cancelRequestBody.parse(route.request().postDataJSON()).requestId;
      release();
      return route.fulfill(json({ outcome: "CANCELLING" }));
    });
    await page.goto(`/dispatch?metroId=${fixture.metroId}&date=${DATE}`);
    await page.getByRole("button", { name: "Optimize routes" }).click();
    await expect(page.getByRole("heading", { name: "Optimizing routes" })).toBeVisible();
    await page.getByRole("button", { name: "Cancel", exact: true }).click();
    await expect(page.getByRole("heading", { name: "Ready to optimize" })).toBeVisible();
    await expect(page.getByText("Optimization cancelled. Nothing was changed.")).toBeVisible();
    expect(optimizeRequestId).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/);
    expect(cancelledId).toBe(optimizeRequestId);
  } finally { await fixture.cleanup(); }
});
