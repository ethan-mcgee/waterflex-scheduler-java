import assert from "node:assert/strict";
import test from "node:test";
import type { OptimizationSimulationReport, SimulationMetrics } from "./engineClient";
import { aggregateValidation, analyzeQuickRun, incrementSeed, paidMinutesPerBookedAppointment, runValidationSequence, validationSeeds } from "./optimizationAnalysis";

const baseMetrics: SimulationMetrics = {
  requested_jobs: 100, booked_jobs: 90, rejected_jobs: 10, acceptance_rate: 0.9,
  drive_minutes: 900, drive_minutes_per_appointment: 10, total_paid_route_minutes: 1800,
  overtime_minutes: 0, mean_days_to_service: 2, mean_remaining_slack_minutes: 40,
  dispatch_drive_savings_minutes: 50, dispatch_paid_time_savings_minutes: 70,
  appointments_reassigned: 4, appointments_materially_retimed: 3, window_violations: 0,
  feasibility_violations: 0, simulation_runtime_ms: 100, solver_runtime_ms: 20, solver_fallbacks: 0,
};

function report(seed: number, overrides: Partial<Record<string, Partial<SimulationMetrics>>> = {}): OptimizationSimulationReport {
  const results: OptimizationSimulationReport["results"] = [];
  for (const profile of ["soonest", "route_friendly", "mixed"] as const) {
    for (const strategy of ["legacy", "enhanced_scoring", "lookahead"] as const) {
      const metrics = { ...baseMetrics, ...(strategy === "enhanced_scoring" ? { total_paid_route_minutes: 1620, drive_minutes_per_appointment: 9 } : {}), ...(strategy === "lookahead" ? { total_paid_route_minutes: 1710, drive_minutes_per_appointment: 8 } : {}), ...overrides[`${profile}:${strategy}`] };
      const deltas = Object.fromEntries(Object.keys(metrics).map((key) => [key, { absolute: 0, percent: 0 }])) as OptimizationSimulationReport["results"][number]["deltas_from_legacy"];
      results.push({ customer_profile: profile, strategy, metrics, deltas_from_legacy: deltas, warnings: [] });
    }
  }
  return { algorithm_version: "test-v1", config: { seed, job_count: 100, technician_count: 8, horizon_days: 10 }, results };
}

test("quick analysis recommends a safe cross-profile winner", () => {
  assert.equal(analyzeQuickRun(report(42)).recommendation.strategy, "enhanced_scoring");
});

test("customer tolerances and safety violations disqualify candidates", () => {
  const analysis = analyzeQuickRun(report(42, {
    "soonest:enhanced_scoring": { acceptance_rate: 0.879 },
    "route_friendly:enhanced_scoring": { mean_days_to_service: 2.51 },
    "mixed:enhanced_scoring": { window_violations: 1 },
    "soonest:lookahead": { window_violations: 1 },
    "route_friendly:lookahead": { window_violations: 1 },
    "mixed:lookahead": { window_violations: 1 },
  }));
  assert.equal(analysis.profileConclusions[0]!.gates.enhanced_scoring.acceptancePassed, false);
  assert.equal(analysis.profileConclusions[1]!.gates.enhanced_scoring.serviceDelayPassed, false);
  assert.equal(analysis.profileConclusions[2]!.gates.enhanced_scoring.safetyPassed, false);
  assert.equal(analysis.recommendation.strategy, null);
});

test("drive minutes break a paid-time tie within one percent", () => {
  const analysis = analyzeQuickRun(report(42, {
    "soonest:lookahead": { total_paid_route_minutes: 1610, drive_minutes_per_appointment: 8 },
    "route_friendly:lookahead": { total_paid_route_minutes: 1610, drive_minutes_per_appointment: 8 },
    "mixed:lookahead": { total_paid_route_minutes: 1610, drive_minutes_per_appointment: 8 },
  }));
  assert.equal(analysis.recommendation.strategy, "lookahead");
});

test("paid minutes per appointment safely handles zero bookings", () => {
  assert.equal(paidMinutesPerBookedAppointment({ ...baseMetrics, booked_jobs: 0 }), null);
});

test("validation increments and wraps unsigned 32-bit seeds", () => {
  assert.equal(incrementSeed(4_294_967_295, 1), 0);
  assert.deepEqual(validationSeeds(4_294_967_294).slice(0, 4), [4_294_967_294, 4_294_967_295, 0, 1]);
});

test("validation sequencing preserves completed runs when a later seed fails", async () => {
  const progress: number[] = [];
  const result = await runValidationSequence(10, async (runSeed) => {
    if (runSeed === 13) throw new Error("failed");
    return runSeed;
  }, (completed) => progress.push(completed.length));
  assert.deepEqual(result.completed, [10, 11, 12]);
  assert.equal(result.failedSeed, 13);
  assert.deepEqual(progress, [1, 2, 3]);
});

test("twenty consistent runs produce summaries and a validated recommendation", () => {
  const aggregate = aggregateValidation(Array.from({ length: 20 }, (_, index) => report(index)));
  assert.equal(aggregate.run_count, 20);
  assert.equal(aggregate.metric_summaries.soonest.enhanced_scoring.total_paid_route_minutes.mean, 1620);
  assert.equal(aggregate.metric_summaries.soonest.enhanced_scoring.total_paid_route_minutes.median, 1620);
  assert.equal(aggregate.metric_summaries.soonest.enhanced_scoring.total_paid_route_minutes.minimum, 1620);
  assert.equal(aggregate.metric_summaries.soonest.enhanced_scoring.total_paid_route_minutes.maximum, 1620);
  assert.equal(aggregate.strategy_win_count.mixed.enhanced_scoring, 20);
  assert.equal(aggregate.recommendation.strategy, "enhanced_scoring");
});

test("partial validation results are retained but inconclusive", () => {
  const aggregate = aggregateValidation([report(8), report(9)]);
  assert.deepEqual(aggregate.completed_seeds, [8, 9]);
  assert.equal(aggregate.recommendation.strategy, null);
  assert.match(aggregate.recommendation.explanation, /Only 2 of 20/);
});

test("one unsafe validation run makes the outcome inconclusive", () => {
  const runs = Array.from({ length: 20 }, (_, index) => report(index));
  runs[7] = report(7, { "soonest:enhanced_scoring": { feasibility_violations: 1 } });
  const aggregate = aggregateValidation(runs);
  assert.equal(aggregate.safety_gates.soonest.enhanced_scoring, false);
  assert.equal(aggregate.recommendation.strategy, null);
});
