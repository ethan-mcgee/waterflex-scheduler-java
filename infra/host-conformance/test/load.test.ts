import assert from "node:assert/strict";
import { test } from "node:test";
import { HostStore, type MetroShape } from "../src/load/host.ts";
import { parsePrometheus, serverView, total } from "../src/load/metrics.ts";
import { dockerBytes, retryAfterMs, seeded } from "../src/load/scenarios.ts";
import { percentile, summarize } from "../src/load/stats.ts";
import { AreaPoints, parseArea } from "../src/points.ts";
import { KIT_POLICY, KIT_RATES } from "../src/snapshot.ts";
import type { Reply } from "../src/client.ts";

const SHAPE: MetroShape = { metroId: "metro-omaha", timeZone: "America/Chicago", serviceId: "svc", prefix: "t", technicians: 2,
  appointmentsPerDay: 2, dates: ["2026-10-15", "2026-10-16"], rates: KIT_RATES, policy: KIT_POLICY };

function store() {
  const points = new AreaPoints(parseArea("41.22,-96.05,41.28,-95.95"));
  return new HostStore(SHAPE, () => points.next(), () => points.next(), Date.parse("2026-10-13T12:00:00Z"));
}

test("a generated metro is a valid host record: ordered visits inside their windows, unique timestamps", () => {
  const snapshot = store().snapshot(SHAPE.dates);
  assert.equal(snapshot.technicianDays.length, 4);
  assert.equal(snapshot.appointments.length, 8);
  assert.equal(new Set(snapshot.technicianDays.map(day => day.lastModified)).size, 4, "each day has its own timestamp");
  for (const day of snapshot.technicianDays) {
    const visits = snapshot.appointments.filter(item => item.technicianId === day.technicianId && item.serviceDate === day.serviceDate);
    assert.deepEqual(visits.map(item => item.sequence), [0, 1]);
    assert.ok(Date.parse(visits[0]!.plannedStart) < Date.parse(visits[1]!.plannedStart), "sequence and plannedStart agree");
    for (const visit of visits) {
      assert.ok(Date.parse(visit.window.start) <= Date.parse(visit.plannedStart) && Date.parse(visit.plannedStart) < Date.parse(visit.window.end));
      assert.ok(Date.parse(day.shift.start) <= Date.parse(visit.window.start) && Date.parse(visit.window.end) <= Date.parse(day.shift.end));
    }
  }
  assert.deepEqual(store().snapshot(["2026-10-15"]).technicianDays.map(day => day.serviceDate), ["2026-10-15", "2026-10-15"]);
});

test("a receipt is written only when every listed timestamp still matches, and moves both days of a move", () => {
  const host = store();
  const before = host.snapshot(["2026-10-15"]);
  const [dayA, dayB] = before.technicianDays;
  const moved = before.appointments.find(item => item.technicianId === "t-tech-1" && item.sequence === 1)!;
  const staying = before.appointments.filter(item => item.technicianId === "t-tech-2");
  const job = { job: { id: "job-1", serviceId: "svc", durationMinutes: 60, location: { lat: 41.25, lng: -96.0 } },
    window: { start: "2026-10-15T15:00:00.000Z", end: "2026-10-15T17:00:00.000Z" } };
  const receipt = { receiptId: "r1", technicianDays: [dayA!, dayB!].map(day => ({ technicianId: day.technicianId, serviceDate: day.serviceDate, lastModified: day.lastModified })),
    assignments: [
      { appointmentId: before.appointments.find(item => item.technicianId === "t-tech-1" && item.sequence === 0)!.id, technicianId: "t-tech-1", serviceDate: "2026-10-15", sequence: 0, plannedStart: "2026-10-15T14:00:00.000Z", plannedEnd: "2026-10-15T14:45:00.000Z" },
      { appointmentId: "job-1", technicianId: "t-tech-1", serviceDate: "2026-10-15", sequence: 1, plannedStart: "2026-10-15T15:30:00.000Z", plannedEnd: "2026-10-15T16:30:00.000Z" },
      ...staying.map((item, index) => ({ appointmentId: item.id, technicianId: "t-tech-2", serviceDate: "2026-10-15", sequence: index, plannedStart: item.plannedStart, plannedEnd: item.plannedStart })),
      { appointmentId: moved.id, technicianId: "t-tech-2", serviceDate: "2026-10-15", sequence: 2, plannedStart: "2026-10-15T20:00:00.000Z", plannedEnd: "2026-10-15T20:45:00.000Z" },
    ] };
  assert.equal(host.write({ ...receipt, technicianDays: [{ ...receipt.technicianDays[0]!, lastModified: "2026-01-01T00:00:00Z" }, receipt.technicianDays[1]!] }, job), "CONFLICT");
  assert.deepEqual(host.snapshot(["2026-10-15"]), before, "a conflict writes nothing");
  assert.equal(host.write(receipt, job), "WRITTEN");
  const after = host.snapshot(["2026-10-15"]);
  assert.deepEqual(after.appointments.find(item => item.id === "job-1")?.window, job.window, "the job is created with its offered window");
  assert.equal(after.appointments.find(item => item.id === moved.id)?.technicianId, "t-tech-2");
  for (const day of after.technicianDays) assert.notEqual(day.lastModified, before.technicianDays.find(item => item.technicianId === day.technicianId)?.lastModified);
  assert.deepEqual(host.anomalies, []);
  assert.equal(host.write(receipt, job), "CONFLICT", "the same receipt cannot be written twice");
});

test("a receipt that drops an appointment from a listed day is reported, never silently deleted", () => {
  const host = store();
  const day = host.snapshot(["2026-10-15"]).technicianDays[0]!;
  const result = host.write({ receiptId: "r2", assignments: [], technicianDays: [{ technicianId: day.technicianId, serviceDate: day.serviceDate, lastModified: day.lastModified }] });
  assert.equal(result, "WRITTEN");
  assert.equal(host.anomalies.length, 2);
  assert.equal(host.appointmentCount, 8);
});

test("percentiles use the nearest rank and summaries count outcomes", () => {
  const values = Array.from({ length: 100 }, (_, index) => index + 1);
  assert.deepEqual([percentile(values, 0.5), percentile(values, 0.95), percentile(values, 0.99), percentile(values, 1)], [50, 95, 99, 100]);
  assert.equal(percentile([7], 0.5), 7);
  assert.throws(() => percentile([], 0.5), /No values/);
  const [summary] = summarize([
    { operation: "offers", status: 201, error: null, message: null, ms: 100, attempts: 1 },
    { operation: "offers", status: 429, error: "BUSY", message: "Search capacity exhausted", ms: 900, attempts: 3 },
    { operation: "offers", status: null, error: "TimeoutError", message: null, ms: 9000, attempts: 1 },
  ]);
  assert.deepEqual(summary?.statuses, { "201": 1, "429": 1, none: 1 });
  assert.deepEqual(summary?.errors, { BUSY: 1, TimeoutError: 1 });
  assert.equal(summary?.retriedBusy, 1);
  assert.deepEqual(summary?.reasons, { "BUSY: Search capacity exhausted": 1 }, "a transport failure has no message to report");
  assert.equal(summary?.ms?.max, 9000);
});

const SCRAPE = (count: number, sum: number, pairs: number, cpu: number) => `# HELP x
http_server_requests_seconds_count{error="none",exception="none",method="POST",outcome="SUCCESS",status="201",uri="/api/v1/booking/offers"} ${count}
http_server_requests_seconds_sum{error="none",exception="none",method="POST",outcome="SUCCESS",status="201",uri="/api/v1/booking/offers"} ${sum}
http_server_requests_seconds_count{method="GET",outcome="SUCCESS",status="200",uri="/health"} 4
scheduler_routing_pairs_total{metro="metro-omaha"} ${pairs}
scheduler_admission_rejections_total 0.0
process_cpu_usage ${cpu}
jvm_memory_used_bytes{area="heap",id="G1 Eden Space"} 1.0E8
jvm_memory_used_bytes{area="heap",id="G1 Old Gen"} 5.0E7
jvm_memory_used_bytes{area="nonheap",id="Metaspace"} 9.0E7
`;

test("the scheduler view is the difference between scrapes, API routes only", () => {
  const before = parsePrometheus(SCRAPE(10, 2, 100, 0.1));
  const after = parsePrometheus(SCRAPE(30, 6, 400, 0.5));
  assert.equal(total(after, "jvm_memory_used_bytes", { area: "heap" }), 1.5e8);
  const view = serverView(before, after, [before, after]);
  assert.deepEqual(view.operations, [{ uri: "/api/v1/booking/offers", method: "POST", status: "201", calls: 20, meanMs: 200 }]);
  assert.equal(view.routingPairs, 300);
  assert.deepEqual(view.processCpu, { mean: 0.3, max: 0.5 });
  assert.equal(view.heapUsedMaxBytes, 1.5e8);
  assert.equal(serverView(before, after, []).processCpu, null, "an unsampled gauge is reported as not sampled, never as zero");
  assert.throws(() => parsePrometheus("not a metric line at all"), /Unreadable/);
});

test("Retry-After, docker sizes and the seeded generator", () => {
  const reply = (value: string | null) => ({ headers: new Headers(value === null ? {} : { "Retry-After": value }) }) as Reply;
  assert.deepEqual([retryAfterMs(reply("3")), retryAfterMs(reply(null)), retryAfterMs(reply("soon")), retryAfterMs(reply("0"))], [3000, 1000, 1000, 1000]);
  assert.deepEqual([retryAfterMs(reply("300")), retryAfterMs(reply("301"))], [300_000, 1000], "a daily wait of up to 300 s is honoured");
  assert.equal(dockerBytes("1.5GiB"), 1.5 * 1024 ** 3);
  assert.equal(dockerBytes("512MiB "), 512 * 1024 ** 2);
  assert.throws(() => dockerBytes("lots"), /Unreadable/);
  const a = seeded(7), b = seeded(7);
  const first = [a(), a(), a()];
  assert.deepEqual([b(), b(), b()], first);
  assert.ok(first.every(value => value >= 0 && value < 1));
});

test("point streams with different starts share no point, and the same start repeats", () => {
  const area = parseArea("41.22,-96.05,41.28,-95.95");
  const take = (start: number) => { const stream = new AreaPoints(area, start); return Array.from({ length: 200 }, () => stream.next()); };
  const first = take(0), second = take(1_000_000);
  const keys = new Set(first.map(point => `${point.lat},${point.lng}`));
  assert.ok(second.every(point => !keys.has(`${point.lat},${point.lng}`)));
  assert.deepEqual(take(1_000_000), second);
  assert.throws(() => new AreaPoints(area, -1), /whole number/);
});
