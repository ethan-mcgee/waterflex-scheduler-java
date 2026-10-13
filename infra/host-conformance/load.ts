// API load tool: drives a scheduler over its public API as concurrent host booking desks and daily planners, and
// writes caller-side latency, outcomes and (with --metrics-url) the scheduler's own view as JSON and markdown.
//   npx tsx load.ts --url http://127.0.0.1:8000 --token-file ./token --metro metro-omaha --time-zone America/Chicago \
//     --area 41.22,-96.05,41.28,-95.95 --scenario booking --concurrency 5 --duration-s 60
// Use a dedicated tenant: the run books and confirms jobs under its own IDs on dates two days ahead and later.
import { randomUUID } from "node:crypto";
import { mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { cpus, totalmem } from "node:os";
import { join, resolve } from "node:path";
import { parseArgs } from "node:util";
import { Id } from "./src/api.ts";
import { SchedulerApi } from "./src/client.ts";
import { HostStore, type MetroShape } from "./src/load/host.ts";
import { scrape, serverView, type Sample, type ServerView } from "./src/load/metrics.ts";
import { loadMarkdown, type LoadReport } from "./src/load/report.ts";
import { bookingLoop, dailyLoop, newTallies, sampleContainers, sampleMetrics, seeded, type ContainerUsage, type Run } from "./src/load/scenarios.ts";
import { summarize } from "./src/load/stats.ts";
import { AreaPoints, FIXTURE_POINTS, parseArea, type Point } from "./src/points.ts";
import { KIT_POLICY, KIT_RATES } from "./src/snapshot.ts";
import { addDays, requireTimeZone, todayIn } from "./src/time.ts";

const USAGE = `Usage: npx tsx load.ts --url <base URL> --token-file <file> --metro <metroId> --time-zone <IANA zone>
    (--area <south,west,north,east> | --points fixture) --scenario booking|daily|mixed --duration-s <seconds>
    [--concurrency 5] [--daily-concurrency 1] [--technicians 10] [--appointments 2] [--horizon-days 5]
    [--daily-technicians 20] [--jobs cold|warm] [--confirm-rate 0.3] [--offer-limit 4] [--window-hours 2]
    [--deadline-ms 5000] [--cost-seconds 5] [--fairness-seconds 0] [--seed 1] [--metrics-url <prometheus URL>]
    [--docker <container,...>] [--label <text>] [--out <directory>]`;

class UsageError extends Error { }

function oneOf<T extends string>(value: string, allowed: readonly T[], option: string): T {
  const found = allowed.find(item => item === value);
  if (found === undefined) throw new UsageError(`${option} is one of ${allowed.join(", ")}`);
  return found;
}

const STRING_OPTIONS = ["url", "token-file", "metro", "time-zone", "area", "points", "scenario", "duration-s", "concurrency", "daily-concurrency",
  "technicians", "appointments", "horizon-days", "daily-technicians", "jobs", "confirm-rate", "offer-limit", "window-hours", "deadline-ms",
  "cost-seconds", "fairness-seconds", "seed", "metrics-url", "docker", "label", "out"] as const;

function settings() {
  let values: Record<string, string | boolean | undefined>;
  try {
    values = parseArgs({ strict: true, allowPositionals: false,
      options: { ...Object.fromEntries(STRING_OPTIONS.map(name => [name, { type: "string" as const }])), help: { type: "boolean" } } }).values;
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
  const scenario = oneOf(required("scenario"), ["booking", "daily", "mixed"] as const, "--scenario");
  const jobs = oneOf(text("jobs") ?? "cold", ["cold", "warm"] as const, "--jobs");
  const confirmRate = Number(text("confirm-rate") ?? "0.3");
  if (!(confirmRate >= 0 && confirmRate <= 1)) throw new UsageError("--confirm-rate is a share from 0 to 1");
  const metroId = required("metro");
  if (!Id.safeParse(metroId).success) throw new UsageError("--metro is not a valid ID");
  if ((text("area") === undefined) === (text("points") === undefined)) throw new UsageError("Give exactly one of --area and --points fixture");
  if (text("points") !== undefined && text("points") !== "fixture") throw new UsageError("--points takes only the value fixture");
  let token: string;
  try { token = readFileSync(required("token-file"), "utf8").trim(); } catch (error) {
    if (error instanceof UsageError) throw error;
    throw new UsageError(`Cannot read --token-file: ${error instanceof Error ? error.message : String(error)}`);
  }
  const costSeconds = integer("cost-seconds", KIT_POLICY.costSearchSeconds, 5, 240);
  const fairnessSeconds = integer("fairness-seconds", KIT_POLICY.fairnessSearchSeconds, 0, 240);
  if (costSeconds + fairnessSeconds > 240) throw new UsageError("--cost-seconds and --fairness-seconds together are at most 240");
  return {
    api: new SchedulerApi(required("url"), token), metroId, timeZone: requireTimeZone(required("time-zone")),
    area: text("area") === undefined ? null : parseArea(required("area")), scenario,
    durationS: (() => { required("duration-s"); return integer("duration-s", 0, 5, 3600); })(),
    concurrency: integer("concurrency", 5, 1, 64), dailyConcurrency: integer("daily-concurrency", 1, 1, 16),
    technicians: integer("technicians", 10, 1, 200), appointments: integer("appointments", 2, 0, 4), horizonDays: integer("horizon-days", 5, 1, 21),
    dailyTechnicians: integer("daily-technicians", 20, 1, 200), jobs, confirmRate,
    offerLimit: integer("offer-limit", 4, 1, 4), windowHours: integer("window-hours", 2, 1, 8),
    deadlineMs: integer("deadline-ms", KIT_POLICY.bookingDeadlineMs, 1000, 5000), costSeconds, fairnessSeconds, seed: integer("seed", 1, 0, 1000),
    metricsUrl: text("metrics-url") ?? null, docker: text("docker")?.split(",").map(name => name.trim()).filter(name => name !== "") ?? [],
    label: text("label") ?? null, out: resolve(text("out") ?? "load-report"),
  };
}

/** Distinct area points, or the fixture router's points in turn. */
function pointSource(area: ReturnType<typeof settings>["area"], seed: number): () => Point {
  if (area !== null) {
    // A million points per seed: runs with different seeds never share a location, so a cold run is cold.
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

async function main(): Promise<number> {
  let options;
  try { options = settings(); } catch (error) {
    console.error(error instanceof Error ? error.message : String(error));
    console.error(USAGE);
    return 2;
  }
  const prefix = `load-${randomUUID().slice(0, 8)}`;
  const serviceId = `${prefix}-service`;
  const today = todayIn(options.timeZone);
  const horizon = Array.from({ length: options.horizonDays }, (_, offset) => addDays(today, 2 + offset));
  const dailyDate = addDays(today, 2);
  const point = pointSource(options.area, options.seed);
  const policy = { ...KIT_POLICY, bookingDeadlineMs: options.deadlineMs, costSearchSeconds: options.costSeconds, fairnessSearchSeconds: options.fairnessSeconds };
  const shape = (name: string, technicians: number, dates: readonly string[]): MetroShape => ({ metroId: options.metroId, timeZone: options.timeZone, serviceId,
    prefix: `${prefix}-${name}`, technicians, appointmentsPerDay: options.appointments, dates, rates: KIT_RATES, policy });
  const bookingStore = new HostStore(shape("book", options.technicians, horizon), point, point);
  const dailyStore = new HostStore(shape("daily", options.dailyTechnicians, [dailyDate]), point, point);
  const warm = Array.from({ length: 8 }, () => point());
  let warmIndex = 0;
  const jobLocation = options.jobs === "cold" ? point : () => warm[warmIndex++ % warm.length] ?? point();

  const before: Sample[] | null = options.metricsUrl === null ? null : await scrape(options.metricsUrl);
  const metrics = options.metricsUrl === null ? null : sampleMetrics(options.metricsUrl, 2000);
  const containers = options.docker.length === 0 ? null : sampleContainers(options.docker, 5000);
  const startedAt = new Date();
  const tallies = newTallies();
  const run: Run = { api: options.api, prefix, serviceId, endsAt: startedAt.getTime() + options.durationS * 1000, random: seeded(options.seed),
    records: [], booking: tallies.booking, daily: tallies.daily, contractBreaches: [] };
  console.log(`Load run ${prefix}: ${options.scenario} for ${options.durationS} s against ${options.api.base}`);
  const loops: Promise<void>[] = [];
  if (options.scenario !== "daily")
    for (let desk = 1; desk <= options.concurrency; desk++)
      loops.push(bookingLoop(run, { store: bookingStore, horizon, offerLimit: options.offerLimit, windowHours: options.windowHours, jobMinutes: 60,
        confirmRate: options.confirmRate, jobLocation }, desk, options.deadlineMs));
  if (options.scenario !== "booking")
    for (let planner = 0; planner < (options.scenario === "daily" ? options.concurrency : options.dailyConcurrency); planner++)
      loops.push(dailyLoop(run, { store: dailyStore, date: dailyDate }, (options.costSeconds + options.fairnessSeconds + 5) * 1000 + 30_000));
  await Promise.all(loops);
  const finishedAt = new Date();
  let server: ServerView | null = null;
  let metricFailures = 0;
  if (metrics !== null && before !== null && options.metricsUrl !== null) {
    const sampled = await metrics.stop();
    metricFailures = sampled.failures;
    server = serverView(before, await scrape(options.metricsUrl), sampled.samples);
  }
  const usage: ContainerUsage[] | null = containers === null ? null : await containers.stop();
  const report: LoadReport = {
    label: options.label, scenario: options.scenario, scheduler: options.api.base, metroId: options.metroId, timeZone: options.timeZone,
    points: options.area === null ? "fixture" : `area ${options.area.south},${options.area.west},${options.area.north},${options.area.east}`,
    run: prefix, startedAt: startedAt.toISOString(), finishedAt: finishedAt.toISOString(),
    settings: { durationS: options.durationS, concurrency: options.concurrency, dailyConcurrency: options.scenario === "mixed" ? options.dailyConcurrency : null,
      technicians: options.technicians, appointmentsPerDay: options.appointments, horizonDates: horizon.length, dailyTechnicians: options.dailyTechnicians,
      jobs: options.jobs, confirmRate: options.confirmRate, offerLimit: options.offerLimit, windowHours: options.windowHours, policy, seed: options.seed },
    machine: { cpus: cpus().length, cpuModel: cpus()[0]?.model ?? "unknown", memoryBytes: totalmem(), node: process.version },
    operations: summarize(run.records), booking: options.scenario === "daily" ? null : run.booking,
    daily: options.scenario === "booking" ? null : run.daily, server, metricFailures, containers: usage,
    contractBreaches: run.contractBreaches, hostAnomalies: [...bookingStore.anomalies, ...dailyStore.anomalies],
  };
  mkdirSync(options.out, { recursive: true });
  const name = `load-${options.scenario}-${startedAt.toISOString().replace(/[:.]/g, "-")}`;
  writeFileSync(join(options.out, `${name}.json`), `${JSON.stringify(report, null, 2)}\n`);
  writeFileSync(join(options.out, `${name}.md`), loadMarkdown(report));
  console.log(loadMarkdown(report));
  return report.contractBreaches.length === 0 && report.hostAnomalies.length === 0 ? 0 : 1;
}

main().then(code => { process.exitCode = code; }, (error: unknown) => {
  console.error(error);
  process.exitCode = 1;
});
