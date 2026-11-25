// Drive-time load tool: dispatchers calling POST /api/v1/routes/geometry and /routes/insertions over the public API, as
// a dispatch screen does around a drag and drop (src/load/routes.ts), writing every call and per-operation latency as
// JSON and markdown. Nothing is stored by these calls, so any tenant serves; a dedicated one keeps its log lines apart.
//   npx tsx routes-load.ts --url https://<vm> --token-env SCHEDULER_STUDY_TOKEN --metro metro-paso-robles \
//     --time-zone America/Los_Angeles --request-file day.json --mode geometry --callers 1 --moves 200
import { readFileSync, mkdirSync, writeFileSync } from "node:fs";
import { cpus } from "node:os";
import { join, resolve } from "node:path";
import { parseArgs } from "node:util";
import { Id } from "./src/api.ts";
import { SchedulerApi } from "./src/client.ts";
import { addDays, requireTimeZone, todayIn } from "./src/time.ts";
import { isWeekday } from "./src/load/horizon.ts";
import { bounds, closedLoop, DaySource, newRouteRun, pacedLoop, parseRouteDay, PLACEMENTS, ROUTE_MODES, type MoveSettings } from "./src/load/routes.ts";
import { routeMarkdown, routeOperations, type RouteReport } from "./src/load/routesreport.ts";
import { AreaPoints, parseArea, parsePointPool, PointPool } from "./src/points.ts";
import { KIT_POLICY, KIT_RATES } from "./src/snapshot.ts";
import { readToken, TOKEN_USAGE } from "./src/token.ts";

const USAGE = `Usage: npx tsx routes-load.ts --url <base URL> ${TOKEN_USAGE} --metro <metroId> --time-zone <IANA zone>
    --mode geometry|insertions|session (--request-file <geometry request JSON> | (--area <south,west,north,east> | --points-file <JSON>) --technicians <n>)
    [--appointments 4] [--placement fresh-job|fresh-day|repeat] [--callers 1] [--pace-ms <ms>] [--max-outstanding 4]
    [--duration-s 300] [--moves <count>] [--timeout-ms 30000] [--seed 1] [--label <text>] [--out <directory>]
  A move is one geometry call, one insertions call, or a session (open with geometry, pick with insertions and timed
  stops, drop with geometry of the day holding the job). Without --pace-ms each dispatcher moves again as soon as
  answered; with it, each moves every --pace-ms on a fixed clock whatever the answers (callers start spread over one
  pace), skipping a tick while --max-outstanding moves are open. The run ends at --duration-s or after --moves moves.
  --request-file sends a recorded day (fresh-job and repeat only); --technicians generates days with --appointments visits
  each on the next weekday two days out, at points from --points-file (known-routable locations, {"points": [{lat, lng}]})
  or spread over --area. Jobs come from --points-file when given, else from the area or the recorded day's own spread.
  Calls are never retried: 429 and 503 are counted.`;

class UsageError extends Error { }

function oneOf<T extends string>(value: string, allowed: readonly T[], option: string): T {
  const found = allowed.find(item => item === value);
  if (found === undefined) throw new UsageError(`${option} is one of ${allowed.join(", ")}`);
  return found;
}

const STRING_OPTIONS = ["url", "token-file", "token-env", "metro", "time-zone", "mode", "request-file", "area", "points-file", "technicians", "appointments", "placement",
  "callers", "pace-ms", "max-outstanding", "duration-s", "moves", "timeout-ms", "seed", "label", "out"] as const;

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
  const integer = (name: string, fallback: number | null, min: number, max: number): number | null => {
    const raw = text(name);
    if (raw === undefined) return fallback;
    const value = Number(raw);
    if (!Number.isInteger(value) || value < min || value > max) throw new UsageError(`--${name} must be a whole number from ${min} to ${max}`);
    return value;
  };
  const given = (name: string, fallback: number, min: number, max: number): number => integer(name, fallback, min, max) ?? fallback;
  const metroId = required("metro");
  if (!Id.safeParse(metroId).success) throw new UsageError("--metro is not a valid ID");
  const requestFile = text("request-file"), pointsFile = text("points-file");
  if (requestFile !== undefined) {
    if (text("area") !== undefined) throw new UsageError("A recorded day takes its jobs from --points-file or its own spread, not --area");
    if (text("technicians") !== undefined) throw new UsageError("--technicians generates days; a recorded day has its own");
  } else {
    if ((text("area") === undefined) === (pointsFile === undefined)) throw new UsageError("Give --request-file, or exactly one of --area and --points-file");
    if (text("technicians") === undefined) throw new UsageError("A generated day needs --technicians");
  }
  const placement = oneOf(text("placement") ?? "fresh-job", PLACEMENTS, "--placement");
  if (requestFile !== undefined && placement === "fresh-day") throw new UsageError("A recorded day cannot be sent fresh; use fresh-job or repeat");
  let token: string;
  try { token = readToken(text("token-file"), text("token-env")); } catch (error) {
    throw new UsageError(error instanceof Error ? error.message : String(error));
  }
  return {
    api: new SchedulerApi(required("url"), token), metroId, timeZone: requireTimeZone(required("time-zone")),
    mode: oneOf(required("mode"), ROUTE_MODES, "--mode"), placement, requestFile: requestFile ?? null, pointsFile: pointsFile ?? null,
    area: text("area") === undefined ? null : parseArea(required("area")),
    technicians: given("technicians", 1, 1, 200), appointments: given("appointments", 4, 0, 4),
    callers: given("callers", 1, 1, 64), paceMs: integer("pace-ms", null, 100, 600_000), maxOutstanding: given("max-outstanding", 4, 1, 64),
    durationS: given("duration-s", 300, 5, 3600), maxMoves: integer("moves", null, 1, 100_000), timeoutMs: given("timeout-ms", 30_000, 1000, 120_000),
    seed: given("seed", 1, 0, 1000), label: text("label") ?? null, out: resolve(text("out") ?? "routes-report"),
  };
}

/** The first weekday at least two days after today in the zone: a date a dispatcher would be arranging. */
function workDate(timeZone: string): string {
  let date = addDays(todayIn(timeZone), 2);
  while (!isWeekday(date)) date = addDays(date, 1);
  return date;
}

async function main(): Promise<number> {
  let options;
  try { options = settings(); } catch (error) {
    console.error(error instanceof Error ? error.message : String(error));
    console.error(USAGE);
    return 2;
  }
  let source: DaySource;
  let described: string;
  // A pool of known-routable points when given; each seed starts its own stretch of the pool, so runs share no point.
  const pool = options.pointsFile === null ? null
    : new PointPool(parsePointPool(JSON.parse(readFileSync(options.pointsFile, "utf8")) as unknown, options.pointsFile), options.seed * 10_000);
  const poolText = pool === null ? "" : `, points from ${options.pointsFile} (${pool.size})`;
  if (options.requestFile !== null) {
    const day = parseRouteDay(JSON.parse(readFileSync(options.requestFile, "utf8")) as unknown, options.requestFile);
    if (day.snapshot.metroId !== options.metroId) { console.error(`${options.requestFile} is for ${day.snapshot.metroId}, not --metro ${options.metroId}`); return 2; }
    // Without a pool, job points spread over the day's own appointments and homes.
    const box = bounds(day);
    const area = pool === null ? new AreaPoints(parseArea(`${box.south},${box.west},${box.north},${box.east}`), options.seed * 1_000_000) : null;
    source = DaySource.recorded(day, () => pool?.next() ?? (area ?? (() => { throw new Error("No job points"); })()).next());
    described = `recorded ${options.requestFile}${poolText}`;
  } else {
    const area = options.area === null ? null : new AreaPoints(options.area, options.seed * 1_000_000);
    const next = () => pool?.next() ?? (area ?? (() => { throw new Error("No --area or --points-file for a generated day"); })()).next();
    const date = workDate(options.timeZone);
    source = DaySource.generated({ metroId: options.metroId, timeZone: options.timeZone, serviceId: "drive-time-service", prefix: "dt",
      technicians: options.technicians, appointmentsPerDay: options.appointments, dates: [date], rates: KIT_RATES, policy: KIT_POLICY }, next);
    described = options.area === null ? `generated on ${date}${poolText}` : `generated in ${options.area.south},${options.area.west},${options.area.north},${options.area.east} on ${date}`;
  }
  const sample = source.day(options.placement === "fresh-day" ? "fresh-job" : options.placement);
  const startedAt = new Date();
  const run = newRouteRun(options.api, startedAt.getTime() + options.durationS * 1000, options.maxMoves, options.timeoutMs);
  const move: MoveSettings = { mode: options.mode, placement: options.placement, source };
  console.log(`Drive-time run: ${options.mode}, ${options.callers} callers${options.paceMs === null ? "" : ` every ${options.paceMs} ms`}, against ${options.api.base}`);
  const paceMs = options.paceMs;
  const now = Date.now();
  await Promise.all(Array.from({ length: options.callers }, (_, caller) => paceMs === null ? closedLoop(run, move)
    : pacedLoop(run, move, paceMs, now + Math.round((caller * paceMs) / options.callers), options.maxOutstanding)));
  const finishedAt = new Date();
  const seconds = Math.max(0.001, (finishedAt.getTime() - startedAt.getTime()) / 1000);
  const report: RouteReport = {
    label: options.label, mode: options.mode, placement: options.placement, scheduler: options.api.base, metroId: options.metroId,
    day: { source: described, technicianDays: sample.snapshot.technicianDays.length, appointments: sample.snapshot.appointments.length },
    settings: { callers: options.callers, paceMs: options.paceMs, durationS: options.durationS, maxMoves: options.maxMoves, maxOutstanding: options.maxOutstanding,
      timeoutMs: options.timeoutMs, seed: options.seed },
    startedAt: startedAt.toISOString(), finishedAt: finishedAt.toISOString(),
    machine: { cpus: cpus().length, cpuModel: cpus()[0]?.model.trim() ?? "unknown", node: process.version },
    moves: run.moves, skippedTicks: run.skippedTicks, noPlacement: run.noPlacement, operations: routeOperations(run.records, seconds),
    contractBreaches: run.contractBreaches, anomalies: run.anomalies, records: run.records,
  };
  mkdirSync(options.out, { recursive: true });
  const name = `routes-${options.mode}-${startedAt.toISOString().replace(/[:.]/g, "-")}`;
  writeFileSync(join(options.out, `${name}.json`), `${JSON.stringify(report)}\n`);
  writeFileSync(join(options.out, `${name}.md`), routeMarkdown(report));
  console.log(routeMarkdown(report));
  return Object.keys(run.contractBreaches).length === 0 ? 0 : 1;
}

main().then(code => { process.exitCode = code; }, (error: unknown) => {
  console.error(error);
  process.exitCode = 1;
});
