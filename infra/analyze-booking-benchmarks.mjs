import assert from "node:assert/strict";
import { readFile, writeFile } from "node:fs/promises";
import { createHash } from "node:crypto";
import { gzipSync } from "node:zlib";

const [prefix, ...paths] = process.argv.slice(2);
assert.ok(prefix && paths.length, "Pass output prefix followed by completed JSONL runs");
const sources = []; const cases = []; const summaries = [];
const quantile = (values, fraction) => values.length ? [...values].sort((a, b) => a - b)[Math.ceil(fraction * values.length) - 1] : null;
const total = (audit, field) => audit.days.reduce((sum, day) => sum + field(day), 0);
for (const [source, path] of paths.entries()) {
  const raw = await readFile(path); const rows = raw.toString("utf8").trim().split(/\r?\n/).map(line => JSON.parse(line));
  const provenance = rows.find(row => row.type === "provenance"); assert.ok(provenance);
  assert.equal(rows.filter(row => row.type === "failure").length, 0, "Retain failed runs separately; do not silently omit them");
  const results = rows.filter(row => row.type === "case");
  assert.equal(results.length, provenance.sizes.length * provenance.workloads.length * provenance.concurrencyValues.length * provenance.caches.length, "Run is incomplete");
  const seen = new Set(); const elapsed = []; const outcomeCounts = {}; let served = 0, incomplete = 0, unknown = 0, excludedTiming = 0;
  for (const row of results) {
    const key = `${row.size}/${row.workload}/${row.concurrency}/${row.cache}`;
    assert.ok(!seen.has(key)); seen.add(key);
    assert.equal(row.independentlyValidated, true); assert.equal(row.promiseViolations, 0);
    assert.equal(row.attempts.length, provenance.requests);
    const samples = [];
    for (const attempt of row.attempts) {
      outcomeCounts[attempt.outcome] = (outcomeCounts[attempt.outcome] ?? 0) + 1;
      served += Number(attempt.served);
      // Old harness selection errors included selection/release in elapsed time and marked
      // search incomplete. Neither measurement can be recovered from that row alone.
      if (attempt.outcome === "SELECTION_CONFLICT" && attempt.selectionElapsedMs == null) { unknown++; excludedTiming++; continue; }
      if (attempt.completed == null) unknown++; else incomplete += Number(!attempt.completed);
      samples.push(attempt.elapsedMs); elapsed.push(attempt.elapsedMs);
    }
    const beforeCost = total(row.before, day => day.policy.costCents), afterCost = total(row.after, day => day.policy.costCents);
    const confirmed = total(row.after, day => day.confirmedAppointments);
    cases.push({ source, key, datasetFingerprint: row.datasetFingerprint, routingIdentity: row.after.routingIdentity,
      served: row.served, requests: row.attempts.length, p50Ms: quantile(samples, .5), p95Ms: quantile(samples, .95), p99Ms: quantile(samples, .99),
      beforeCost, afterCost, costPerConfirmedAppointmentCents: confirmed ? afterCost / confirmed : null,
      incrementalCostPerServedCents: row.served ? (afterCost - beforeCost) / row.served : null,
      overtimeBefore: total(row.before, day => day.policy.overtimeMinutes), overtimeAfter: total(row.after, day => day.policy.overtimeMinutes),
      waitingBefore: total(row.before, day => day.waitingMinutes), waitingAfter: total(row.after, day => day.waitingMinutes),
      meanDailyVarianceBefore: total(row.before, day => day.policy.fairness.variance) / row.before.days.length,
      meanDailyVarianceAfter: total(row.after, day => day.policy.fairness.variance) / row.after.days.length,
      maximumUtilization: Math.max(...row.after.days.map(day => day.policy.fairness.maximumUtilization)),
      changedAssignments: row.changedAssignments, retimedAppointments: row.retimedAppointments });
  }
  const archive = `${prefix}-source-${source}.jsonl.gz`;
  await writeFile(archive, gzipSync(raw), { flag: "wx" });
  sources.push({ source, provenance, archive: archive.split(/[\\/]/).at(-1), sha256: createHash("sha256").update(raw).digest("hex") });
  summaries.push({ source, variant: provenance.variant, cases: results.length, requests: results.length * provenance.requests,
    served, incomplete, unknownSearchCompletion: unknown, excludedTiming, outcomeCounts,
    p50Ms: quantile(elapsed, .5), p95Ms: quantile(elapsed, .95), p99Ms: quantile(elapsed, .99) });
}
const comparisons = [];
for (const later of cases.filter(row => row.source > 0)) {
  const initial = cases.find(row => row.source === 0 && row.key === later.key);
  if (!initial) continue;
  assert.equal(initial.datasetFingerprint, later.datasetFingerprint, `Dataset changed for ${later.key}`);
  comparisons.push({ source: later.source, key: later.key, beforeServed: initial.served, afterServed: later.served,
    beforeP95Ms: initial.p95Ms, afterP95Ms: later.p95Ms });
}
await writeFile(`${prefix}.json`, JSON.stringify({ sources, summaries, cases, comparisons,
  limitations: ["Ten-request case p95 is the maximum sample, not a stable production tail estimate. Read sample counts.",
    "Search HTTP measurements exclude browser address validation; actual-road and deterministic-fixture evidence are separate.",
    "Concurrent local benchmarks and verification shared hardware. No production capacity or payroll savings claim.",
    "Accepted request streams can diverge after failures; compare served demand with latency and cost per confirmed appointment.",
    "Legacy selection-conflict rows have unavailable search timing/completion and are excluded only from those statistics.",
    "Earlier runs did not capture harness source hashes or provider HTTP counters. Server revisions and available artifact manifests remain explicit."] }, null, 2) + "\n", { flag: "wx" });
console.log(JSON.stringify(summaries, null, 2));
