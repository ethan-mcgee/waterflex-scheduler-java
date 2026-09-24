import assert from "node:assert/strict";
import { readFile, writeFile } from "node:fs/promises";
import { createHash } from "node:crypto";
import { gzipSync } from "node:zlib";

const [initialPath, cappedPath, densePath, outputPrefix, ...repeatPaths] = process.argv.slice(2);
assert.ok(initialPath && cappedPath && densePath && outputPrefix, "Pass initial, corrected cap, corrected dense JSONL, output prefix, optional repeats");
const sources = []; const selected = [];
for (const [index, path] of [initialPath, cappedPath, densePath, ...repeatPaths].entries()) {
  const raw = await readFile(path); const lines = raw.toString("utf8").trim().split(/\r?\n/).map(line => JSON.parse(line));
  const provenance = lines.find(row => row.type === "provenance"); assert.ok(provenance);
  const results = lines.filter(row => row.type === "result");
  const accepted = results.filter(row => index === 0 ? row.variant !== "CURRENT_CAPPED" && row.workload !== "NEAR_CAPACITY"
    : index === 1 ? row.workload !== "NEAR_CAPACITY" : true);
  for (const row of accepted) { assert.equal(row.violations, 0); selected.push({ source: index, ...row }); }
  const archive = `${outputPrefix}-source-${index}.jsonl.gz`;
  await writeFile(archive, gzipSync(raw), { flag: "wx" });
  sources.push({ index, archive: archive.split(/[\\/]/).at(-1), sha256: createHash("sha256").update(raw).digest("hex"), provenance,
    rows: results.length, accepted: accepted.length, excluded: results.length - accepted.length,
    reason: index === 0 ? "Exclude mislabeled capped runs and underloaded near-capacity fixture" : index === 1 ? "Use separately rerun dense dataset for every variant" : "No exclusions" });
}
const seen = new Set();
for (const row of selected) {
  const key = `${row.variant}/${row.seed}/${row.technicians}/${row.workload}`;
  assert.ok(!seen.has(key), `Duplicate case ${key}`); seen.add(key);
  if (row.variant === "CURRENT_CAPPED") {
    assert.ok(row.referencePhase.steps <= 1000); assert.ok(row.fairnessPhase == null || row.fairnessPhase.steps <= 1000);
  }
}
const cases = selected.map(row => ({ source: row.source, variant: row.variant, seed: row.seed, technicians: row.technicians, workload: row.workload,
  datasetFingerprint: row.datasetFingerprint, appointments: row.appointments, beforeCost: row.before.costCents, referenceCost: row.reference.costCents,
  afterCost: row.after.costCents, overtimeBefore: row.before.overtimeMinutes, overtimeAfter: row.after.overtimeMinutes,
  fairnessBefore: row.before.fairness.variance, fairnessAfter: row.after.fairness.variance, maximumUtilization: row.after.fairness.maximumUtilization,
  waitingBefore: row.paidWaitingBefore, waitingAfter: row.paidWaitingAfter, changedAssignments: row.changedAssignments, retimedAppointments: row.retimedAppointments,
  acceptance: row.acceptanceReason, elapsedMs: row.elapsedMs, referencePhase: row.referencePhase, fairnessPhase: row.fairnessPhase }));
const summaries = [];
for (const seed of [...new Set(cases.map(row => row.seed))]) for (const variant of [...new Set(cases.filter(row => row.seed === seed).map(row => row.variant))]) {
  const rows = cases.filter(row => row.seed === seed && row.variant === variant); assert.equal(rows.length, 21, `Incomplete ${variant}/${seed}`);
  const sum = field => rows.reduce((value, row) => value + row[field], 0);
  summaries.push({ seed, variant, cases: rows.length, referenceCost: sum("referenceCost"), afterCost: sum("afterCost"), overtimeAfter: sum("overtimeAfter"),
    meanFairnessAfter: sum("fairnessAfter") / rows.length, waitingAfter: sum("waitingAfter"), changedAssignments: sum("changedAssignments"), elapsedMs: sum("elapsedMs"),
    terminations: rows.flatMap(row => [row.referencePhase.termination, ...(row.fairnessPhase ? [row.fairnessPhase.termination] : [])])
      .reduce((counts, reason) => ({ ...counts, [reason]: (counts[reason] ?? 0) + 1 }), {}) });
}
const report = { generatedAt: new Date().toISOString(), sources, summaries, cases,
  evidence: "Independent deterministic-fixture validation with 15-second maximum daily budgets. Shared local hardware, not customer latency or proof of global optimality.",
  limitations: ["The initial artifact's capped and underloaded near-capacity rows are excluded, not repaired in place.",
    "Additional seeds compare only retained candidates. Other configurations have single-seed evidence.",
    "These fixture costs and workload metrics are modeled values and do not establish payroll savings."] };
await writeFile(`${outputPrefix}.json`, JSON.stringify(report, null, 2) + "\n", { flag: "wx" });
console.log(JSON.stringify(summaries, null, 2));
