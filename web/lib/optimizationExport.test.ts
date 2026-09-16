import assert from "node:assert/strict";
import test from "node:test";
import type { OptimizationSimulationReport, SimulationMetrics } from "./engineClient";
import { aggregateValidation } from "./optimizationAnalysis";
import { EXPORT_METRIC_KEYS, serializeOptimizationCsv, serializeOptimizationJson, serializeValidationCsv, serializeValidationJson } from "./optimizationExport";

const metrics = Object.fromEntries(EXPORT_METRIC_KEYS.map((key, index) => [key, index])) as unknown as SimulationMetrics;
const deltas = Object.fromEntries(EXPORT_METRIC_KEYS.map((key) => [key, { absolute: 0, percent: null }])) as OptimizationSimulationReport["results"][number]["deltas_from_legacy"];
const report: OptimizationSimulationReport = {
  algorithm_version: "test-v1",
  config: { seed: 42, job_count: 10, technician_count: 2, horizon_days: 3 },
  results: [{ strategy: "legacy", customer_profile: "soonest", metrics, deltas_from_legacy: deltas, warnings: [] }],
};

test("JSON export retains reproducibility metadata and raw metrics", () => {
  const value = JSON.parse(serializeOptimizationJson(report));
  assert.equal(value.algorithm_version, "test-v1");
  assert.equal(value.config.seed, 42);
  assert.equal(value.results[0].metrics.booked_jobs, metrics.booked_jobs);
});

test("CSV export includes configuration, metrics, and both delta forms", () => {
  const value = serializeOptimizationCsv(report);
  assert.match(value, /"algorithm_version","seed","job_count"/);
  assert.match(value, /"booked_jobs","booked_jobs_delta","booked_jobs_delta_percent"/);
  assert.match(value, /"test-v1","42","10","2","3","soonest","legacy"/);
});

test("validation JSON includes aggregate conclusions and every raw run", () => {
  const completeReport: OptimizationSimulationReport = {
    ...report,
    results: (["soonest", "route_friendly", "mixed"] as const).flatMap((customer_profile) =>
      (["legacy", "enhanced_scoring", "lookahead"] as const).map((strategy) => ({
        ...report.results[0]!, customer_profile, strategy,
      }))),
  };
  const runs = Array.from({ length: 20 }, (_, seed) => ({ ...completeReport, config: { ...completeReport.config, seed } }));
  const aggregate = aggregateValidation(runs);
  const value = JSON.parse(serializeValidationJson(aggregate, runs));
  assert.equal(value.aggregate.run_count, 20);
  assert.equal(value.raw_runs.length, 20);
  assert.ok(value.aggregate.recommendation);
});

test("validation CSV has one complete row per seed, profile, and strategy", () => {
  const completeReport: OptimizationSimulationReport = {
    ...report,
    results: (["soonest", "route_friendly", "mixed"] as const).flatMap((customer_profile) =>
      (["legacy", "enhanced_scoring", "lookahead"] as const).map((strategy) => ({
        ...report.results[0]!, customer_profile, strategy,
      }))),
  };
  const runs = Array.from({ length: 20 }, (_, seed) => ({ ...completeReport, config: { ...completeReport.config, seed } }));
  const aggregate = aggregateValidation(runs);
  const lines = serializeValidationCsv(aggregate, runs).split("\n");
  assert.equal(lines.length, 181);
  assert.match(lines[0]!, /"validation_recommendation"/);
  assert.match(lines[0]!, /"booked_jobs_delta_percent"/);
});
