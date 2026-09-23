import { test, expect } from "@playwright/test";
import { PrismaClient } from "@prisma/client";
import { z } from "zod";
import { initialAvailability } from "../lib/technicianAvailability";

const prisma = new PrismaClient();
test.afterAll(async () => { await prisma.$disconnect(); });

test("empty datasets expose no shift mutations and disable time-off submission", async ({ page }) => {
  expect(await prisma.serviceCatalog.count()).toBe(0);
  await page.goto("/book");
  await expect(page.getByRole("button", { name: /see available/i })).toBeDisabled();
  await page.goto("/technicians");
  await expect(page.getByText("No technicians found", { exact: true })).toBeVisible();
  await expect(page.getByRole("button", { name: /add exception|save day|edit .*exception|delete .*exception/i })).toHaveCount(0);
  await page.goto("/time-off");
  await page.getByRole("button", { name: /new request/i }).click();
  await expect(page.getByRole("button", { name: "Submit request" })).toBeDisabled();
});

test("malformed booking responses retain entered form data and create no job", async ({ page }) => {
  const service = await prisma.serviceCatalog.create({ data: { code: "UI_NULL_TEST", name: "UI test service", estDurationMin: 60 } });
  try {
    await page.route("**/api/book", route => route.fulfill({ status: 200, contentType: "application/json", body: "null" }));
    await page.goto("/book");
    await page.getByRole("radio").first().check();
    for (const [label, value] of Object.entries({ "First name": "UI", "Last name": "Test", Email: "ui@example.invalid", Phone: "4025550100", City: "Omaha", State: "NE", ZIP: "68102" }))
      await page.locator("label").filter({ hasText: new RegExp(`^${label}$`) }).locator("..").locator("input").fill(value);
    await page.getByPlaceholder("Street address").fill("1 Main St");
    await page.getByRole("button", { name: /see available/i }).click();
    await expect(page.getByText("Invalid response. Reload before continuing.", { exact: true })).toBeVisible();
    await expect(page.getByPlaceholder("Street address")).toHaveValue("1 Main St");
    expect(await prisma.job.count()).toBe(0);
  } finally { await prisma.serviceCatalog.delete({ where: { id: service.id } }); }
});

test("shift drafts follow the selected technician and time-off modal preserves failed input", async ({ page }) => {
  const metro = await prisma.metro.create({ data: { name: "UI controls metro", timezone: "America/Chicago" } });
  const dealership = await prisma.dealership.create({ data: { name: "UI controls dealership" } });
  const depot = await prisma.depot.create({ data: { metroId: metro.id, dealershipId: dealership.id, name: "UI controls depot", lat: 41.2, lng: -95.9,
    endpointPolicies: { create: { effectiveDate: new Date("1900-01-01T00:00:00Z"), departure: "HOME", returnTo: "HOME" } } } });
  const first = await prisma.technician.create({ data: { name: "Ada Shift", color: "#2563eb", availabilityVersions: initialAvailability(420, 900), homeLat: 41.2, homeLng: -95.9, shiftStartMin: 420, shiftEndMin: 900,
    depotAssignments: { create: { depotId: depot.id, effectiveDate: new Date("1900-01-01T00:00:00Z") } } } });
  const second = await prisma.technician.create({ data: { name: "Ben Shift", color: "#dc2626", availabilityVersions: initialAvailability(540, 1080), homeLat: 41.3, homeLng: -96.0, shiftStartMin: 540, shiftEndMin: 1080, active: false,
    depotAssignments: { create: { depotId: depot.id, effectiveDate: new Date("1900-01-01T00:00:00Z") } } } });
  const service = await prisma.serviceCatalog.create({ data: { code: "UI_SHIFT_TEST", name: "Shift test service", estDurationMin: 60 } });
  const override = await prisma.technicianShiftOverride.create({ data: { technicianId: first.id, serviceDate: new Date("2099-10-05T00:00:00Z"), available: true, shiftStartMin: 480, shiftEndMin: 960 } });
  const dateParts = new Intl.DateTimeFormat("en-US", { timeZone: "America/Chicago", year: "numeric", month: "2-digit", day: "2-digit" }).formatToParts(new Date());
  const part = (type: string) => dateParts.find(item => item.type === type)?.value ?? "";
  const currentDate = `${part("year")}-${part("month")}-${part("day")}`;
  const partial = await prisma.timeOffRequest.create({ data: { technicianId: first.id, category: "Other", reason: "Partial absence", status: "APPROVED",
    intervals: { create: { serviceDate: new Date(`${currentDate}T00:00:00Z`), startMin: 600, endMin: 720 } } } });
  const full = await prisma.timeOffRequest.create({ data: { technicianId: first.id, category: "Other", reason: "Full absence", status: "APPROVED",
    intervals: { create: { serviceDate: new Date(`${currentDate}T00:00:00Z`), startMin: 0, endMin: 1440 } } } });
  const malformed = await prisma.timeOffRequest.create({ data: { technicianId: first.id, category: "Other", reason: "Malformed analysis", status: "PENDING",
    intervals: { create: { serviceDate: new Date("2099-10-06T00:00:00Z"), startMin: 480, endMin: 1020 } },
    report: { create: { status: "ANALYZING", data: { days: null } } } } });
  try {
    await page.route("**/api/dispatch/availability", route => route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ success: true }) }));
    await page.goto("/technicians");
    await expect(page.getByRole("button", { name: "Shift test service" })).toHaveAttribute("aria-pressed", "false");
    await page.getByRole("button", { name: "+ Add exception" }).click();
    await expect(page.getByLabel("Start")).toHaveValue("07:00");
    await page.getByRole("button", { name: /Ben Shift/ }).click();
    await expect(page.getByText("Inactive technician", { exact: true })).toBeVisible();
    await page.getByRole("button", { name: "+ Add exception" }).click();
    await expect(page.getByLabel("Start")).toHaveValue("09:00");
    await page.getByRole("button", { name: /Ada Shift/ }).click();
    await page.getByRole("button", { name: `Edit Ada Shift's exception for 2099-10-05` }).click();
    await expect(page.getByLabel("Date")).toBeDisabled();
    page.once("dialog", async dialog => { expect(dialog.message()).toContain("default shift hours will be restored"); await dialog.dismiss(); });
    await page.getByRole("button", { name: `Delete Ada Shift's exception for 2099-10-05` }).click();

    await page.route("**/api/time-off", route => route.fulfill({ status: 409, contentType: "application/json", body: JSON.stringify({ error: "Overlapping time-off request" }) }));
    await page.goto("/time-off");
    await expect(page.getByText("Progress unknown", { exact: true }).first()).toBeVisible();
    await page.getByRole("button", { name: "Pending" }).click();
    await expect(page.getByRole("button", { name: "Pending" })).toHaveAttribute("aria-pressed", "true");
    await expect(page.getByLabel("Analysis 0% complete")).toBeVisible();
    await expect(page.getByRole("button", { name: "Refresh analysis" })).toBeVisible();
    await page.getByRole("button", { name: "Ada Shift" }).click();
    await expect(page.getByText("The saved analysis report is malformed.", { exact: true })).toBeVisible();
    await page.getByRole("button", { name: "Ready for review" }).click();
    await expect(page.getByText(/No requests match the ready for review filter/)).toBeVisible();
    const opener = page.getByRole("button", { name: /new request/i });
    await opener.click();
    await expect(page.getByRole("dialog")).toBeVisible();
    await expect(page.getByLabel("Technician")).toBeFocused();
    await page.getByLabel("First date").fill("2099-10-06");
    await page.getByLabel("Explain the planned absence").fill("Keep this explanation after failure");
    await page.getByRole("button", { name: "Submit request" }).click();
    await expect(page.locator("p[role=alert]")).toHaveText("Overlapping time-off request");
    await expect(page.getByLabel("Explain the planned absence")).toHaveValue("Keep this explanation after failure");
    await page.keyboard.press("Escape");
    await expect(page.getByRole("dialog")).toHaveCount(0);
    await expect(opener).toBeFocused();
  } finally {
    await prisma.timeOffRequest.deleteMany({ where: { id: { in: [partial.id, full.id, malformed.id] } } });
    await prisma.technicianShiftOverride.delete({ where: { id: override.id } });
    await prisma.serviceCatalog.delete({ where: { id: service.id } });
    await prisma.technician.deleteMany({ where: { id: { in: [first.id, second.id] } } });
    await prisma.depot.delete({ where: { id: depot.id } });
    await prisma.dealership.delete({ where: { id: dealership.id } });
    await prisma.metro.delete({ where: { id: metro.id } });
  }
});

test("dispatch retains missing-location appointments and shows malformed-history errors", async ({ page }) => {
  const metro = await prisma.metro.create({ data: { name: "UI test metro", timezone: "America/Chicago" } });
  const dealership = await prisma.dealership.create({ data: { name: "UI test dealership" } });
  const depot = await prisma.depot.create({ data: { metroId: metro.id, dealershipId: dealership.id, name: "UI test depot", lat: 41.25, lng: -95.93,
    endpointPolicies: { create: { effectiveDate: new Date("1900-01-01T00:00:00Z"), departure: "HOME", returnTo: "HOME" } } } });
  const tech = await prisma.technician.create({ data: { name: "UI test tech", color: "#059669", availabilityVersions: initialAvailability(480, 1020), homeLat: 41.25, homeLng: -95.93, shiftStartMin: 480, shiftEndMin: 1020,
    depotAssignments: { create: { depotId: depot.id, effectiveDate: new Date("1900-01-01T00:00:00Z") } } } });
  const service = await prisma.serviceCatalog.create({ data: { code: "UI_LOCATION_TEST", name: "UI location service", estDurationMin: 60 } });
  const customer = await prisma.customer.create({ data: { firstName: "Missing", lastName: "Location", email: "missing@example.invalid", phone: "4025550100" } });
  const address = await prisma.address.create({ data: { customerId: customer.id, line1: "Unverified address", city: "Omaha", state: "NE", postalCode: "68102" } });
  const job = await prisma.job.create({ data: { customerId: customer.id, addressId: address.id, serviceId: service.id, durationMin: 60, status: "SCHEDULED" } });
  const start = new Date("2026-10-05T15:00:00Z"), end = new Date("2026-10-05T16:00:00Z");
  const appointment = await prisma.appointment.create({ data: { jobId: job.id, technicianId: tech.id, serviceDate: new Date("2026-10-05T00:00:00Z"), windowStart: start, windowEnd: end, plannedStart: start, plannedEnd: end, sequence: 0 } });
  try {
    await page.route("**/api/dispatch/**", route => route.fulfill({ status: 200, contentType: "application/json", body: "null" }));
    await page.goto("/dispatch?date=2026-10-05");
    await expect(page.getByText("Missing Location", { exact: true })).toBeVisible();
    await expect(page.getByText("Location data missing. Routing is blocked.", { exact: true })).toBeVisible();
    await expect(page.getByRole("button", { name: "Preview optimization" })).toBeDisabled();
    await expect(page.getByText("Invalid response. Reload before continuing.", { exact: true })).toBeVisible();
  } finally {
    await prisma.appointment.delete({ where: { id: appointment.id } }); await prisma.job.delete({ where: { id: job.id } });
    await prisma.address.delete({ where: { id: address.id } }); await prisma.customer.delete({ where: { id: customer.id } });
    await prisma.serviceCatalog.delete({ where: { id: service.id } }); await prisma.technician.delete({ where: { id: tech.id } });
    await prisma.depot.delete({ where: { id: depot.id } }); await prisma.dealership.delete({ where: { id: dealership.id } });
    await prisma.metro.delete({ where: { id: metro.id } });
  }
});

test("sequential runs default to a fixed 30-mile radius and submit the selected preset", async ({ page }) => {
  const id = "44444444-4444-4444-8444-444444444444";
  let submittedConfig: unknown;
  await page.route("**/api/dispatch/testing**", async route => {
    if (route.request().method() === "GET") return route.fulfill({ status: 200, contentType: "application/json",
      body: JSON.stringify({ runs: [], horizon: ["2026-10-05"] }) });
    const body = z.object({ action: z.string(), config: z.unknown().optional() }).parse(route.request().postDataJSON() as unknown);
    if (body.action === "create") submittedConfig = body.config;
    const parsed = z.object({ count: z.number(), seed: z.number().nullable(), policy: z.string(), weights: z.array(z.number()), radiusMi: z.number() })
      .parse(submittedConfig);
    const run = { id, status: "STOPPED", createdAt: "2026-09-21T12:00:00Z", config: { ...parsed, seed: parsed.seed ?? 123 },
      purgedAt: null, purgedCount: null, generation: null, revision: 1, error: null, horizon: ["2026-10-05"], currentHorizon: ["2026-10-05"],
      requests: [], previews: [], applied: [] };
    return route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(run) });
  });
  await page.goto("/dispatch/testing");
  const radius = page.getByLabel("Generation radius");
  await expect(radius).toHaveValue("30");
  await expect(radius.locator("option")).toHaveText(["10 miles", "20 miles", "30 miles", "45 miles", "65 miles"]);
  await radius.selectOption("45");
  await page.getByRole("button", { name: "Start new run" }).click();
  await expect.poll(() => z.object({ radiusMi: z.number() }).parse(submittedConfig).radiusMi).toBe(45);
  await expect(page.getByText(/45-mile generation radius/)).toBeVisible();
});

test("running sequential polling retains its preview map and refetches only on phase changes", async ({ page }) => {
  const id = "22222222-2222-4222-8222-222222222222", optimizationId = "preview-poll-test";
  const preview = { run_id: optimizationId, metro_id: "metro-omaha", service_date: "2026-10-05", status: "PREVIEW", reason: null,
    solver_status: "SOLVED", solve_ms: 10, routing_identity: "routing-test", configuration_version: "config-test", objective_improvement: 100,
    churn_penalty_minutes: 0, optimized: false, appointments_moved: 0, created_at: "2026-09-21T12:00:00Z", applied_at: null, warnings: [],
    route_summary_before: [], route_summary_after: [], changes: [] };
  const run = { id, status: "RUNNING", createdAt: "2026-09-21T12:00:00Z", config: { count: 1, seed: 42, policy: "earliest", weights: [1, 1, 1, 1], radiusMi: 30 },
    purgedAt: null, purgedCount: null, generation: null, revision: 2, error: null, horizon: ["2026-10-05"], currentHorizon: ["2026-10-05"], requests: [],
    previews: [{ id: `${id}:2026-10-05`, serviceDate: "2026-10-05", optimizationId, result: preview, error: null }], applied: [] };
  let runReads = 0;
  const geometryPhases: string[] = [];
  await page.route("http://localhost:8083/**", route => route.fulfill({ status: 404 }));
  await page.route("**/api/dispatch/geometry**", route => {
    const phase = new URL(route.request().url()).searchParams.get("phase") ?? "";
    geometryPhases.push(phase);
    return route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({
      type: "FeatureCollection", routingIdentity: "routing-test", serviceDate: "2026-10-05", phase, features: [], stops: [],
    }) });
  });
  await page.route("**/api/dispatch/testing**", route => {
    const url = new URL(route.request().url());
    if (route.request().method() === "GET" && url.searchParams.has("id")) runReads++;
    return route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(
      route.request().method() === "GET" && !url.searchParams.has("id") ? { runs: [run], horizon: ["2026-10-05"] } : run) });
  });

  await page.goto(`/dispatch/testing?run=${id}`);
  await expect.poll(() => geometryPhases.length).toBe(1);
  const canvas = page.locator(".maplibregl-canvas");
  await expect(canvas).toHaveCount(1);
  await canvas.evaluate(element => { element.dataset.pollingMap = "retained"; });
  await page.waitForTimeout(2200);
  await expect.poll(() => runReads, { timeout: 5000 }).toBeGreaterThanOrEqual(2);
  expect(geometryPhases).toEqual(["after"]);
  await expect(canvas).toHaveAttribute("data-polling-map", "retained");

  await page.getByRole("button", { name: "Before preview" }).click();
  await expect.poll(() => geometryPhases).toEqual(["after", "before"]);
});

test("address generation progress survives pause, reload, and resume into booking", async ({ page }) => {
  const id = "33333333-3333-4333-8333-333333333333";
  const progress = { acceptedCount: 1, targetCount: 3, candidatesTried: 12, batches: 3, elapsedMs: 154000,
    completedAt: null as string | null, consecutiveNoProgressBatches: 3 };
  let status = "PAUSED", advanceCalls = 0;
  let releaseAdvance!: () => void;
  const advanceGate = new Promise<void>(resolve => { releaseAdvance = resolve; });
  const responseRun = () => ({ id, status, createdAt: "2026-09-21T12:00:00Z",
    config: { count: 3, seed: 42, policy: "earliest", weights: [1, 1, 1, 1], radiusMi: 30 }, purgedAt: null, purgedCount: null,
    generation: { ...progress }, revision: status === "RUNNING" ? 3 : 4, error: null, horizon: ["2026-10-05"], currentHorizon: ["2026-10-05"],
    requests: [], previews: [], applied: [] });
  await page.route("**/api/dispatch/testing**", async route => {
    const url = new URL(route.request().url());
    if (route.request().method() === "GET") return route.fulfill({ status: 200, contentType: "application/json",
      body: JSON.stringify(url.searchParams.has("id") ? responseRun() : { runs: [responseRun()], horizon: ["2026-10-05"] }) });
    const body = z.object({ action: z.string() }).parse(route.request().postDataJSON() as unknown);
    if (body.action === "resume") status = "RUNNING";
    if (body.action === "pause") { status = "PAUSED"; releaseAdvance(); }
    if (body.action === "advance") {
      advanceCalls++;
      if (advanceCalls === 1) { await advanceGate; progress.acceptedCount = 2; progress.candidatesTried = 24; progress.batches = 4; progress.consecutiveNoProgressBatches = 0; }
      else { progress.acceptedCount = 3; progress.candidatesTried = 30; progress.batches = 5; progress.completedAt = "2026-09-21T12:03:00Z"; status = "COMPLETED"; }
    }
    return route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(responseRun()) });
  });
  await page.goto(`/dispatch/testing?run=${id}`);
  await expect(page.getByText("1 of 3 addresses ready.", { exact: false })).toBeVisible();
  await expect(page.getByText(/No new addresses were found in the last 3 batches/)).toBeVisible();
  await expect(page.getByRole("option", { name: /1\/3 addresses/ })).toBeAttached();
  await page.getByRole("button", { name: "Resume" }).click();
  await expect(page.getByRole("button", { name: "Pause" })).toBeEnabled();
  await page.getByRole("button", { name: "Pause" }).click();
  await expect(page.getByText("2 of 3 addresses ready.", { exact: false })).toBeVisible();
  await page.reload();
  await expect(page.getByText("2 of 3 addresses ready.", { exact: false })).toBeVisible();
  await page.getByRole("button", { name: "Resume" }).click();
  await expect(page.getByText("3 of 3 addresses ready.", { exact: false })).toBeVisible();
  await expect(page.getByText(/Address generation complete/)).toBeVisible();
});

test("sequential review confirms guarded apply, reports conflicts, refreshes routes, and confirms purge", async ({ page }) => {
  const id = "11111111-1111-4111-8111-111111111111", optimizationId = "preview-ui-test";
  const preview = { run_id: optimizationId, metro_id: "metro-omaha", service_date: "2026-10-05", status: "PREVIEW", reason: null,
    solver_status: "SOLVED", solve_ms: 10, routing_identity: "routing-test", configuration_version: "config-test", objective_improvement: 100,
    churn_penalty_minutes: 0, optimized: false, appointments_moved: 1, created_at: "2026-09-21T12:00:00Z", applied_at: null, warnings: [],
    route_summary_before: [], route_summary_after: [], changes: [] };
  const baseRun = { id, status: "COMPLETED", createdAt: "2026-09-21T12:00:00Z", config: { count: 1, seed: 42, policy: "earliest", weights: [1, 1, 1, 1], radiusMi: 30 },
    purgedAt: null, purgedCount: null, generation: null, revision: 2, error: null, horizon: ["2026-10-05"], currentHorizon: ["2026-10-05"], requests: [],
    previews: [{ id: `${id}:2026-10-05`, serviceDate: "2026-10-05", optimizationId, result: preview, error: null }],
    applied: [{ id: optimizationId, status: "PREVIEW", appliedAt: null }] };
  let applied = false, purged = false, applyCalls = 0, runReads = 0;
  const geometryPhases: string[] = [];
  await page.route("http://localhost:8083/**", route => route.fulfill({ status: 404 }));
  await page.route("**/api/dispatch/geometry**", route => {
    const phase = new URL(route.request().url()).searchParams.get("phase") ?? "";
    geometryPhases.push(phase);
    if (phase === "before") return route.fulfill({ status: 409, contentType: "application/json", body: JSON.stringify({ detail: "Saved route technician is unavailable" }) });
    return route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({
      type: "FeatureCollection", routingIdentity: "routing-test", serviceDate: "2026-10-05", phase, features: [], stops: [],
    }) });
  });
  await page.route("**/api/dispatch/optimize/apply", async route => {
    applyCalls++;
    if (applyCalls === 1) return route.fulfill({ status: 409, contentType: "application/json", body: JSON.stringify({ error: "Schedule version changed" }) });
    applied = true; return route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ ...preview, status: "APPLIED", optimized: true, applied_at: "2026-09-21T13:00:00Z" }) });
  });
  await page.route("**/api/dispatch/testing**", async route => {
    if (route.request().method() === "POST") {
      const body = z.object({ action: z.string() }).safeParse(route.request().postDataJSON() as unknown);
      if (body.success && body.data.action === "purge") purged = true;
    }
    const run = { ...baseRun, status: purged ? "PURGED" : "COMPLETED", purgedAt: purged ? "2026-09-21T14:00:00Z" : null, purgedCount: purged ? 1 : null,
      applied: [{ id: optimizationId, status: applied ? "APPLIED" : "PREVIEW", appliedAt: applied ? "2026-09-21T13:00:00Z" : null }] };
    const url = new URL(route.request().url());
    if (route.request().method() === "GET" && url.searchParams.has("id")) runReads++;
    return route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(route.request().method() === "GET" && !url.searchParams.has("id") ? { runs: [run], horizon: ["2026-10-05"] } : run) });
  });
  await page.goto(`/dispatch/testing?run=${id}`);
  await expect(page.getByRole("button", { name: "Apply proposal" })).toBeVisible();
  await expect(page.getByRole("button", { name: "Proposed roads" })).toHaveAttribute("aria-pressed", "true");
  await expect.poll(() => geometryPhases).toEqual(["after"]);
  const readsAfterLoad = runReads;
  await page.waitForTimeout(2200);
  expect(runReads).toBe(readsAfterLoad);
  await page.getByRole("button", { name: "Reload progress" }).click();
  await expect.poll(() => runReads).toBe(readsAfterLoad + 1);
  await page.getByRole("button", { name: "Before preview" }).click();
  await expect(page.getByText("Saved route technician is unavailable Stop markers remain visible.", { exact: true })).toBeVisible();
  await page.getByRole("button", { name: "Proposed roads" }).click();
  await expect.poll(() => geometryPhases).toEqual(["after", "before", "after"]);
  page.once("dialog", dialog => dialog.dismiss()); await page.getByRole("button", { name: "Apply proposal" }).click(); expect(applyCalls).toBe(0);
  page.once("dialog", dialog => dialog.accept()); await page.getByRole("button", { name: "Apply proposal" }).click();
  await expect(page.getByText("Schedule version changed", { exact: true })).toBeVisible();
  page.once("dialog", dialog => dialog.accept()); await page.getByRole("button", { name: "Apply proposal" }).click();
  await expect(page.getByRole("button", { name: "Apply proposal" })).toHaveCount(0);
  await expect(page.getByRole("button", { name: "Current roads" })).toHaveAttribute("aria-pressed", "true");
  page.once("dialog", dialog => dialog.accept()); await page.getByRole("button", { name: "Delete generated appointments" }).click();
  await expect(page.getByText(/Purged 1 generated appointment/)).toBeVisible();
  await expect(page.getByRole("button", { name: "Delete generated appointments" })).toBeEnabled();
});
