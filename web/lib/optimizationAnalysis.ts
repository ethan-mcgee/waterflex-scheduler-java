import { required } from "./contracts";
import type { OptimizationSimulationReport, SimulationMetrics } from "./optimizationSimulationTypes";

export const VALIDATION_RUNS = 20;
export const MAX_SEED = 4_294_967_295;
export const CUSTOMER_PROFILES = ["soonest", "route_friendly", "mixed"] as const;
export const CANDIDATE_STRATEGIES = ["enhanced_scoring", "lookahead"] as const;

export type CustomerProfile = (typeof CUSTOMER_PROFILES)[number];
export type CandidateStrategy = (typeof CANDIDATE_STRATEGIES)[number];
export type Strategy = OptimizationSimulationReport["results"][number]["strategy"];
export type MetricSummary = { mean: number; median: number; minimum: number; maximum: number };
export type MetricSummaries = Record<CustomerProfile, Record<Strategy, Record<keyof SimulationMetrics, MetricSummary>>>;

export interface StrategyGate {
  safetyPassed: boolean;
  acceptancePassed: boolean;
  serviceDelayPassed: boolean;
  eligible: boolean;
}

export interface ProfileConclusion {
  profile: CustomerProfile;
  winner: CandidateStrategy | null;
  gates: Record<CandidateStrategy, StrategyGate>;
}

export interface Recommendation {
  strategy: CandidateStrategy | null;
  label: string;
  explanation: string;
}

export interface QuickAnalysis {
  mode: "quick";
  profileConclusions: ProfileConclusion[];
  recommendation: Recommendation;
}

export interface ValidationAggregate {
  mode: "validation";
  target_run_count: number;
  run_count: number;
  completed_seeds: number[];
  metric_summaries: MetricSummaries;
  strategy_win_count: Record<CustomerProfile, Record<CandidateStrategy, number>>;
  safety_gates: Record<CustomerProfile, Record<CandidateStrategy, boolean>>;
  customer_guardrails: Record<CustomerProfile, Record<CandidateStrategy, { acceptance_passed: boolean; service_delay_passed: boolean }>>;
  recommendation: Recommendation;
}

const metricKeys = [
  "requested_jobs", "booked_jobs", "rejected_jobs", "acceptance_rate", "drive_minutes",
  "drive_minutes_per_appointment", "total_paid_route_minutes", "overtime_minutes",
  "mean_days_to_service", "mean_remaining_slack_minutes", "dispatch_drive_savings_minutes",
  "dispatch_paid_time_savings_minutes", "appointments_reassigned",
  "appointments_materially_retimed", "window_violations", "feasibility_violations",
  "simulation_runtime_ms", "solver_runtime_ms", "solver_fallbacks",
] as const satisfies ReadonlyArray<keyof SimulationMetrics>;

export function incrementSeed(seed: number, offset: number): number {
  return (seed + offset) % (MAX_SEED + 1);
}

export function validationSeeds(firstSeed: number): number[] {
  return Array.from({ length: VALIDATION_RUNS }, (_, index) => incrementSeed(firstSeed, index));
}

export async function runValidationSequence<T>(firstSeed: number, runner: (seed: number) => Promise<T>, onProgress?: (completed: T[]) => void): Promise<{ completed: T[]; failedSeed: number | null; error: unknown }> {
  const completed: T[] = [];
  for (const seed of validationSeeds(firstSeed)) {
    try {
      completed.push(await runner(seed));
      onProgress?.([...completed]);
    } catch (error) {
      return { completed, failedSeed: seed, error };
    }
  }
  return { completed, failedSeed: null, error: null };
}

export function paidMinutesPerBookedAppointment(metrics: SimulationMetrics): number | null {
  return metrics.booked_jobs > 0 ? metrics.total_paid_route_minutes / metrics.booked_jobs : null;
}

export function metricMap<T>(fn: (key: keyof SimulationMetrics) => T) {
  return {
    requested_jobs: fn("requested_jobs"),
    booked_jobs: fn("booked_jobs"),
    rejected_jobs: fn("rejected_jobs"),
    acceptance_rate: fn("acceptance_rate"),
    drive_minutes: fn("drive_minutes"),
    drive_minutes_per_appointment: fn("drive_minutes_per_appointment"),
    total_paid_route_minutes: fn("total_paid_route_minutes"),
    overtime_minutes: fn("overtime_minutes"),
    mean_days_to_service: fn("mean_days_to_service"),
    mean_remaining_slack_minutes: fn("mean_remaining_slack_minutes"),
    dispatch_drive_savings_minutes: fn("dispatch_drive_savings_minutes"),
    dispatch_paid_time_savings_minutes: fn("dispatch_paid_time_savings_minutes"),
    appointments_reassigned: fn("appointments_reassigned"),
    appointments_materially_retimed: fn("appointments_materially_retimed"),
    window_violations: fn("window_violations"),
    feasibility_violations: fn("feasibility_violations"),
    simulation_runtime_ms: fn("simulation_runtime_ms"),
    solver_runtime_ms: fn("solver_runtime_ms"),
    solver_fallbacks: fn("solver_fallbacks"),
  };
}
function profileMap<T>(fn: (profile: CustomerProfile) => T) { return { soonest: fn("soonest"), route_friendly: fn("route_friendly"), mixed: fn("mixed") }; }
function candidateMap<T>(fn: (strategy: CandidateStrategy) => T) { return { enhanced_scoring: fn("enhanced_scoring"), lookahead: fn("lookahead") }; }
function resultFor(report: OptimizationSimulationReport, profile: CustomerProfile, strategy: Strategy) {
  const result = required(report.results.find((result) => result.customer_profile === profile && result.strategy === strategy), `${profile}/${strategy} simulation result`);
  for (const key of metricKeys) if (!Number.isFinite(result.metrics[key])) throw new Error(`Invalid simulation metric: ${key}`);
  return result;
}

function gate(candidate: SimulationMetrics, control: SimulationMetrics): StrategyGate {
  const safetyPassed = candidate.window_violations === 0 && candidate.feasibility_violations === 0;
  const acceptancePassed = candidate.acceptance_rate >= control.acceptance_rate - 0.02;
  const serviceDelayPassed = candidate.mean_days_to_service <= control.mean_days_to_service + 0.5;
  return { safetyPassed, acceptancePassed, serviceDelayPassed, eligible: safetyPassed && acceptancePassed && serviceDelayPassed };
}

function chooseWinner(report: OptimizationSimulationReport, profile: CustomerProfile): ProfileConclusion {
  const control = resultFor(report, profile, "legacy");
  if (!control) throw new Error(`Missing legacy result for ${profile}.`);
  const gates = candidateMap(strategy => gate(resultFor(report, profile, strategy).metrics, control.metrics));
  const eligible: Array<{ strategy: CandidateStrategy; paid: number; drive: number }> = [];
  for (const strategy of CANDIDATE_STRATEGIES) {
    const result = resultFor(report, profile, strategy);
    if (!result) throw new Error(`Missing ${strategy} result for ${profile}.`);
    gates[strategy] = gate(result.metrics, control.metrics);
    const paid = paidMinutesPerBookedAppointment(result.metrics);
    if (gates[strategy].eligible && paid !== null) eligible.push({ strategy, paid, drive: result.metrics.drive_minutes_per_appointment });
  }
  if (!eligible.length) return { profile, winner: null, gates };
  const lowestPaid = Math.min(...eligible.map((entry) => entry.paid));
  const contenders = eligible.filter((entry) => entry.paid <= lowestPaid * 1.01);
  contenders.sort((a, b) => a.drive - b.drive || a.paid - b.paid);
  return { profile, winner: required(contenders[0]).strategy, gates };
}

function strategyLabel(strategy: CandidateStrategy) {
  return strategy === "enhanced_scoring" ? "Enhanced scoring" : "Lookahead";
}

export function analyzeQuickRun(report: OptimizationSimulationReport): QuickAnalysis {
  const profileConclusions = CUSTOMER_PROFILES.map((profile) => chooseWinner(report, profile));
  const qualifying = CANDIDATE_STRATEGIES.filter((strategy) => {
    const wins = profileConclusions.filter((conclusion) => conclusion.winner === strategy).length;
    if (wins < 2) return false;
    return CUSTOMER_PROFILES.every((profile) => {
      const candidate = required(resultFor(report, profile, strategy));
      const control = required(resultFor(report, profile, "legacy"));
      const candidatePaid = paidMinutesPerBookedAppointment(candidate.metrics);
      const controlPaid = paidMinutesPerBookedAppointment(control.metrics);
      return candidatePaid !== null && controlPaid !== null && candidatePaid <= controlPaid * 1.01;
    });
  });
  const strategy = qualifying.sort((a, b) => {
    const wins = (value: CandidateStrategy) => profileConclusions.filter((item) => item.winner === value).length;
    return wins(b) - wins(a);
  })[0] ?? null;
  return {
    mode: "quick",
    profileConclusions,
    recommendation: strategy
      ? { strategy, label: strategyLabel(strategy), explanation: `${strategyLabel(strategy)} won at least two customer profiles while staying within the customer, safety, and remaining-profile efficiency limits.` }
      : { strategy: null, label: "No clear winner", explanation: "No strategy met the cross-profile recommendation rules in this scenario." },
  };
}

function summarize(values: number[]): MetricSummary {
  if (!values.length || values.some(v => !Number.isFinite(v))) throw new Error("Complete finite metrics required");
  const sorted = [...values].sort((a, b) => a - b);
  const middle = Math.floor(sorted.length / 2);
  const median = sorted.length % 2 ? required(sorted[middle]) : (required(sorted[middle - 1]) + required(sorted[middle])) / 2;
  return { mean: values.reduce((sum, value) => sum + value, 0) / values.length, median, minimum: required(sorted[0]), maximum: required(sorted.at(-1)) };
}

export function aggregateValidation(runs: OptimizationSimulationReport[]): ValidationAggregate {
  if (!runs.length) throw new Error("At least one completed run is required.");
  const summarizeStrategy = (profile: CustomerProfile, strategy: Strategy) => metricMap(key => summarize(runs.map(run => resultFor(run, profile, strategy).metrics[key])));
  const metric_summaries = profileMap(profile => ({ legacy: summarizeStrategy(profile, "legacy"), ...candidateMap(strategy => summarizeStrategy(profile, strategy)) }));
  const strategy_win_count = profileMap(profile => candidateMap(strategy => runs.filter(run => chooseWinner(run, profile).winner === strategy).length));
  const safety_gates = profileMap(profile => candidateMap(strategy => runs.every(run => {
    const metrics = resultFor(run, profile, strategy).metrics;
    return metrics.window_violations === 0 && metrics.feasibility_violations === 0;
  })));
  const customer_guardrails = profileMap(profile => candidateMap(strategy => {
    const control = metric_summaries[profile].legacy, candidate = metric_summaries[profile][strategy];
    return { acceptance_passed: candidate.acceptance_rate.mean >= control.acceptance_rate.mean - 0.02,
      service_delay_passed: candidate.mean_days_to_service.mean <= control.mean_days_to_service.mean + 0.5 };
  }));

  const qualifying = runs.length === VALIDATION_RUNS ? CANDIDATE_STRATEGIES.filter((strategy) => {
    const profilesWon = CUSTOMER_PROFILES.filter((profile) => strategy_win_count[profile][strategy] >= 14).length;
    return profilesWon >= 2 && CUSTOMER_PROFILES.every((profile) => safety_gates[profile][strategy]
      && customer_guardrails[profile][strategy].acceptance_passed
      && customer_guardrails[profile][strategy].service_delay_passed);
  }) : [];
  qualifying.sort((a, b) => {
    const wins = (strategy: CandidateStrategy) => CUSTOMER_PROFILES.reduce((sum, profile) => sum + strategy_win_count[profile][strategy], 0);
    return wins(b) - wins(a);
  });
  const strategy = qualifying[0] ?? null;
  return {
    mode: "validation",
    target_run_count: VALIDATION_RUNS,
    run_count: runs.length,
    completed_seeds: runs.map((run) => run.config.seed),
    metric_summaries,
    strategy_win_count,
    safety_gates,
    customer_guardrails,
    recommendation: strategy
      ? { strategy, label: strategyLabel(strategy), explanation: `${strategyLabel(strategy)} won at least 14 of 20 seeds in at least two profiles, stayed safe in every run, and passed the aggregate customer guardrails.` }
      : { strategy: null, label: "Results are inconclusive", explanation: runs.length < VALIDATION_RUNS ? `Only ${runs.length} of 20 runs completed, so no validated recommendation can be made.` : "No strategy met every validation threshold across safety, customer access, and repeatable profile wins." },
  };
}
