import { test } from "node:test";
import assert from "node:assert/strict";
import { breakdownLines, containerShareLines, medianDecimal, parseApiLines, parseStudyReceipts, requestLog, serverBreakdown, studyLines, studySummary } from "../src/load/serverlog.ts";

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

const RECEIPT_BODY = { proposalId: "dp_1", serviceDate: "2026-10-21", variant: "TABU_KOPT", seed: 17, technicians: 20, visits: 40,
  phases: [{ name: "REFERENCE", budgetMs: 5000, solveMs: 5003, termination: "TIME_LIMIT" }, { name: "FAIRNESS", budgetMs: 5000, solveMs: 4998, termination: "TIME_LIMIT" }],
  fairness: "SEARCHED", accepted: false, reason: "NO_POLICY_IMPROVEMENT",
  reference: { costCents: 120000, overtimeMinutes: 0, variance: "0.0123456789012345678901234567890123", maximumUtilization: "0.9" },
  costCeilingCents: 122400, proposed: { costCents: 119000, overtimeMinutes: 0, variance: "0.01", maximumUtilization: "0.85" } };
const ecsLine = (requestId: string, message: string, tenant = "loadtest") =>
  JSON.stringify({ "@timestamp": "2026-10-20T20:00:00.000Z", "log.level": "INFO", tenant, requestId, message });
const ended = (time: number, speed: number) =>
  `Solving ended: time spent (${time}), best score ([0/0/0/0/0]hard/[0/-4000]soft), move evaluation speed (${speed}/sec), phase total (1), environment mode (NO_ASSERT), move thread count (NONE).`;

test("a study receipt takes its own request's solver speeds in order, across interleaved requests", () => {
  const log = [ecsLine("r1", ended(5003, 900)), ecsLine("r2", ended(5001, 800)), ecsLine("r1", ended(4998, 700)),
    ecsLine("r1", `Study receipt ${JSON.stringify(RECEIPT_BODY)}`), ecsLine("r2", ended(4990, 600)),
    ecsLine("r2", `Study receipt ${JSON.stringify({ ...RECEIPT_BODY, proposalId: "dp_2", accepted: true, reason: "FAIRNESS_IMPROVEMENT" })}`),
    ecsLine("r3", ended(10, 5)), ecsLine("other", `Study receipt ${JSON.stringify(RECEIPT_BODY)}`, "elsewhere")].join("\n");
  const receipts = parseStudyReceipts(log);
  assert.equal(receipts.length, 3);
  assert.deepEqual(receipts[0]?.solves.map(solve => solve.movesPerSecond), [900, 700]);
  assert.deepEqual(receipts[1]?.solves.map(solve => solve.movesPerSecond), [800, 600]);
  const summary = studySummary(receipts, "loadtest");
  assert.equal(summary.receipts, 2, "another tenant's receipt is left out");
  assert.deepEqual(summary.decisions, { NO_POLICY_IMPROVEMENT: 1, ACCEPTED: 1 });
  assert.equal(summary.phases.find(phase => phase.phase === "FAIRNESS")?.movesPerSecond?.p50, 600);
  assert.equal(summary.referenceVariance, "0.0123456789012345678901234567890123", "decimal readings keep every digit");
  assert.ok(studyLines(summary).some(line => line.startsWith("| REFERENCE | 2 |")));
});

test("a receipt carries the five fairness measures of each plan, each an exact decimal string", () => {
  const five = { costCents: 120000, overtimeMinutes: 0, variance: "0.0123", maximumUtilization: "0.9", utilizationSpread: "0.3", absoluteDeviation: "0.08", loadBalance: "1234.5" };
  const body = { ...RECEIPT_BODY, reference: five, proposed: { ...five, variance: "0.01" } };
  const [receipt] = parseStudyReceipts(ecsLine("r1", `Study receipt ${JSON.stringify(body)}`));
  assert.ok(receipt !== undefined && "loadBalance" in receipt.body.proposed && receipt.body.proposed.loadBalance === "1234.5");
  assert.throws(() => parseStudyReceipts(ecsLine("r2", `Study receipt ${JSON.stringify({ ...body, proposed: { ...five, loadBalance: 1234.5 } })}`)), /out of contract/,
    "a measure as a JSON number could lose digits");
});

test("a receipt names its flight recordings only when every phase has one", () => {
  const recorded = { ...RECEIPT_BODY, profile: { thread: "daily-queue-0", recordings: { REFERENCE: "dp_1-REFERENCE.jfr", FAIRNESS: "dp_1-FAIRNESS.jfr" } } };
  const partial = { ...RECEIPT_BODY, proposalId: "dp_2", profile: { thread: "daily-queue-1", recordings: { REFERENCE: "dp_2-REFERENCE.jfr" } } };
  const gap = { ...RECEIPT_BODY, proposalId: "dp_3", profile: { unavailable: "PHASES_NOT_RECORDED" } };
  const log = [ecsLine("r1", `Study receipt ${JSON.stringify(recorded)}`), ecsLine("r2", `Study receipt ${JSON.stringify(partial)}`),
    ecsLine("r3", `Study receipt ${JSON.stringify(gap)}`), ecsLine("r4", `Study receipt ${JSON.stringify(RECEIPT_BODY)}`)].join("\n");
  const summary = studySummary(parseStudyReceipts(log), "loadtest");
  assert.equal(summary.receipts, 4);
  assert.equal(summary.profiled, 1, "a recording per phase, or the receipt is not profiled");
  assert.ok(studyLines(summary).some(line => line.includes("1 with every phase flight-recorded")));
  assert.throws(() => parseStudyReceipts(ecsLine("r5", `Study receipt ${JSON.stringify({ ...RECEIPT_BODY, profile: { thread: "" , recordings: {} } })}`)), /out of contract/);
});

test("a receipt without speeds is counted unpaired and an unmeasured plan is named, never zero", () => {
  const body = { ...RECEIPT_BODY, proposed: { unavailable: "INVALID_PLAN" } };
  const summary = studySummary(parseStudyReceipts(ecsLine("r1", `Study receipt ${JSON.stringify(body)}`)), "loadtest");
  assert.equal(summary.unpairedSpeeds, 1);
  assert.equal(summary.phases[0]?.movesPerSecond, null);
  assert.equal(summary.proposedCostCents, null);
  assert.deepEqual(summary.unmeasured, { "proposed INVALID_PLAN": 1 });
});

test("a receipt that does not read in full is an error", () => {
  assert.throws(() => parseStudyReceipts(ecsLine("r1", `Study receipt ${JSON.stringify({ ...RECEIPT_BODY, seed: "17" })}`)), /out of contract/);
  assert.throws(() => parseStudyReceipts(ecsLine("r1", "Study receipt {not json")), /Unreadable study receipt/);
  assert.throws(() => parseStudyReceipts(JSON.stringify({ message: `Study receipt ${JSON.stringify(RECEIPT_BODY)}` })), /without its tenant/);
  assert.equal(medianDecimal(["0.2", "0.10", "0.3"]), "0.2");
});
