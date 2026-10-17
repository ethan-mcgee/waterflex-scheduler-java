// Offer quality by window threshold and refinement cap: builds one host metro and optimizes every date with daily
// proposals, as the overnight run would. Each case is then a new job address, searched once per --variants value on the same records
// (in a rotated order, so the route cache warmed by the first search favours none). The best offer is confirmed and
// priced with /api/v1/routes/evaluate against the day before the booking; the host writes nothing, and stamps the
// confirmed days as written and cancelled, so the next search's records are newer than the confirm's.
//   npx tsx quality.ts --url http://127.0.0.1:8000 --token-file ./token --metro metro-omaha --time-zone America/Chicago \
//     --area 41.22,-96.05,41.28,-95.95 --variants 2,2:1000,1000 --cases 30
// Use a dedicated tenant: it confirms jobs under its own IDs on dates two days ahead and later.
import { randomUUID } from "node:crypto";
import { mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { cpus } from "node:os";
import { join, resolve } from "node:path";
import { parseArgs } from "node:util";
import type { z } from "zod";
import * as Api from "./src/api.ts";
import { SchedulerApi, type Reply } from "./src/client.ts";
import { dynamicHorizon, isWeekday, recordDates, weekdayWindow } from "./src/load/horizon.ts";
import { HostStore, type Job, type MetroShape } from "./src/load/host.ts";
import { disturbance, parseVariants, qualityLines, withReceipt, type OfferedWindow, type QualityCase, type Searched, type Variant } from "./src/load/quality.ts";
import { retryAfterMs } from "./src/load/scenarios.ts";
import { breakdownLines, containerShareLines, readRequestLog, serverBreakdown, tenantOf, type ContainerShare, type ServerBreakdown } from "./src/load/serverlog.ts";
import { AreaPoints, FIXTURE_POINTS, parseArea, type Point } from "./src/points.ts";
import { KIT_POLICY, KIT_RATES, type Snapshot } from "./src/snapshot.ts";
import { addDays, requireTimeZone, todayIn } from "./src/time.ts";

const USAGE = `Usage: npx tsx quality.ts --url <base URL> --token-file <file> --metro <metroId> --time-zone <IANA zone>
    (--area <south,west,north,east> | --points fixture) [--variants 2,1000 | --thresholds 2,1000] [--cases 30] [--technicians 3]
    [--appointments 2] [--horizon fixed|dynamic] [--horizon-weekdays 10] [--lead-weekdays 2] [--full-percent 85]
    [--booked-weekdays 0] [--booked-appointments 4] [--offer-limit 4] [--window-hours 2] [--deadline-ms 5000]
    [--seed 1] [--scheduler-log <container,...>] [--label <text>] [--out <directory>]
  --scheduler-log reads the named scheduler containers' request log for the run (docker logs) and reports the server's
  own attribution of each variant's searches: slot wait, routing, engine, database, commit, pairs and CPU.
  A variant is a regularWindowThreshold, optionally with a bookingRefinementMs cap: 2:500 is threshold 2 with a 500 ms
  cap, and 2 alone leaves the scheduler's configured cap. --thresholds lists variants without caps. The first variant
  is the baseline every other is compared with on the same cases.`;

class UsageError extends Error { }

const STRING_OPTIONS = ["url", "token-file", "metro", "time-zone", "area", "points", "thresholds", "variants", "cases", "technicians", "appointments",
  "horizon", "horizon-weekdays", "lead-weekdays", "full-percent", "booked-weekdays", "booked-appointments", "offer-limit", "window-hours",
  "deadline-ms", "seed", "scheduler-log", "label", "out"] as const;

function settings() {
  let values: Record<string, string | boolean | undefined>;
  try {
    values = parseArgs({ strict: true, allowPositionals: false, options: { ...Object.fromEntries(STRING_OPTIONS.map(name => [name, { type: "string" as const }])),
      help: { type: "boolean" as const } } }).values;
  } catch (error) {
    throw new UsageError(error instanceof Error ? error.message : String(error));
  }
  if (values.help === true) { console.log(USAGE); process.exit(0); }
  const text = (name: string): string | undefined => {
    const value = values[name];
    return typeof value === "string" && value.trim() !== "" ? value.trim() : undefined;
  };
  const required = (name: string): string => text(name) ?? (() => { throw new UsageError(`--${name} is required`); })();
  const integer = (name: string, fallback: number, min: number, max: number): number => {
    const raw = text(name);
    const value = raw === undefined ? fallback : Number(raw);
    if (!Number.isInteger(value) || value < min || value > max) throw new UsageError(`--${name} must be a whole number from ${min} to ${max}`);
    return value;
  };
  if (text("thresholds") !== undefined && text("variants") !== undefined) throw new UsageError("Give --variants or --thresholds, not both");
  const listed = text("variants") ?? text("thresholds") ?? "2,1000";
  if (text("thresholds") !== undefined && listed.includes(":")) throw new UsageError("--thresholds takes no refinement caps; use --variants");
  let variants: Variant[];
  try { variants = parseVariants(listed); } catch (error) { throw new UsageError(error instanceof Error ? error.message : String(error)); }
  const metroId = required("metro");
  if (!Api.Id.safeParse(metroId).success) throw new UsageError("--metro is not a valid ID");
  if ((text("area") === undefined) === (text("points") === undefined)) throw new UsageError("Give exactly one of --area and --points fixture");
  if (text("points") !== undefined && text("points") !== "fixture") throw new UsageError("--points takes only the value fixture");
  const horizonMode = text("horizon") ?? "fixed";
  if (horizonMode !== "fixed" && horizonMode !== "dynamic") throw new UsageError("--horizon is fixed or dynamic");
  if (horizonMode === "fixed" && (text("lead-weekdays") !== undefined || text("full-percent") !== undefined))
    throw new UsageError("--lead-weekdays and --full-percent apply only with --horizon dynamic");
  let token: string;
  try { token = readFileSync(required("token-file"), "utf8").trim(); } catch (error) {
    if (error instanceof UsageError) throw error;
    throw new UsageError(`Cannot read --token-file: ${error instanceof Error ? error.message : String(error)}`);
  }
  return {
    api: new SchedulerApi(required("url"), token), metroId, timeZone: requireTimeZone(required("time-zone")),
    area: text("area") === undefined ? null : parseArea(required("area")), variants, cases: integer("cases", 30, 1, 500),
    technicians: integer("technicians", 3, 1, 200), appointments: integer("appointments", 2, 0, 4),
    horizonMode, horizonWeekdays: integer("horizon-weekdays", horizonMode === "dynamic" ? 15 : 10, 1, 15),
    leadWeekdays: integer("lead-weekdays", 2, 1, 5), fullPercent: integer("full-percent", 85, 50, 100),
    bookedWeekdays: integer("booked-weekdays", 0, 0, 15), bookedAppointments: integer("booked-appointments", 4, 0, 4),
    offerLimit: integer("offer-limit", 4, 1, 4), windowHours: integer("window-hours", 2, 1, 8),
    deadlineMs: integer("deadline-ms", KIT_POLICY.bookingDeadlineMs, 1000, 5000), seed: integer("seed", 1, 0, 1000),
    schedulerLog: text("scheduler-log")?.split(",").map(name => name.trim()).filter(name => name !== "") ?? [],
    label: text("label") ?? null, out: resolve(text("out") ?? "quality-report"),
  };
}

/** Distinct area points (a million per seed, so runs with different seeds share none), or the fixture router's in turn. */
function pointSource(area: ReturnType<typeof settings>["area"], seed: number): () => Point {
  if (area !== null) {
    const stream = new AreaPoints(area, seed * 1_000_000);
    return () => stream.next();
  }
  let index = 0;
  return () => {
    const next = FIXTURE_POINTS[index++ % FIXTURE_POINTS.length];
    if (next === undefined) throw new Error("The fixture point list is empty");
    return next;
  };
}

const sleep = (ms: number) => new Promise(resolve => setTimeout(resolve, ms));

/** One server-time table per variant, the booking offers rows only: what each setting cost the scheduler. */
function variantBreakdownLines(attributed: Record<string, ServerBreakdown>): string[] {
  const lines = ["## Server time by variant (request log, booking offers)", ""];
  for (const [variant, breakdown] of Object.entries(attributed)) {
    const offers = Object.fromEntries(Object.entries(breakdown).filter(([key]) => key.startsWith("POST /api/v1/booking/offers ")));
    lines.push(...breakdownLines(offers, `### Variant ${variant}`), "");
  }
  return lines;
}

/** Calls, repeating a 429 BUSY with the same body after Retry-After as a host would; null when no answer came. */
async function call(api: SchedulerApi, path: string, body: object, timeoutMs: number): Promise<Reply | null> {
  for (let attempt = 0; attempt < 120; attempt++) {
    let reply: Reply;
    try { reply = await api.call("POST", path, body, { timeoutMs }); } catch { return null; }
    if (reply.status !== 429) return reply;
    await sleep(retryAfterMs(reply));
  }
  return null;
}

function parsed<T extends z.ZodType>(breaches: string[], reply: Reply, schema: T, what: string): z.infer<T> | null {
  const result = schema.safeParse(reply.json);
  if (result.success) return result.data;
  breaches.push(`${what}: ${result.error.issues.map(issue => `${issue.path.join(".")} ${issue.message}`).join("; ")}`);
  return null;
}

async function main(): Promise<number> {
  let options;
  try { options = settings(); } catch (error) {
    console.error(error instanceof Error ? error.message : String(error));
    console.error(USAGE);
    return 2;
  }
  const o = options;
  const prefix = `quality-${randomUUID().slice(0, 8)}`;
  const serviceId = `${prefix}-service`;
  const firstDate = addDays(todayIn(o.timeZone), 2);
  const window = weekdayWindow(firstDate, o.horizonWeekdays);
  const records = recordDates(firstDate, o.horizonWeekdays, o.bookedWeekdays);
  const booked = new Set(records.filter(isWeekday).slice(0, o.bookedWeekdays));
  const area = o.area;
  const point = pointSource(area, o.seed);
  const basePolicy = { ...KIT_POLICY, bookingDeadlineMs: o.deadlineMs };
  const timeoutMs = o.deadlineMs + 10_000;
  const breaches: string[] = [];
  const cases: QualityCase[] = [];
  const startedAt = new Date();
  const tenant = o.schedulerLog.length === 0 ? null : await tenantOf(o.api);
  console.log(`Quality run ${prefix}: ${o.cases} cases, variants ${o.variants.map(item => item.name).join(", ")}, against ${o.api.base}`);

  // One metro, optimized first as the overnight run leaves a schedule, so a booking is priced against a day that has no
  // slack of its own to give up; without this the refinement is credited with savings the overnight run would make.
  const shape: MetroShape = { metroId: o.metroId, timeZone: o.timeZone, serviceId, prefix, technicians: o.technicians,
    appointmentsPerDay: o.appointments, bookedDates: booked, bookedAppointments: o.bookedAppointments, dates: records, rates: KIT_RATES, policy: basePolicy };
  const store = new HostStore(shape, point, point);
  const optimized: Array<{ serviceDate: string; decision: string; costCents: number }> = [];
  for (const serviceDate of records) {
    const proposed = await call(o.api, "/api/v1/daily/proposals", { requestId: randomUUID(), serviceDate, snapshot: store.snapshot([serviceDate]) }, 60_000);
    const proposal = proposed?.status === 201 ? parsed(breaches, proposed, Api.DailyProposal, "Daily proposal") : null;
    if (proposal === null) throw new Error(`The daily proposal for ${serviceDate} answered ${proposed?.status ?? "nothing"}`);
    optimized.push({ serviceDate, decision: proposal.decision, costCents: proposal.costCents });
    if (proposal.decision !== "IMPROVED") continue;
    const versions = store.snapshot([serviceDate]).technicianDays.map(day => ({ technicianId: day.technicianId, serviceDate: day.serviceDate, lastModified: day.lastModified }));
    const committed = await call(o.api, `/api/v1/daily/proposals/${encodeURIComponent(proposal.proposalId)}/commit`, { requestId: randomUUID(), technicianDays: versions }, 30_000);
    const receipt = committed?.status === 200 ? parsed(breaches, committed, Api.CommitReceipt, "Commit") : null;
    if (receipt === null || store.write(receipt) !== "WRITTEN") throw new Error(`Committing the proposal for ${serviceDate} answered ${committed?.status ?? "nothing"}`);
  }
  console.log(`Optimized ${optimized.filter(day => day.decision === "IMPROVED").length} of ${records.length} dates`);
  const dates = o.horizonMode === "dynamic"
    ? dynamicHorizon(firstDate, store.snapshot(window), { capWeekdays: o.horizonWeekdays, leadWeekdays: o.leadWeekdays, fullPercent: o.fullPercent }).dates
    : window;
  const first = dates[0], last = dates[dates.length - 1];
  if (first === undefined || last === undefined) throw new Error("Empty booking horizon");

  for (let index = 1; index <= o.cases; index++) {
    // Each case is a new job address on the same schedule, searched once per variant in a rotated order.
    const location = point();
    const order = o.variants.map((_, n) => o.variants[(n + index - 1) % o.variants.length] ?? (() => { throw new Error("No variants"); })());
    const searched = new Map<string, Searched>();
    for (const variant of order) {
      const { name, threshold, refinementMs } = variant;
      const policy = { ...basePolicy, regularWindowThreshold: threshold, ...(refinementMs === null ? {} : { bookingRefinementMs: refinementMs }) };
      const snapshot = (days: readonly string[]): Snapshot => ({ ...store.snapshot(days), policy });
      const job: Job = { id: `${prefix}-job-${index}-t${threshold}${refinementMs === null ? "" : `r${refinementMs}`}`, serviceId, durationMinutes: 60, location };
      const started = performance.now();
      const requestId = randomUUID();
      const reply = await call(o.api, "/api/v1/booking/offers", { requestId, job, horizon: { firstDate: first, lastDate: last },
        offerLimit: o.offerLimit, windowHours: o.windowHours, snapshot: snapshot(dates) }, timeoutMs);
      const ms = Math.round(performance.now() - started);
      const set = reply?.status === 201 ? parsed(breaches, reply, Api.OfferSet, "Booking offers") : null;
      const empty = { variant: name, threshold, refinementMs, requestId, status: reply?.status ?? null, ms: null, refinement: null, refinementStop: null, regularWindowsFound: null, searchComplete: null, incompleteDates: null, offers: [], priced: null };
      if (set === null) { searched.set(name, { ...empty, pricingFailure: reply === null ? "no answer" : `search answered ${reply.status}` }); continue; }
      const offers: OfferedWindow[] = set.offers.map(offer => ({ serviceDate: offer.serviceDate, start: offer.window.start, end: offer.window.end }));
      const result = { variant: name, threshold, refinementMs, requestId, status: 201, ms, refinement: set.refinement ?? null, refinementStop: set.refinementStop ?? null,
        regularWindowsFound: set.regularWindowsFound ?? null, searchComplete: set.searchComplete,
        incompleteDates: set.incompleteDates?.length ?? null, offers };
      const best = set.offers[0];
      if (best === undefined) { searched.set(name, { ...result, priced: null, pricingFailure: null }); continue; }
      const failed = (why: string) => searched.set(name, { ...result, priced: null, pricingFailure: why });
      const selected = await call(o.api, `/api/v1/booking/offers/${encodeURIComponent(best.offerId)}/select`, { requestId: randomUUID() }, 10_000);
      const hold = selected?.status === 200 ? parsed(breaches, selected, Api.Hold, "Select") : null;
      if (hold === null) { failed(`select answered ${selected?.status ?? "nothing"}`); continue; }
      const day = snapshot([best.serviceDate]);
      const confirmed = await call(o.api, `/api/v1/booking/holds/${encodeURIComponent(hold.holdId)}/confirm`, { requestId: randomUUID(), snapshot: day }, timeoutMs);
      const receipt = confirmed?.status === 200 ? parsed(breaches, confirmed, Api.CommitReceipt, "Confirm") : null;
      if (receipt === null) {
        failed(`confirm answered ${confirmed?.status ?? "nothing"}`);
        await call(o.api, `/api/v1/booking/offers/${encodeURIComponent(best.offerId)}/release`, { requestId: randomUUID() }, 10_000);
        continue;
      }
      // The host writes nothing, but its records of the confirmed days now read as written and cancelled.
      store.touch(receipt.technicianDays);
      const after = withReceipt(day, receipt, job, best.window);
      const evaluate = async (facts: Snapshot) => {
        const timed = await call(o.api, "/api/v1/routes/evaluate", { serviceDate: best.serviceDate, snapshot: facts }, 30_000);
        const evaluation = timed?.status === 200 ? parsed(breaches, timed, Api.RouteEvaluation, "Route evaluation") : null;
        return evaluation === null ? `evaluate answered ${timed?.status ?? "nothing"}` : !evaluation.feasible ? "the day did not evaluate as feasible" : evaluation.costCents;
      };
      const was = await evaluate(day);
      if (typeof was === "string") { failed(`before: ${was}`); continue; }
      const now = await evaluate(after);
      if (typeof now === "string") { failed(`after: ${now}`); continue; }
      searched.set(name, { ...result, priced: { beforeCostCents: was, afterCostCents: now, addedCostCents: now - was, ...disturbance(day, after, job.id) }, pricingFailure: null });
    }
    const inListOrder = o.variants.map(({ name }) => searched.get(name) ?? (() => { throw new Error(`Variant ${name} was not searched`); })());
    cases.push({ index, firstDate: first, dates: dates.length, searched: inListOrder, order: order.map(item => item.name) });
    const line = inListOrder.map(s => `${s.variant}: ${s.offers.length} offers, ${s.refinement ?? s.status}, ` +
      `${s.priced === null ? s.pricingFailure ?? "not priced" : `adds ${(s.priced.addedCostCents / 100).toFixed(2)}`}`).join("; ");
    console.log(`[${index}/${o.cases}] ${dates.length} dates; ${line}`);
  }

  const finishedAt = new Date();
  // The server's attribution per variant: the log lines of that variant's searches, by their request IDs.
  let attributed: Record<string, ServerBreakdown> | null = null;
  let serverLog: ContainerShare[] | null = null;
  if (tenant !== null) {
    const log = await readRequestLog(o.schedulerLog, startedAt, tenant);
    serverLog = [...log.containers];
    attributed = {};
    for (const variant of o.variants) {
      const ids = new Set(cases.flatMap(item => item.searched.filter(entry => entry.variant === variant.name).map(entry => entry.requestId)));
      attributed[variant.name] = serverBreakdown(log.lines, { tenant, requestIds: ids });
    }
  }
  const report = { label: o.label, scheduler: o.api.base, metroId: o.metroId, timeZone: o.timeZone, run: prefix,
    startedAt: startedAt.toISOString(), finishedAt: finishedAt.toISOString(),
    settings: { variants: o.variants, cases: o.cases, technicians: o.technicians, appointmentsPerDay: o.appointments,
      horizon: { mode: o.horizonMode, weekdays: o.horizonWeekdays, leadWeekdays: o.horizonMode === "dynamic" ? o.leadWeekdays : null,
        fullPercent: o.horizonMode === "dynamic" ? o.fullPercent : null, bookedWeekdays: o.bookedWeekdays, bookedAppointments: o.bookedWeekdays === 0 ? null : o.bookedAppointments },
      offerLimit: o.offerLimit, windowHours: o.windowHours, policy: basePolicy, seed: o.seed,
      points: area === null ? "fixture" : `area ${area.south},${area.west},${area.north},${area.east}` },
    machine: { cpus: cpus().length, cpuModel: cpus()[0]?.model ?? "unknown", node: process.version },
    optimized, cases, serverBreakdown: attributed, serverLog, contractBreaches: breaches };
  const lines = [`# Quality run ${prefix}${o.label === null ? "" : `: ${o.label}`}`, "",
    `- ${o.cases} cases of ${o.technicians} technicians x ${o.appointments} visits a day${o.bookedWeekdays === 0 ? "" : ` (${o.bookedAppointments} on the first ${o.bookedWeekdays} weekdays)`}; ` +
      `horizon ${o.horizonMode} ${o.horizonWeekdays} weekdays; offerLimit ${o.offerLimit}, windowHours ${o.windowHours}, bookingDeadlineMs ${o.deadlineMs}`,
    `- Ran ${((finishedAt.getTime() - startedAt.getTime()) / 1000).toFixed(0)} s against ${o.api.base}`, "", ...qualityLines(o.variants.map(item => item.name), cases),
    ...(attributed === null ? [] : ["", ...variantBreakdownLines(attributed)]),
    ...(serverLog === null ? [] : ["", ...containerShareLines(serverLog)]),
    ...(breaches.length === 0 ? [] : ["", "## Defects found", "", ...breaches.map(item => `- Contract: ${item}`)])];
  mkdirSync(o.out, { recursive: true });
  const name = `quality-${startedAt.toISOString().replace(/[:.]/g, "-")}`;
  writeFileSync(join(o.out, `${name}.json`), `${JSON.stringify(report, null, 2)}\n`);
  writeFileSync(join(o.out, `${name}.md`), `${lines.join("\n")}\n`);
  console.log(lines.join("\n"));
  return breaches.length === 0 ? 0 : 1;
}

main().then(code => { process.exitCode = code; }, (error: unknown) => {
  console.error(error);
  process.exitCode = 1;
});
