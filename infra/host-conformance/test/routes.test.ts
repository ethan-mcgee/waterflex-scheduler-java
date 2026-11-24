import assert from "node:assert/strict";
import { test } from "node:test";
import type { Reply, SchedulerApi } from "../src/client.ts";
import { bounds, closedLoop, DaySource, droppedDay, move, newRouteRun, pacedLoop, parseRouteDay, type RouteDay, type RouteRecord } from "../src/load/routes.ts";
import { routeMarkdown, routeOperations, spread } from "../src/load/routesreport.ts";
import { AreaPoints, parseArea, parsePointPool, PointPool } from "../src/points.ts";
import { KIT_POLICY, KIT_RATES } from "../src/snapshot.ts";

const AREA = parseArea("35.40,-120.90,35.70,-120.50");

function generated(technicians = 2) {
  const points = new AreaPoints(AREA);
  return DaySource.generated({ metroId: "metro-paso-robles", timeZone: "America/Los_Angeles", serviceId: "svc", prefix: "t", technicians,
    appointmentsPerDay: 3, dates: ["2026-12-01"], rates: KIT_RATES, policy: KIT_POLICY }, () => points.next());
}

const recordedJson = () => ({ serviceDate: "2026-11-17", snapshot: generated(1).day("repeat").snapshot });

test("a recorded day is read only when its date, appointments and technicians are usable", () => {
  const json = recordedJson();
  json.snapshot.technicianDays.forEach(day => { day.serviceDate = "2026-11-17"; });
  json.snapshot.appointments.forEach(item => { item.serviceDate = "2026-11-17"; });
  const day = parseRouteDay(json, "day.json");
  assert.equal(day.serviceDate, "2026-11-17");
  assert.throws(() => parseRouteDay(null, "x"), /not a JSON object/);
  assert.throws(() => parseRouteDay({ ...json, serviceDate: "17 Nov" }, "x"), /serviceDate/);
  assert.throws(() => parseRouteDay({ ...json, snapshot: { ...json.snapshot, technicianDays: [] } }, "x"), /technicianDays/);
  const unlocated = structuredClone(json);
  (unlocated.snapshot.appointments[0] as { location: unknown }).location = { address: { line1: "1 Main" } };
  assert.throws(() => parseRouteDay(unlocated, "x"), /coordinates/, "an appointment without coordinates is refused, never guessed");
  const otherDay = structuredClone(json);
  otherDay.snapshot.appointments[0]!.serviceDate = "2026-11-18";
  assert.throws(() => parseRouteDay(otherDay, "x"), /serviceDate/);
});

test("bounds cover every appointment and home of the day", () => {
  const day = generated().day("repeat");
  const box = bounds(day);
  for (const item of day.snapshot.appointments) {
    const point = item.location as { lat: number; lng: number };
    assert.ok(point.lat >= box.south && point.lat <= box.north && point.lng >= box.west && point.lng <= box.east);
  }
});

test("placements: repeat sends one day and job, fresh-job one day and new jobs, fresh-day new days", () => {
  const source = generated();
  assert.equal(source.day("repeat"), source.day("fresh-job"));
  assert.deepEqual(source.job("repeat"), source.job("repeat"));
  assert.notDeepEqual(source.job("fresh-job").location, source.job("fresh-job").location);
  const a = source.day("fresh-day"), b = source.day("fresh-day");
  assert.notDeepEqual(a.snapshot.appointments.map(item => item.location), b.snapshot.appointments.map(item => item.location), "a fresh day has new points");
  const recorded = DaySource.recorded(source.day("repeat"), () => ({ lat: 35.5, lng: -120.7 }));
  assert.throws(() => recorded.day("fresh-day"), /recorded day/);
});

/** An insertions answer that offers the job after the first visit of the first technician, with every stop timed. */
function offer(day: RouteDay, jobId: string, leaveOut: string | null = null) {
  const technicianId = day.snapshot.technicianDays[0]!.technicianId;
  const own = day.snapshot.appointments.filter(item => item.technicianId === technicianId).sort((a, b) => a.sequence - b.sequence);
  const order = [own[0]!.id, jobId, ...own.slice(1).map(item => item.id)].filter(id => id !== leaveOut);
  const stops = order.map((appointmentId, sequence) => ({ appointmentId, sequence, plannedStart: `2026-12-01T${String(16 + sequence).padStart(2, "0")}:00:00Z`,
    plannedEnd: `2026-12-01T${String(16 + sequence).padStart(2, "0")}:45:00Z` }));
  const window = { start: "2026-12-01T17:00:00Z", end: "2026-12-01T19:00:00Z" };
  const timed = { driveMinutes: 30, meters: 1000, paidMinutes: 400, costCents: 100, bufferSeconds: 0 };
  return { routingIdentity: "map", skippedTechnicianDays: [], technicianDays: [{ technicianId, serviceDate: day.serviceDate, holdsAsGiven: true, qualified: true,
    gaps: [{ routable: false, starts: [] }, { afterAppointmentId: own[0]!.id, routable: true, starts: [{ plannedStart: stops[1]!.plannedStart, window, timed, stops }] }] }] };
}

test("a drop places the job and re-times its technician's day from the offered stops, leaving other days alone", () => {
  const source = generated();
  const day = source.day("repeat"), job = source.job("repeat");
  const dropped = droppedDay(day, job, offer(day, job.id));
  assert.ok(dropped !== null);
  const technicianId = day.snapshot.technicianDays[0]!.technicianId;
  const own = dropped.snapshot.appointments.filter(item => item.technicianId === technicianId).sort((a, b) => a.sequence - b.sequence);
  assert.deepEqual(own.map(item => item.sequence), [0, 1, 2, 3]);
  assert.equal(own[1]!.id, job.id);
  assert.deepEqual(own[1]!.window, { start: "2026-12-01T17:00:00Z", end: "2026-12-01T19:00:00Z" });
  const others = (snapshot: RouteDay["snapshot"]) => snapshot.appointments.filter(item => item.technicianId !== technicianId);
  assert.deepEqual(others(dropped.snapshot), others(day.snapshot));
  assert.equal(day.snapshot.appointments.some(item => item.id === job.id), false, "the source day is not changed");
});

test("nowhere to drop is null: no start with stops, or stops that leave out one of the day's visits", () => {
  const source = generated();
  const day = source.day("repeat"), job = source.job("repeat");
  const none = offer(day, job.id);
  none.technicianDays[0]!.gaps = [{ routable: false, starts: [] }];
  assert.equal(droppedDay(day, job, none), null);
  const firstId = day.snapshot.appointments.find(item => item.technicianId === day.snapshot.technicianDays[0]!.technicianId)!.id;
  assert.equal(droppedDay(day, job, offer(day, job.id, firstId)), null);
});

function fakeApi(delayMs: number, status = 200, json: unknown = { routingIdentity: "m", routes: [], skippedTechnicianDays: [] }): SchedulerApi {
  return { base: "fake", call: async () => {
    await new Promise(resolve => setTimeout(resolve, delayMs));
    const text = JSON.stringify(json);
    return { status, headers: new Headers(status === 429 ? { "Retry-After": "1" } : {}), text, json, ms: delayMs, headersMs: delayMs } as Reply;
  } } as unknown as SchedulerApi;
}

test("a refused call is counted with its Retry-After and never retried", async () => {
  const run = newRouteRun(fakeApi(1, 429, { error: "BUSY", message: "busy", retryable: true }), Date.now() + 5000, 3, 1000);
  await closedLoop(run, { mode: "geometry", placement: "repeat", source: generated() });
  assert.equal(run.records.length, 3);
  assert.ok(run.records.every(record => record.status === 429 && record.error === "BUSY" && record.retryAfterS === 1 && record.attempts === 1));
});

test("an answer off the contract is a breach; a technician with visits left undrawn is an anomaly", async () => {
  const off = newRouteRun(fakeApi(1, 200, { routes: "no" }), Date.now() + 5000, 1, 1000);
  await move(off, { mode: "geometry", placement: "repeat", source: generated() }, null);
  assert.equal(Object.keys(off.contractBreaches).length, 1);
  const empty = newRouteRun(fakeApi(1), Date.now() + 5000, 1, 1000);
  await move(empty, { mode: "geometry", placement: "repeat", source: generated() }, null);
  assert.deepEqual(empty.contractBreaches, {});
  assert.deepEqual(Object.keys(empty.anomalies), ["Geometry: a technician with visits was not drawn"]);
});

test("a pick with nowhere to drop is its own operation, so a session is always a whole drag", async () => {
  const source = generated(1);
  const day = source.day("repeat");
  const technicianId = day.snapshot.technicianDays[0]!.technicianId;
  const drawn = { routingIdentity: "m", skippedTechnicianDays: [], routes: [{ technicianId, serviceDate: day.serviceDate, stops: [],
    legs: [{ segment: 0, legIndex: 0, seconds: 60, meters: 900, coordinates: [[-120.7, 35.6], [-120.6, 35.5]] }] }] };
  const full = { routingIdentity: "m", skippedTechnicianDays: [], technicianDays: [{ technicianId, serviceDate: day.serviceDate, holdsAsGiven: true, qualified: true,
    gaps: [{ routable: false, starts: [] }] }] };
  const api = { base: "fake", call: async (_method: string, path: string) => {
    const json = path.endsWith("/geometry") ? drawn : full;
    const text = JSON.stringify(json);
    return { status: 200, headers: new Headers(), text, json, ms: 1, headersMs: 1 } as Reply;
  } } as unknown as SchedulerApi;
  const run = newRouteRun(api, Date.now() + 5000, 2, 1000);
  await closedLoop(run, { mode: "session", placement: "repeat", source });
  assert.equal(run.noPlacement, 2);
  assert.deepEqual(run.records.filter(record => record.operation.startsWith("session,")).map(record => record.operation), ["session, nowhere to drop", "session, nowhere to drop"]);
  assert.equal(run.records.some(record => record.operation === "session" || record.operation === "session drop (geometry)"), false);
  assert.deepEqual(run.contractBreaches, {});
});

test("a paced dispatcher keeps its clock: a slow server leaves ticks skipped and late, never fewer ticks silently", async () => {
  const run = newRouteRun(fakeApi(250), Date.now() + 650, null, 1000);
  await pacedLoop(run, { mode: "geometry", placement: "repeat", source: generated() }, 100, Date.now(), 1);
  assert.ok(run.skippedTicks >= 3, `skipped ${run.skippedTicks}`);
  assert.equal(run.moves, run.records.length);
  assert.ok(run.records.every(record => record.lagMs !== null && record.lagMs >= 0));
});

test("operation summaries count only 200s per second and keep missing figures absent", () => {
  const record = (status: number | null, ms: number): RouteRecord => ({ operation: "geometry", status, error: status === 200 ? null : "X", message: null, ms, attempts: 1,
    startedAt: 0, headersMs: status === null ? null : ms - 1, bytes: status === null ? null : 100, lagMs: null, retryAfterS: null });
  const [op] = routeOperations([record(200, 10), record(200, 20), record(429, 5), record(null, 30)], 2);
  assert.ok(op !== undefined);
  assert.equal(op.okPerSecond, 1);
  assert.deepEqual(op.headersMs, { p50: 9, p95: 19, p99: 19, max: 19 }, "a call without an answer has no time to headers");
  assert.equal(op.lagMs, null);
  assert.equal(spread([]), null);
  assert.throws(() => routeOperations([], 0), /more than zero/);
  const markdown = routeMarkdown({ label: null, mode: "geometry", placement: "repeat", scheduler: "fake", metroId: "m",
    day: { source: "generated", technicianDays: 1, appointments: 3 }, settings: { callers: 1, paceMs: null, durationS: 5, maxMoves: null, maxOutstanding: 4, timeoutMs: 1000, seed: 1 },
    startedAt: "2026-11-24T00:00:00Z", finishedAt: "2026-11-24T00:00:02Z", machine: { cpus: 1, cpuModel: "x", node: "v22" }, moves: 4, skippedTicks: 0, noPlacement: 0,
    operations: [op], contractBreaches: {}, anomalies: {}, records: [] });
  assert.match(markdown, /\| geometry \| 4 \|/);
  assert.doesNotMatch(markdown, /\| - \|/, "missing values read n/a, never a dash");
});

test("a point pool gives its real points first, then nearby distinct ones, and never repeats", () => {
  const real = Array.from({ length: 7 }, (_, i) => ({ lat: 35.6 + i * 0.01, lng: -120.7 + i * 0.01 }));
  const pool = new PointPool(real);
  const first = Array.from({ length: 7 }, () => pool.next());
  assert.deepEqual([...first].sort((a, b) => a.lat - b.lat), real, "the first pass is the pool itself, in a fixed shuffle");
  assert.notDeepEqual(first, real, "the shuffle does not walk the file in order");
  const later = Array.from({ length: 70 }, () => pool.next());
  const keys = new Set([...first, ...later].map(point => `${point.lat},${point.lng}`));
  assert.equal(keys.size, 77, "no point is sent twice");
  for (const point of later) {
    const nearest = Math.min(...real.map(item => Math.max(Math.abs(item.lat - point.lat), Math.abs(item.lng - point.lng))));
    assert.ok(nearest <= 0.0008 + 1e-9, `a later point moved ${nearest} degrees from its source`);
  }
  assert.notDeepEqual(new PointPool(real, 3).next(), new PointPool(real, 0).next(), "another start gives another first point");
});

test("a points file is refused whole when any entry is not a location", () => {
  assert.deepEqual(parsePointPool({ points: [{ lat: 35.6, lng: -120.7 }] }, "p.json"), [{ lat: 35.6, lng: -120.7 }]);
  assert.throws(() => parsePointPool({ points: [] }, "p.json"), /empty/);
  assert.throws(() => parsePointPool({}, "p.json"), /no points array/);
  assert.throws(() => parsePointPool({ points: [{ lat: 35.6, lng: -120.7 }, { lat: "35", lng: -120 }] }, "p.json"), /point 1/);
  assert.throws(() => parsePointPool({ points: [{ lat: 95, lng: -120 }] }, "p.json"), /point 0/);
  assert.throws(() => new PointPool([]), /empty/);
});
