// Drags a visit to another technician on the weekly schedule, again and again, and records where the time went in each
// check: the browser's measure from the drop to the answer painted, the Resource Timing entry of the request, and the
// portal's Server-Timing phases. The scheduler's own breakdown is joined later from its log by the report
// (infra/host-conformance/src/load/move-check-report.ts). One row per drag goes to <run dir>/runs.jsonl as it happens.
//
// Configuration, from the start script's environment:
//   TIMING_RUN_DIR       the run directory, with plan.json from timing/seedMoveTiming.ts
//   TIMING_PORTAL_URL    the portal (playwright.timing.config.ts)
//   TIMING_WARM_REPS     warm drags per target and simulated delay (default 30)
//   TIMING_WARMUP        warm drags before measuring, recorded as discarded (default 3)
//   TIMING_DELAYS        simulated browser-to-portal round trip delays in ms (default 0,20,50,100)
//   TIMING_CONCURRENCY   browsers dragging at once for the optional concurrency case, for example 2,4 (default none)
//   TIMING_CONCURRENCY_ROUNDS  rounds per concurrency level (default 10)
import { test, expect, type Browser, type BrowserContext, type Page } from "@playwright/test";
import { appendFileSync } from "node:fs";
import { join } from "node:path";
import { z } from "zod";
import { CLIENT_COOKIE } from "../lib/clients";
import { readPlan, type TimingPlan } from "./timingPlan";

const runDir = z.string().min(1).parse(process.env.TIMING_RUN_DIR);
const integer = (name: string, fallback: number, max: number) => {
  const value = process.env[name];
  const parsed = value === undefined || value === "" ? fallback : Number(value);
  if (!Number.isInteger(parsed) || parsed < 0 || parsed > max) throw new Error(`${name} must be an integer from 0 to ${max}`);
  return parsed;
};
const list = (name: string, fallback: string, max: number) => (process.env[name] ?? fallback).split(",").map(item => item.trim()).filter(item => item !== "")
  .map(item => { const value = Number(item); if (!Number.isInteger(value) || value < 0 || value > max) throw new Error(`${name} has ${item}`); return value; });
const WARM_REPS = integer("TIMING_WARM_REPS", 30, 1000);
const WARMUP = integer("TIMING_WARMUP", 3, 100);
const DELAYS = list("TIMING_DELAYS", "0,20,50,100", 2000);
const CONCURRENCY = list("TIMING_CONCURRENCY", "", 16);
const ROUNDS = integer("TIMING_CONCURRENCY_ROUNDS", 10, 1000);

type Target = "roomy" | "full";
const EXPECTED: Record<Target, string> = { roomy: "FEASIBLE", full: "NOT_FEASIBLE" };
type Case = "first" | "warmup" | "warm" | "cold" | `concurrency-${number}`;

/** What the page measured for one drag; all times are the page's performance clock in milliseconds. */
const measured = z.object({
  detail: z.object({ outcome: z.string().min(1) }),
  measure: z.object({ startTime: z.number(), duration: z.number() }),
  resource: z.object({ startTime: z.number(), fetchStart: z.number(), requestStart: z.number(), responseStart: z.number(), responseEnd: z.number(),
    duration: z.number(), serverTiming: z.array(z.object({ name: z.string(), duration: z.number() })) }).nullable(),
});

let sequence = 0;

/** The schedule week of `date`, signed in as the timing client. */
async function openWeek(page: Page, plan: TimingPlan, date: string) {
  await page.goto(`/schedule?metroId=${plan.metroId}&week=${date}`);
  await expect(page.locator(`[aria-label="Weekly technician schedule"] [data-drop-technician="${plan.technicians.source}"][data-drop-date="${date}"]`)).toBeVisible();
}

async function newPage(browser: Browser, baseURL: string, plan: TimingPlan): Promise<{ context: BrowserContext; page: Page }> {
  const context = await browser.newContext({ baseURL, viewport: { width: 1500, height: 2400 } });
  await context.addCookies([{ name: CLIENT_COOKIE, value: plan.clientId, url: baseURL }]);
  const page = await context.newPage();
  return { context, page };
}

/** Drags the day's moving visit onto the target's cell, waits for the answer to be painted, and records the row. */
async function drag(page: Page, plan: TimingPlan, date: string, moving: string, target: Target, row: { case: Case; delayMs: number; rep: number }) {
  const grid = page.locator('[aria-label="Weekly technician schedule"]');
  const card = grid.locator(`[data-appointment-id="${moving}"]`);
  const cell = grid.locator(`[data-drop-technician="${plan.technicians[target]}"][data-drop-date="${date}"]`);
  await page.evaluate(() => { performance.clearMeasures("waterflex.move-check"); performance.clearResourceTimings(); });
  await card.scrollIntoViewIfNeeded();
  const from = await card.boundingBox(), to = await cell.boundingBox();
  const view = page.viewportSize();
  const visible = (box: { x: number; y: number; width: number; height: number } | null) =>
    box !== null && view !== null && box.y >= 0 && box.x >= 0 && box.y + box.height <= view.height && box.x + box.width <= view.width;
  if (from === null || to === null || !visible(from) || !visible(to)) throw new Error(`The visit or the ${target} cell on ${date} is not fully on screen`);
  const startedAt = new Date().toISOString();
  await page.mouse.move(from.x + from.width / 2, from.y + from.height / 2);
  await page.mouse.down();
  await page.mouse.move(to.x + to.width / 2, to.y + 40, { steps: 8 });
  await page.mouse.up();
  await expect.poll(() => page.evaluate(() => performance.getEntriesByName("waterflex.move-check", "measure").length), { timeout: 60_000 }).toBe(1);
  const raw: unknown = await page.evaluate(() => {
    const measure = performance.getEntriesByName("waterflex.move-check", "measure")[0];
    const resource = performance.getEntriesByType("resource").filter(entry => entry.name.endsWith("/api/schedule/move-check")).at(-1);
    const detail: unknown = measure instanceof PerformanceMeasure ? measure.detail : null;
    return {
      detail,
      measure: measure === undefined ? null : { startTime: measure.startTime, duration: measure.duration },
      resource: resource instanceof PerformanceResourceTiming ? { startTime: resource.startTime, fetchStart: resource.fetchStart, requestStart: resource.requestStart,
        responseStart: resource.responseStart, responseEnd: resource.responseEnd, duration: resource.duration,
        serverTiming: resource.serverTiming.map(item => ({ name: item.name, duration: item.duration })) } : null,
    };
  });
  const data = measured.parse(raw);
  const portal = Object.fromEntries((data.resource?.serverTiming ?? []).map(item => [item.name, item.duration]));
  const record = { seq: sequence++, case: row.case, delayMs: row.delayMs, rep: row.rep, discarded: row.case === "warmup", date, target,
    expected: EXPECTED[target], outcome: data.detail.outcome, startedAt, endedAt: new Date().toISOString(),
    browserMs: data.measure.duration,
    resource: data.resource === null ? null : { fetchStart: data.resource.fetchStart - data.measure.startTime, requestStart: data.resource.requestStart - data.measure.startTime,
      responseStart: data.resource.responseStart - data.measure.startTime, responseEnd: data.resource.responseEnd - data.measure.startTime, duration: data.resource.duration },
    portal };
  appendFileSync(join(runDir, "runs.jsonl"), `${JSON.stringify(record)}\n`);
  return record;
}

/**
 * Simulated browser-to-portal latency on one page's network, through Chromium's DevTools protocol. Every change goes
 * through the one session opened here: Chromium 156 ignores the conditions sent by a third or later session on the
 * same page, which silently left every delay case of the first full run at +20 ms or less.
 */
async function delaySimulator(page: Page): Promise<(latencyMs: number) => Promise<void>> {
  const session = await page.context().newCDPSession(page);
  await session.send("Network.enable");
  return async latencyMs => {
    await session.send("Network.emulateNetworkConditions", { offline: false, latency: latencyMs, downloadThroughput: -1, uploadThroughput: -1 });
  };
}

test("move check timing", async ({ browser, baseURL }) => {
  if (baseURL === undefined) throw new Error("No portal URL");
  const plan = readPlan(runDir);
  const warm = plan.days.find(day => day.kind === "warm");
  if (warm === undefined) throw new Error("The plan has no warm day");
  const { context, page } = await newPage(browser, baseURL, plan);
  await openWeek(page, plan, warm.date);
  const simulateDelay = await delaySimulator(page);

  // The first check after the portal starts also verifies the client's token with the scheduler and is cold.
  await drag(page, plan, warm.date, warm.moving, "roomy", { case: "first", delayMs: 0, rep: 0 });
  for (let rep = 0; rep < WARMUP; rep++)
    await drag(page, plan, warm.date, warm.moving, rep % 2 === 0 ? "full" : "roomy", { case: "warmup", delayMs: 0, rep });

  for (const delayMs of DELAYS) {
    await simulateDelay(delayMs);
    for (let rep = 0; rep < WARM_REPS; rep++)
      for (const target of ["roomy", "full"] as const) await drag(page, plan, warm.date, warm.moving, target, { case: "warm", delayMs, rep });
  }
  await simulateDelay(0);

  let rep = 0;
  for (const day of plan.days.filter(item => item.kind !== "warm")) {
    await openWeek(page, plan, day.date);
    await drag(page, plan, day.date, day.moving, day.kind === "cold-feasible" ? "roomy" : "full", { case: "cold", delayMs: 0, rep: rep++ });
  }
  await context.close();

  for (const level of CONCURRENCY) {
    const pages = await Promise.all(Array.from({ length: level }, () => newPage(browser, baseURL, plan)));
    for (const item of pages) await openWeek(item.page, plan, warm.date);
    for (let round = 0; round < ROUNDS; round++)
      await Promise.all(pages.map(item => drag(item.page, plan, warm.date, warm.moving, "roomy", { case: `concurrency-${level}`, delayMs: 0, rep: round })));
    for (const item of pages) await item.context.close();
  }
});
