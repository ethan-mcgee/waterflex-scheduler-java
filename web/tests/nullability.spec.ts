import { test, expect } from "@playwright/test";
import { PrismaClient } from "@prisma/client";
import { z } from "zod";

const prisma = new PrismaClient();
test.afterAll(async () => { await prisma.$disconnect(); });

test("empty lists disable actions; malformed booking responses stay on the form", async ({ page }) => {
  expect(await prisma.serviceCatalog.count()).toBe(0);
  await page.goto("/book");
  await expect(page.getByRole("button", { name: /see available/i })).toBeDisabled();
  await page.goto("/dispatch/availability");
  await expect(page.getByRole("button", { name: "Save day" })).toBeDisabled();
  await page.goto("/time-off");
  await expect(page.getByRole("button", { name: "Submit time-off request" })).toBeDisabled();
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

test("dispatch retains missing-location appointments and shows malformed-history errors", async ({ page }) => {
  const metro = await prisma.metro.create({ data: { name: "UI test metro", timezone: "America/Chicago" } });
  const tech = await prisma.technician.create({ data: { metroId: metro.id, name: "UI test tech", homeLat: 41.25, homeLng: -95.93, shiftStartMin: 480, shiftEndMin: 1020 } });
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
    await prisma.serviceCatalog.delete({ where: { id: service.id } }); await prisma.technician.delete({ where: { id: tech.id } }); await prisma.metro.delete({ where: { id: metro.id } });
  }
});

test("running sequential polling retains its preview map and refetches only on phase changes", async ({ page }) => {
  const id = "22222222-2222-4222-8222-222222222222", optimizationId = "preview-poll-test";
  const preview = { run_id: optimizationId, metro_id: "metro-omaha", service_date: "2026-10-05", status: "PREVIEW", reason: null,
    solver_status: "SOLVED", solve_ms: 10, routing_identity: "routing-test", configuration_version: "config-test", objective_improvement: 100,
    churn_penalty_minutes: 0, optimized: false, appointments_moved: 0, created_at: "2026-09-21T12:00:00Z", applied_at: null, warnings: [],
    route_summary_before: [], route_summary_after: [], changes: [] };
  const run = { id, status: "RUNNING", createdAt: "2026-09-21T12:00:00Z", config: { count: 1, seed: 42, policy: "earliest", weights: [1, 1, 1, 1] },
    purgedAt: null, purgedCount: null, revision: 2, error: null, horizon: ["2026-10-05"], currentHorizon: ["2026-10-05"], requests: [],
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

test("sequential review confirms guarded apply, reports conflicts, refreshes routes, and confirms purge", async ({ page }) => {
  const id = "11111111-1111-4111-8111-111111111111", optimizationId = "preview-ui-test";
  const preview = { run_id: optimizationId, metro_id: "metro-omaha", service_date: "2026-10-05", status: "PREVIEW", reason: null,
    solver_status: "SOLVED", solve_ms: 10, routing_identity: "routing-test", configuration_version: "config-test", objective_improvement: 100,
    churn_penalty_minutes: 0, optimized: false, appointments_moved: 1, created_at: "2026-09-21T12:00:00Z", applied_at: null, warnings: [],
    route_summary_before: [], route_summary_after: [], changes: [] };
  const baseRun = { id, status: "COMPLETED", createdAt: "2026-09-21T12:00:00Z", config: { count: 1, seed: 42, policy: "earliest", weights: [1, 1, 1, 1] },
    purgedAt: null, purgedCount: null, revision: 2, error: null, horizon: ["2026-10-05"], currentHorizon: ["2026-10-05"], requests: [],
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
