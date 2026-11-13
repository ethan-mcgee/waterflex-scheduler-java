import { test } from "node:test";
import assert from "node:assert/strict";
import { evaluateLines, groups, join, parseRows, stepTables, table, type DragRow } from "../src/load/movecheck.ts";

const at = (second: number) => `2026-11-13T20:00:${String(second).padStart(2, "0")}.000000000Z`;

/** A docker log line: docker's timestamp, then the scheduler's own plain line. */
function logged(second: number, tenant: string, elapsed: number, fetched: number, operation = "POST /api/v1/routes/evaluate") {
  return `${at(second)} 2026-11-13T20:00:${second}.000Z  INFO [tenant=${tenant} request=] 1 --- [nio-8000-exec-1] dev.waterflex.scheduler.api.requests : `
    + `api operation="${operation}" status=200 elapsedMs=${elapsed} queueMs=1 routingMs=${elapsed - 4} calculationMs=1 dbMs=2 dbCalls=3 routingPairs=225 cacheHits=${225 - fetched} fetched=${fetched} cpuMs=3`;
}

function row(seq: number, change: Partial<DragRow> = {}): DragRow {
  return { seq, case: "warm", delayMs: 0, rep: seq, discarded: false, date: "2026-11-30", target: "roomy", expected: "FEASIBLE", outcome: "FEASIBLE",
    startedAt: at(seq), endedAt: at(seq), browserMs: 60,
    resource: { fetchStart: 1, requestStart: 2, responseStart: 40, responseEnd: 45, duration: 44 },
    portal: { scope: 2, facts: 10, assemble: 1, scheduler: 25, total: 40 }, ...change };
}

const jsonl = (rows: readonly DragRow[]) => rows.map(item => JSON.stringify(item)).join("\n");

test("only the tenant's route evaluations are read, in time order, from docker's stamped log", () => {
  const log = [logged(3, "move-timing", 20, 0), logged(1, "move-timing", 30, 5), logged(2, "other", 10, 0), logged(4, "move-timing", 5, 0, "GET /api/v1/whoami"),
    "not a log line"].join("\n");
  assert.deepEqual(evaluateLines(log, "move-timing").map(line => line.elapsedMs), [30, 20]);
});

test("each drag is paired with its line in order, and every step is derived from both", () => {
  const rows = parseRows(jsonl([row(1), row(0, { case: "cold" })]));
  const lines = evaluateLines([logged(1, "move-timing", 22, 7), logged(2, "move-timing", 20, 0)].join("\n"), "move-timing");
  const { joined, paired } = join(rows, lines);
  assert.equal(paired, true);
  const cold = joined[0], warm = joined[1];
  assert.equal(cold?.row.case, "cold");
  assert.equal(cold?.steps.fetched, 7, "the first drag gets the first line");
  assert.equal(cold?.invalid, null);
  assert.equal(warm?.steps.proxy, 5, "proxy and transport: the portal's 25 ms call minus the scheduler's 20 ms");
  assert.equal(warm?.steps.toPortal, 4, "the request's 44 ms minus the portal's 40 ms");
  assert.equal(warm?.steps.render, 15, "60 ms to paint minus the response ending at 45 ms");
  assert.equal(warm?.steps.schedulerOther, 2, "20 ms minus 1 slot wait, 16 routing and 1 evaluation");
});

test("a mismatch in counts leaves the scheduler unmeasured instead of guessing which line is whose", () => {
  const { joined, paired, reason } = join(parseRows(jsonl([row(0), row(1)])), evaluateLines(logged(1, "move-timing", 20, 0), "move-timing"));
  assert.equal(paired, false);
  assert.match(reason ?? "", /1 route evaluation lines for 2 drags/);
  assert.equal(joined[0]?.steps.schedulerTotal, null);
  assert.equal(joined[0]?.invalid, null, "the browser and portal steps still count");
});

test("drags that fail a check are named and left out of the statistics", () => {
  const rows = parseRows(jsonl([row(0, { outcome: "NOT_FEASIBLE" }), row(1), row(2, { case: "cold" }), row(3, { portal: { scheduler: 10, total: 20 } })]));
  const lines = evaluateLines([logged(1, "move-timing", 20, 0), logged(2, "move-timing", 20, 3), logged(3, "move-timing", 20, 0), logged(4, "move-timing", 20, 0)].join("\n"), "move-timing");
  const { joined } = join(rows, lines);
  assert.match(joined[0]?.invalid ?? "", /answered NOT_FEASIBLE, expected FEASIBLE/);
  assert.match(joined[1]?.invalid ?? "", /a warm drag fetched 3 drive times/);
  assert.match(joined[2]?.invalid ?? "", /a cold drag fetched no drive times/);
  assert.match(joined[3]?.invalid ?? "", /the scheduler took 20 ms of a 10.0 ms call/);
  const warm = groups(joined).find(group => group.key === "warm|FEASIBLE|0");
  assert.equal(warm?.drags, 3);
  assert.equal(warm?.invalid, 3);
  assert.equal(warm?.stats.dropToPainted, null, "no valid drags, so no statistic, never zero");
  assert.match(stepTables(groups(joined), "median"), /not measured/);
});

test("a drag under a simulated delay that came back faster than the delay is left out", () => {
  const rows = parseRows(jsonl([row(0, { delayMs: 100 }), row(1, { delayMs: 20 })]));
  const { joined } = join(rows, evaluateLines([logged(1, "move-timing", 20, 0), logged(2, "move-timing", 20, 0)].join("\n"), "move-timing"));
  assert.match(joined[0]?.invalid ?? "", /the request took 44.0 ms under a \+100 ms delay/);
  assert.equal(joined[1]?.invalid, null, "a 44 ms request under a 20 ms delay is plausible");
});

test("discarded warm-up drags are not summarized, and tables are aligned", () => {
  const { joined } = join(parseRows(jsonl([row(0, { case: "warmup", discarded: true }), row(1)])),
    evaluateLines([logged(1, "move-timing", 20, 0), logged(2, "move-timing", 20, 0)].join("\n"), "move-timing"));
  assert.deepEqual(groups(joined).map(group => group.label), ["Warm, feasible, +0 ms"]);
  const lines = table(["Case", "Drags (ms)"], [["Warm", "1.5"], ["Cold, not feasible", "12.0"]]).split("\n");
  assert.equal(new Set(lines.map(line => line.length)).size, 1, "every row is as wide as the header");
  assert.equal(lines[1], "| ------------------ | ---------: |");
});
