import { test } from "node:test";
import assert from "node:assert/strict";
import { breakdownLines, containerShareLines, parseApiLines, requestLog, serverBreakdown } from "../src/load/serverlog.ts";

const OFFERS = '2026-10-17T04:26:14.483Z  INFO [tenant=loadtest request=c5fa604f-6e7a-467c-a043-849ba1948b06] 1 --- [nio-8000-exec-5] dev.waterflex.scheduler.api.requests     : ' +
  'api operation="POST /api/v1/booking/offers" status=201 elapsedMs=1074 queueMs=0 routingMs=8 calculationMs=1002 dbMs=13 dbCalls=56 routingPairs=6841 cacheHits=6841 fetched=0 commitMs=41 cpuMs=1036';
const CONFIRM = '2026-10-17T04:26:15.001Z  INFO [tenant=loadtest request=0f0e1d2c-3b4a-4958-8677-5a4b3c2d1e0f] 1 --- [nio-8000-exec-2] dev.waterflex.scheduler.api.requests     : ' +
  'api operation="POST /api/v1/booking/holds/{holdId}/confirm" status=200 elapsedMs=20 queueMs=0 routingMs=5 calculationMs=0 dbMs=4 dbCalls=3 routingPairs=71 cacheHits=71 fetched=0 cpuMs=4';
const REFUSED = '2026-10-17T04:26:16.000Z  INFO [tenant=other request=] 1 --- [nio-8000-exec-3] dev.waterflex.scheduler.api.requests     : api operation="POST /api/v1/booking/offers" status=400 elapsedMs=3';
const NOISE = '2026-10-17T04:26:14.000Z  INFO [tenant=loadtest request=c5fa604f-6e7a-467c-a043-849ba1948b06] 1 --- [nio-8000-exec-5] d.w.scheduler.api.BookingOffers : Retrying booking search for job j (attempt 2)';
const ECS = JSON.stringify({ "@timestamp": "2026-10-17T04:26:17.000Z", "log.level": "INFO", "log.logger": "dev.waterflex.scheduler.api.requests", tenant: "loadtest",
  requestId: "9a8b7c6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d", operation: "POST /api/v1/booking/offers",
  message: 'api operation="POST /api/v1/booking/offers" status=201 elapsedMs=900 queueMs=120 routingMs=300 calculationMs=400 dbMs=30 dbCalls=40 routingPairs=500 cacheHits=200 fetched=300 cpuMs=410' });

test("request summary lines are read from the plain console format and from ECS JSON, and other lines are skipped", () => {
  const lines = parseApiLines([NOISE, OFFERS, "", CONFIRM, REFUSED, ECS, '{"message":"Started SchedulerApplication"}', "not json {"].join("\n"));
  assert.equal(lines.length, 4);
  assert.deepEqual(lines[0], { tenant: "loadtest", requestId: "c5fa604f-6e7a-467c-a043-849ba1948b06", operation: "POST /api/v1/booking/offers", status: 201, elapsedMs: 1074,
    breakdown: { queueMs: 0, routingMs: 8, calculationMs: 1002, dbMs: 13, dbCalls: 56, routingPairs: 6841, cacheHits: 6841, fetched: 0, commitMs: 41, cpuMs: 1036 } });
  assert.equal(lines[1]?.breakdown?.commitMs, null, "a line from before the commit phase was logged has no commit time, not zero");
  assert.deepEqual(lines[2], { tenant: "other", requestId: "", operation: "POST /api/v1/booking/offers", status: 400, elapsedMs: 3, breakdown: null },
    "a refused request ran under no deadline and has no breakdown");
  assert.equal(lines[3]?.tenant, "loadtest");
  assert.equal(lines[3]?.breakdown?.queueMs, 120, "ECS fields sit beside the message");
});

test("a summary line that cannot be read in full is an error rather than a partly invented attribution", () => {
  assert.throws(() => parseApiLines(OFFERS.replace(" routingMs=8", "")), /without routingMs/);
  assert.throws(() => parseApiLines(OFFERS.replace("elapsedMs=1074", "elapsedMs=fast")), /Unreadable request log line/);
  assert.throws(() => parseApiLines(OFFERS.replace("[tenant=loadtest request=c5fa604f-6e7a-467c-a043-849ba1948b06] ", "")), /without its tenant/);
});

test("the breakdown is per operation and status for one tenant, optionally for named requests only", () => {
  const second = OFFERS.replace("c5fa604f-6e7a-467c-a043-849ba1948b06", "11111111-2222-4333-8444-555555555555").replace("elapsedMs=1074", "elapsedMs=2000")
    .replace("calculationMs=1002", "calculationMs=1900").replace(" commitMs=41", "");
  const lines = parseApiLines([OFFERS, CONFIRM, REFUSED, second].join("\n"));
  const all = serverBreakdown(lines, { tenant: "loadtest" });
  assert.deepEqual(Object.keys(all), ["POST /api/v1/booking/holds/{holdId}/confirm 200", "POST /api/v1/booking/offers 201"], "the other tenant's refused call is left out");
  const offers = all["POST /api/v1/booking/offers 201"];
  assert.equal(offers?.calls, 2);
  assert.deepEqual(offers?.elapsedMs, { p50: 1074, p95: 2000, mean: 1537 });
  assert.deepEqual(offers?.calculationMs, { p50: 1002, p95: 1900, mean: 1451 });
  assert.deepEqual(offers?.commitMs, { p50: 41, p95: 41, mean: 41 }, "over the call that logged one");
  const refused = serverBreakdown(lines, { tenant: "other" })["POST /api/v1/booking/offers 400"];
  assert.equal(refused?.attributed, 0);
  assert.equal(refused?.queueMs, null, "no breakdown means no attribution, never zeros");
  const one = serverBreakdown(lines, { tenant: "loadtest", requestIds: new Set(["11111111-2222-4333-8444-555555555555"]) });
  assert.deepEqual(Object.keys(one), ["POST /api/v1/booking/offers 201"]);
  assert.equal(one["POST /api/v1/booking/offers 201"]?.calls, 1);
  assert.deepEqual(serverBreakdown(lines, { tenant: "nobody" }), {});
  const table = breakdownLines(all);
  assert.equal(table[0], "## Server time by call (request log)");
  assert.equal(table[table.length - 1], "| POST /api/v1/booking/offers 201 | 2 | 1074 / 2000 | 2 | 0 / 0 | 8 / 8 | 1002 / 1900 | 13 / 13 | 41 / 41 | 6841 / 0 | 1036 |");
});

test("each container's share of the tenant's request lines shows which replicas served the run", () => {
  const log = requestLog([{ container: "scheduler-booking-1", log: [OFFERS, REFUSED, NOISE].join("\n") },
    { container: "scheduler-booking-2", log: [CONFIRM, ECS].join("\n") }, { container: "scheduler-booking-3", log: "" }], "loadtest");
  assert.equal(log.lines.length, 4, "every container's request lines pooled, the refused other-tenant call among them");
  assert.deepEqual(log.containers, [{ container: "scheduler-booking-1", apiLines: 1 }, { container: "scheduler-booking-2", apiLines: 2 }, { container: "scheduler-booking-3", apiLines: 0 }]);
  assert.deepEqual(containerShareLines(log.containers).slice(-3), ["| scheduler-booking-1 | 1 |", "| scheduler-booking-2 | 2 |", "| scheduler-booking-3 | 0 |"]);
});
