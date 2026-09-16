import { expect, test } from "@playwright/test";

const metricKeys = ["requested_jobs", "booked_jobs", "rejected_jobs", "acceptance_rate", "drive_minutes", "drive_minutes_per_appointment", "total_paid_route_minutes", "overtime_minutes", "mean_days_to_service", "mean_remaining_slack_minutes", "dispatch_drive_savings_minutes", "dispatch_paid_time_savings_minutes", "appointments_reassigned", "appointments_materially_retimed", "window_violations", "feasibility_violations", "simulation_runtime_ms", "solver_runtime_ms", "solver_fallbacks"];

function simulatedReport(seed: number) {
  const base = { requested_jobs: 10, booked_jobs: 9, rejected_jobs: 1, acceptance_rate: .9, drive_minutes: 100, drive_minutes_per_appointment: 11.1, total_paid_route_minutes: 400, overtime_minutes: 0, mean_days_to_service: 2, mean_remaining_slack_minutes: 40, dispatch_drive_savings_minutes: 10, dispatch_paid_time_savings_minutes: 15, appointments_reassigned: 2, appointments_materially_retimed: 1, window_violations: 0, feasibility_violations: 0, simulation_runtime_ms: 30, solver_runtime_ms: 5, solver_fallbacks: 0 };
  return { algorithm_version: "browser-test-v1", config: { seed, job_count: 10, technician_count: 2, horizon_days: 3 }, results: (["soonest", "route_friendly", "mixed"] as const).flatMap((customer_profile) => (["legacy", "enhanced_scoring", "lookahead"] as const).map((strategy) => {
    const metrics = { ...base, total_paid_route_minutes: strategy === "enhanced_scoring" ? 360 : strategy === "lookahead" ? 380 : 400, drive_minutes_per_appointment: strategy === "enhanced_scoring" ? 9 : strategy === "lookahead" ? 10 : 11.1 };
    return { customer_profile, strategy, metrics, deltas_from_legacy: Object.fromEntries(metricKeys.map((key) => [key, { absolute: 0, percent: 0 }])), warnings: strategy === "lookahead" && customer_profile === "mixed" ? ["Synthetic warning for browser coverage."] : [] };
  })) };
}

const databaseUrl = process.env.DATABASE_URL ?? "";
const databaseName = databaseUrl ? new URL(databaseUrl).pathname.split("/").filter(Boolean).at(-1) ?? "" : "";

function nextMonday(): string {
  const day = new Date();
  day.setUTCDate(day.getUTCDate() + 7);
  day.setUTCDate(day.getUTCDate() + ((8 - day.getUTCDay()) % 7));
  return day.toISOString().slice(0, 10);
}

test.beforeAll(() => {
  if (!databaseName.endsWith("_test")) {
    throw new Error(`Browser setup refused for database ${JSON.stringify(databaseName)}. Use a database ending in _test.`);
  }
});

test("runs and exports a fixed strategy comparison", async ({ page }) => {
  await page.goto("/optimization-test");
  await page.getByLabel("Jobs").fill("10");
  await page.getByLabel("Technicians").fill("2");
  await page.getByLabel("Horizon days").fill("3");
  await page.getByRole("spinbutton", { name: "Seed" }).fill("42");
  await page.getByRole("button", { name: "Run comparison" }).click();

  await expect(page.getByRole("heading", { name: "Comparison results" })).toBeVisible();
  await expect(page.getByText("What this test says")).toBeVisible();
  await expect(page.getByText("This scenario only")).toBeVisible();
  await expect(page.getByRole("heading", { name: "How each scenario performed" })).toBeVisible();
  for (const profile of ["Soonest", "Route-friendly", "Mixed"]) await expect(page.getByRole("heading", { name: profile, exact: true })).toBeVisible();
  await expect(page.getByRole("heading", { name: "Customer acceptance" })).toBeVisible();
  await expect(page.getByRole("heading", { name: "Paid minutes per booked appointment" })).toBeVisible();
  await page.getByText("Engineering evidence", { exact: true }).click();
  await expect(page.getByRole("heading", { name: "Safety", exact: true })).toBeVisible();
  await page.getByText("Raw results", { exact: true }).click();
  await expect(page.getByRole("cell", { name: "Current control", exact: true }).first()).toBeVisible();
  await expect(page.getByRole("button", { name: "Download JSON" })).toBeVisible();
  await expect(page.getByRole("button", { name: "Download CSV" })).toBeVisible();
});

test("validates twenty sequential runs and keeps the evidence usable on a narrow screen", async ({ page }) => {
  const seeds: number[] = [];
  await page.route("**/api/optimization-test", async (route) => {
    const seed = JSON.parse(route.request().postData() ?? "{}").seed as number;
    seeds.push(seed);
    await new Promise((resolve) => setTimeout(resolve, 15));
    await route.fulfill({ json: simulatedReport(seed) });
  });
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto("/optimization-test");
  await page.getByLabel("Validate strategy").check();
  await page.getByRole("spinbutton", { name: "Seed" }).fill("4294967294");
  await page.getByRole("button", { name: "Validate strategy" }).click();
  await expect(page.getByRole("status")).toContainText(/Run \d+ of 20/);
  await expect(page.getByText("Validated across 20 runs")).toBeVisible();
  await expect(page.getByRole("heading", { name: "Validation evidence" })).toBeVisible();
  await expect(page.getByText("Warnings that qualify this conclusion")).toBeVisible();
  expect(seeds).toHaveLength(20);
  expect(seeds.slice(0, 4)).toEqual([4294967294, 4294967295, 0, 1]);
  await page.getByText("Raw results", { exact: true }).click();
  await expect(page.getByText("4294967294", { exact: true }).first()).toBeVisible();
});

test("books tagged fake data and applies a dispatch preview", async ({ page, request }) => {
  const date = nextMonday();
  const seed = "3";
  try {
    await page.goto("/fake-data");
    await page.getByLabel("Start date").fill(date);
    await page.getByLabel("End date").fill(date);
    await page.getByLabel("Total calls").fill("24");
    await page.getByLabel("Reproducible seed (optional)").fill(seed);
    await page.getByRole("button", { name: "Generate appointments" }).click();
    await expect(page.getByRole("heading", { name: "Generation result" })).toBeVisible();
    await expect(page.getByText("Created", { exact: true })).toBeVisible();

    await page.goto(`/dispatch?date=${date}`);
    await expect(page.getByRole("heading", { name: "Dispatch board" })).toBeVisible();
    await page.getByRole("button", { name: "Preview optimization" }).click();
    await expect(page.getByText("PREVIEW READY", { exact: true })).toBeVisible();
    await expect(page.getByText(/Drive: \d+ to \d+ min/)).toBeVisible();
    await expect(page.getByText(/Paid route: \d+ to \d+ min/)).toBeVisible();

    page.once("dialog", (dialog) => dialog.accept());
    await page.getByRole("button", { name: "Apply proposal" }).click();
    await expect(page.getByText(/Applied \d+ appointment change/)).toBeVisible();
    await page.getByText(/Optimization history/).click();
    await expect(page.getByRole("button", { name: /APPLIED/ }).first()).toBeVisible();
  } finally {
    const cleanup = await request.delete(`/api/fake-data?startDate=${date}&endDate=${date}`);
    expect(cleanup.ok()).toBeTruthy();
  }
});
