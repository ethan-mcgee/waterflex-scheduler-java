"use client";

import Link from "next/link";
import { useMemo, useState } from "react";
import type { OptimizationSimulationReport, SimulationMetrics } from "@/lib/engineClient";
import { aggregateValidation, analyzeQuickRun, CANDIDATE_STRATEGIES, CUSTOMER_PROFILES, runValidationSequence, type CustomerProfile, type QuickAnalysis, type ValidationAggregate } from "@/lib/optimizationAnalysis";
import { serializeOptimizationCsv, serializeOptimizationJson, serializeValidationCsv, serializeValidationJson } from "@/lib/optimizationExport";
import styles from "./optimizationTest.module.css";
import CaliforniaReplay from "./CaliforniaReplay";

type MetricKey = keyof SimulationMetrics;
type Metric = { key: MetricKey; label: string; short: string; definition: string; format?: "percent" | "decimal" | "integer" };
type Mode = "quick" | "validation";

const METRICS: Metric[] = [
  { key: "requested_jobs", label: "Requested jobs", short: "Requested", definition: "Customer requests presented to the simulation.", format: "integer" },
  { key: "booked_jobs", label: "Booked jobs", short: "Booked", definition: "Requests committed to a feasible technician and window.", format: "integer" },
  { key: "rejected_jobs", label: "Rejected jobs", short: "Rejected", definition: "Requests with no feasible offer remaining.", format: "integer" },
  { key: "acceptance_rate", label: "Acceptance rate", short: "Acceptance", definition: "Booked jobs divided by requested jobs.", format: "percent" },
  { key: "drive_minutes", label: "Drive minutes", short: "Drive", definition: "Full route drive time, including return to the depot.", format: "integer" },
  { key: "drive_minutes_per_appointment", label: "Drive minutes per appointment", short: "Drive / appt", definition: "Full route drive time divided by booked jobs.", format: "decimal" },
  { key: "total_paid_route_minutes", label: "Total paid route time", short: "Paid route", definition: "Time from each used shift start through return to the depot.", format: "integer" },
  { key: "overtime_minutes", label: "Overtime", short: "Overtime", definition: "Route time beyond each technician's regular daily limit.", format: "integer" },
  { key: "mean_days_to_service", label: "Mean days to service", short: "Days out", definition: "Average calendar days from request to service.", format: "decimal" },
  { key: "mean_remaining_slack_minutes", label: "Remaining route slack", short: "Slack", definition: "Average minutes remaining in the customer window at arrival.", format: "decimal" },
  { key: "dispatch_drive_savings_minutes", label: "Later dispatch drive savings", short: "Drive saved", definition: "Booking-time drive minutes minus post-booking optimizer drive minutes.", format: "integer" },
  { key: "dispatch_paid_time_savings_minutes", label: "Later dispatch paid-time savings", short: "Paid saved", definition: "Booking-time paid route minutes minus post-booking optimizer paid route minutes.", format: "integer" },
  { key: "appointments_reassigned", label: "Technician reassignments", short: "Reassigned", definition: "Appointments moved to another technician by dispatch optimization.", format: "integer" },
  { key: "appointments_materially_retimed", label: "Material retiming", short: "Retimed", definition: "Appointments shifted by more than 15 minutes.", format: "integer" },
  { key: "window_violations", label: "Window violations", short: "Window violations", definition: "Final routes arriving outside a promised customer window.", format: "integer" },
  { key: "feasibility_violations", label: "Feasibility violations", short: "Feasibility", definition: "Routes returning after the allowed shift and overtime limit.", format: "integer" },
  { key: "simulation_runtime_ms", label: "Simulation runtime", short: "Simulation ms", definition: "Wall-clock runtime for booking and dispatch evaluation.", format: "integer" },
  { key: "solver_runtime_ms", label: "Solver runtime", short: "Solver ms", definition: "Time spent in the later dispatch solver.", format: "integer" },
  { key: "solver_fallbacks", label: "Solver fallbacks", short: "Fallbacks", definition: "Days where dispatch retained the booking route because no solution returned.", format: "integer" },
];

const GROUPS: Array<{ name: string; keys: MetricKey[] }> = [
  { name: "Customer", keys: ["requested_jobs", "booked_jobs", "rejected_jobs", "acceptance_rate", "mean_days_to_service"] },
  { name: "Efficiency", keys: ["drive_minutes", "drive_minutes_per_appointment", "total_paid_route_minutes", "overtime_minutes", "dispatch_drive_savings_minutes", "dispatch_paid_time_savings_minutes"] },
  { name: "Safety", keys: ["window_violations", "feasibility_violations", "mean_remaining_slack_minutes", "solver_fallbacks"] },
  { name: "Disruption", keys: ["appointments_reassigned", "appointments_materially_retimed"] },
  { name: "Runtime", keys: ["simulation_runtime_ms", "solver_runtime_ms"] },
];

const strategyName = (value: string) => ({ legacy: "Current control", enhanced_scoring: "Enhanced scoring", lookahead: "Lookahead" }[value] ?? value);
const profileName = (value: string) => ({ soonest: "Soonest", route_friendly: "Route-friendly", mixed: "Mixed" }[value] ?? value);
const metricByKey = (key: MetricKey) => METRICS.find((metric) => metric.key === key)!;

function valueText(value: number | null, metric: Metric) {
  if (value === null || !Number.isFinite(value)) return "Not available";
  if (metric.format === "percent") return `${(value * 100).toFixed(1)}%`;
  if (metric.format === "decimal") return value.toFixed(1);
  return Math.round(value).toLocaleString();
}

function download(name: string, content: string, type: string) {
  const link = document.createElement("a");
  link.href = URL.createObjectURL(new Blob([content], { type }));
  link.download = name;
  link.click();
  URL.revokeObjectURL(link.href);
}

function metricValue(runs: OptimizationSimulationReport[], aggregate: ValidationAggregate | null, profile: CustomerProfile, strategy: "legacy" | "enhanced_scoring" | "lookahead", key: MetricKey) {
  if (aggregate) return aggregate.metric_summaries[profile][strategy][key].mean;
  return runs[0]!.results.find((result) => result.customer_profile === profile && result.strategy === strategy)!.metrics[key];
}

function paidPerAppointment(runs: OptimizationSimulationReport[], aggregate: ValidationAggregate | null, profile: CustomerProfile, strategy: "legacy" | "enhanced_scoring" | "lookahead") {
  const booked = metricValue(runs, aggregate, profile, strategy, "booked_jobs");
  const paid = metricValue(runs, aggregate, profile, strategy, "total_paid_route_minutes");
  return booked > 0 ? paid / booked : null;
}

function ComparisonChart({ title, metric, runs, aggregate }: { title: string; metric: MetricKey | "paid_per_appointment"; runs: OptimizationSimulationReport[]; aggregate: ValidationAggregate | null }) {
  const displayMetric = metric === "paid_per_appointment" ? { ...metricByKey("total_paid_route_minutes"), format: "decimal" as const } : metricByKey(metric);
  const rows = CUSTOMER_PROFILES.flatMap((profile) => (["legacy", ...CANDIDATE_STRATEGIES] as const).map((strategy) => ({ profile, strategy, value: metric === "paid_per_appointment" ? paidPerAppointment(runs, aggregate, profile, strategy) : metricValue(runs, aggregate, profile, strategy, metric) })));
  const max = Math.max(...rows.map((row) => row.value ?? 0), 1);
  return <section className={styles.chart} aria-label={`${title} chart`}><h3>{title}</h3>{CUSTOMER_PROFILES.map((profile) => <div className={styles.chartGroup} key={profile}><strong>{profileName(profile)}</strong>{rows.filter((row) => row.profile === profile).map((row) => <div className={styles.barRow} key={row.strategy}><span>{strategyName(row.strategy)}</span><div><i className={styles[row.strategy]} style={{ width: `${((row.value ?? 0) / max) * 100}%` }} /></div><b>{valueText(row.value, displayMetric)}</b></div>)}</div>)}</section>;
}

function Scenario({ profile, runs, aggregate, quick }: { profile: CustomerProfile; runs: OptimizationSimulationReport[]; aggregate: ValidationAggregate | null; quick: QuickAnalysis | null }) {
  const winner = quick?.profileConclusions.find((item) => item.profile === profile)?.winner ?? null;
  const strategies = ["legacy", ...CANDIDATE_STRATEGIES] as const;
  const v = (strategy: (typeof strategies)[number], key: MetricKey) => metricValue(runs, aggregate, profile, strategy, key);
  const cards = [
    { title: "Did we preserve customer access?", rows: (s: (typeof strategies)[number]) => [`${valueText(v(s, "acceptance_rate"), metricByKey("acceptance_rate"))} accepted`, `${valueText(v(s, "mean_days_to_service"), metricByKey("mean_days_to_service"))} mean days to service`] },
    { title: "Did we improve efficiency?", rows: (s: (typeof strategies)[number]) => [`${valueText(paidPerAppointment(runs, aggregate, profile, s), { ...metricByKey("total_paid_route_minutes"), format: "decimal" })} paid min / booked appt`, `${valueText(v(s, "drive_minutes_per_appointment"), metricByKey("drive_minutes_per_appointment"))} drive min / appt`, `${valueText(v(s, "overtime_minutes"), metricByKey("overtime_minutes"))} overtime min`, `${valueText(v(s, "dispatch_paid_time_savings_minutes"), metricByKey("dispatch_paid_time_savings_minutes"))} later dispatch min saved`] },
    { title: "Did we keep valid promises?", rows: (s: (typeof strategies)[number]) => [`${valueText(v(s, "window_violations"), metricByKey("window_violations"))} window violations`, `${valueText(v(s, "feasibility_violations"), metricByKey("feasibility_violations"))} feasibility violations`, `${valueText(v(s, "mean_remaining_slack_minutes"), metricByKey("mean_remaining_slack_minutes"))} min mean slack`, `${valueText(v(s, "solver_fallbacks"), metricByKey("solver_fallbacks"))} solver fallbacks`] },
    { title: "What operational disruption did it require?", rows: (s: (typeof strategies)[number]) => [`${valueText(v(s, "appointments_reassigned"), metricByKey("appointments_reassigned"))} technician reassignments`, `${valueText(v(s, "appointments_materially_retimed"), metricByKey("appointments_materially_retimed"))} material retimings`] },
  ];
  return <section className={styles.scenario}><div className={styles.scenarioTitle}><div><p>Customer profile</p><h3>{profileName(profile)}</h3></div>{winner && <span>{strategyName(winner)} leads this profile</span>}</div><div className={styles.storyGrid}>{cards.map((card) => <article key={card.title}><h4>{card.title}</h4><div className={styles.strategyColumns}>{strategies.map((strategy) => <div key={strategy}><strong>{strategyName(strategy)}</strong>{card.rows(strategy).map((row) => <span key={row}>{row}</span>)}</div>)}</div></article>)}</div></section>;
}

function RawTable({ report }: { report: OptimizationSimulationReport }) {
  return <div className={styles.tableWrap}><table><caption>Absolute results and differences from the current control within each customer profile</caption><thead><tr><th>Seed</th><th>Profile</th><th>Strategy</th>{METRICS.map((metric) => <th key={metric.key}><abbr title={metric.label}>{metric.short}</abbr></th>)}</tr></thead><tbody>{report.results.map((result) => <tr key={`${result.customer_profile}-${result.strategy}`}><td>{report.config.seed}</td><th scope="row">{profileName(result.customer_profile)}</th><td>{strategyName(result.strategy)}</td>{METRICS.map((metric) => { const delta = result.deltas_from_legacy[metric.key]; const absolute = metric.format === "percent" ? `${(delta.absolute * 100).toFixed(1)} pp` : valueText(delta.absolute, metric); const percent = delta.percent === null ? "n/a" : `${delta.percent.toFixed(1)}%`; return <td key={metric.key}><strong>{valueText(result.metrics[metric.key], metric)}</strong><span>{result.strategy === "legacy" ? "Control" : `${delta.absolute > 0 ? "+" : ""}${absolute} (${delta.percent !== null && delta.percent > 0 ? "+" : ""}${percent})`}</span></td>; })}</tr>)}</tbody></table></div>;
}

export default function OptimizationTestDashboard() {
  const [jobs, setJobs] = useState("250");
  const [technicians, setTechnicians] = useState("8");
  const [horizon, setHorizon] = useState("10");
  const [seed, setSeed] = useState("42");
  const [mode, setMode] = useState<Mode>("quick");
  const [resultMode, setResultMode] = useState<Mode>("quick");
  const [runs, setRuns] = useState<OptimizationSimulationReport[]>([]);
  const [busy, setBusy] = useState(false);
  const [progress, setProgress] = useState(0);
  const [error, setError] = useState("");
  const aggregate = useMemo(() => resultMode === "validation" && runs.length ? aggregateValidation(runs) : null, [resultMode, runs]);
  const quick = useMemo(() => resultMode === "quick" && runs.length ? analyzeQuickRun(runs[0]!) : null, [resultMode, runs]);
  const recommendation = aggregate?.recommendation ?? quick?.recommendation;

  async function requestRun(runSeed: number) {
    const response = await fetch("/api/optimization-test", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ jobCount: Number(jobs), technicianCount: Number(technicians), horizonDays: Number(horizon), seed: runSeed }) });
    const data = await response.json() as OptimizationSimulationReport & { error?: string };
    if (!response.ok) throw new Error(data.error ?? "Simulation failed.");
    return data;
  }

  async function run(event: React.FormEvent) {
    event.preventDefault(); setBusy(true); setError(""); setRuns([]); setProgress(0); setResultMode(mode);
    try {
      if (mode === "quick") {
        const report = await requestRun(Number(seed)); setRuns([report]); setProgress(1);
      } else {
        const result = await runValidationSequence(Number(seed), requestRun, (completed) => { setRuns(completed); setProgress(completed.length); });
        if (result.error) {
          const message = result.error instanceof Error ? result.error.message : "Simulation failed.";
          setError(`${message} Seed ${result.failedSeed} failed. ${result.completed.length} completed run${result.completed.length === 1 ? " was" : "s were"} preserved.`);
        }
      }
    } catch (caught) {
      setError(caught instanceof Error ? caught.message : "Simulation failed.");
    } finally { setBusy(false); }
  }

  const warnings = runs.flatMap((report) => report.results.flatMap((result) => result.warnings.map((warning) => `Seed ${report.config.seed}, ${profileName(result.customer_profile)}, ${strategyName(result.strategy)}: ${warning}`)));
  const disruption = recommendation?.strategy ? CUSTOMER_PROFILES.reduce((totals, profile) => ({ reassigned: totals.reassigned + metricValue(runs, aggregate, profile, recommendation.strategy!, "appointments_reassigned"), retimed: totals.retimed + metricValue(runs, aggregate, profile, recommendation.strategy!, "appointments_materially_retimed") }), { reassigned: 0, retimed: 0 }) : null;
  const primaryBenefit = recommendation?.strategy ? (() => { const candidate = CUSTOMER_PROFILES.reduce((sum, profile) => sum + (paidPerAppointment(runs, aggregate, profile, recommendation.strategy!) ?? 0), 0) / CUSTOMER_PROFILES.length; const control = CUSTOMER_PROFILES.reduce((sum, profile) => sum + (paidPerAppointment(runs, aggregate, profile, "legacy") ?? 0), 0) / CUSTOMER_PROFILES.length; const difference = control - candidate; return difference >= 0 ? `${strategyName(recommendation.strategy)} used ${difference.toFixed(1)} fewer paid minutes per booked appointment than the current control across customer profiles.` : `${strategyName(recommendation.strategy)} stayed within ${Math.abs(difference).toFixed(1)} paid minutes per booked appointment of the current control and won on the defined near-tie criteria.`; })() : "No strategy showed a sufficiently consistent efficiency advantage while protecting customer access and valid promises.";
  const exportJson = () => aggregate ? download(`optimization-validation-${runs[0]!.config.seed}.json`, serializeValidationJson(aggregate, runs), "application/json") : download(`optimization-${runs[0]!.config.seed}.json`, serializeOptimizationJson(runs[0]!), "application/json");
  const exportCsv = () => aggregate ? download(`optimization-validation-${runs[0]!.config.seed}.csv`, serializeValidationCsv(aggregate, runs), "text/csv") : download(`optimization-${runs[0]!.config.seed}.csv`, serializeOptimizationCsv(runs[0]!), "text/csv");

  return <main className={styles.page}>
    <header className={styles.header}><Link href="/">WaterFlex Scheduler</Link><h1>Booking optimization test</h1><p>Compare synthetic booking strategies while keeping the current scheduler as the control.</p></header>
    <CaliforniaReplay />
    <form className={styles.controls} onSubmit={run}>
      <fieldset className={styles.modeSelector}><legend>Test mode</legend><label><input type="radio" name="mode" checked={mode === "quick"} onChange={() => setMode("quick")} />Quick run<span>One scenario for exploration</span></label><label><input type="radio" name="mode" checked={mode === "validation"} onChange={() => setMode("validation")} />Validate strategy<span>20 sequential seeds</span></label></fieldset>
      <label>Jobs<input aria-label="Jobs" type="number" min="10" max="1000" value={jobs} onChange={(event) => setJobs(event.target.value)} /></label><label>Technicians<input aria-label="Technicians" type="number" min="1" max="25" value={technicians} onChange={(event) => setTechnicians(event.target.value)} /></label><label>Horizon days<input aria-label="Horizon days" type="number" min="1" max="31" value={horizon} onChange={(event) => setHorizon(event.target.value)} /></label><label>First seed<input aria-label="Seed" type="number" min="0" max="4294967295" value={seed} onChange={(event) => setSeed(event.target.value)} /></label>
      <button disabled={busy}>{busy ? (mode === "validation" ? `Run ${Math.min(progress + 1, 20)} of 20` : "Running comparison...") : mode === "validation" ? "Validate strategy" : "Run comparison"}</button>
    </form>
    {busy && mode === "validation" && <div className={styles.progress} role="status"><div><i style={{ width: `${progress / 20 * 100}%` }} /></div><strong>Run {Math.min(progress + 1, 20)} of 20</strong><span>{progress} completed</span></div>}
    <p className={styles.profileNote}><strong>Customer profiles:</strong> Soonest selects the earliest offered window. Route-friendly selects the first optimizer-ranked offer. Mixed uses 60% soonest, 25% first-ranked, and 15% seeded random selection.</p>
    {error && <p className={styles.error} role="alert">{error}</p>}
    {runs.length > 0 && recommendation && <>
      <div className={styles.resultHeader}><div><h2>Comparison results</h2><p>{aggregate ? `${aggregate.run_count} of 20 runs completed` : `Seed ${runs[0]!.config.seed}`} | {runs[0]!.config.job_count} jobs | {runs[0]!.config.technician_count} technicians | {runs[0]!.algorithm_version}</p></div><div><button onClick={exportJson}>Download JSON</button><button onClick={exportCsv}>Download CSV</button></div></div>
      <section className={`${styles.verdict} ${recommendation.strategy ? styles.positiveVerdict : styles.neutralVerdict}`}><div className={styles.verdictTop}><p>What this test says</p><span>{aggregate ? (aggregate.run_count === 20 ? "Validated across 20 runs" : `Partial validation: ${aggregate.run_count} of 20`) : "This scenario only"}</span></div><h2>{recommendation.label}</h2><p className={styles.explanation}>{primaryBenefit} {recommendation.explanation}</p><div className={styles.qualifications}><div><strong>Customer impact</strong><span>Acceptance must stay within 2 points and service delay within 0.5 day of the current control.</span></div><div><strong>Safety</strong><span>Any customer-window or route-feasibility violation disqualifies a strategy.</span></div><div><strong>Dispatch tradeoff</strong><span>{disruption ? `${Math.round(disruption.reassigned)} reassignments and ${Math.round(disruption.retimed)} material retimings across the three profile averages.` : "Reassignments and material retiming remain visible tradeoffs, not automatic disqualifiers."}</span></div></div>{warnings.length > 0 && <div className={styles.inlineWarning}><strong>Warnings that qualify this conclusion</strong><ul>{warnings.map((warning, index) => <li key={`${index}-${warning}`}>{warning}</li>)}</ul></div>}</section>
      <div className={styles.charts}><ComparisonChart title="Customer acceptance" metric="acceptance_rate" runs={runs} aggregate={aggregate} /><ComparisonChart title="Paid minutes per booked appointment" metric="paid_per_appointment" runs={runs} aggregate={aggregate} /></div>
      <section className={styles.profiles}><div className={styles.sectionIntro}><p>Evidence by customer behavior</p><h2>How each scenario performed</h2></div>{CUSTOMER_PROFILES.map((profile) => <Scenario key={profile} profile={profile} runs={runs} aggregate={aggregate} quick={quick} />)}</section>
      {aggregate && <section className={styles.validationEvidence}><h2>Validation evidence</h2><p>Strategy wins by profile. A recommendation requires at least 14 wins in at least two profiles.</p><div>{CUSTOMER_PROFILES.map((profile) => <article key={profile}><strong>{profileName(profile)}</strong>{CANDIDATE_STRATEGIES.map((strategy) => <span key={strategy}>{strategyName(strategy)}: {aggregate.strategy_win_count[profile][strategy]} of 20</span>)}</article>)}</div></section>}
      <details className={styles.engineering}><summary>Engineering evidence</summary><p>Values are {aggregate ? "means across completed runs" : "from this seed"}. Every conclusion above is supported by these metrics.</p><div className={styles.metricGroups}>{GROUPS.map((group) => <section key={group.name}><h3>{group.name}</h3>{group.keys.map((key) => <div className={styles.metricLine} key={key}><strong>{metricByKey(key).label}</strong>{CUSTOMER_PROFILES.map((profile) => <span key={profile}>{profileName(profile)}: {CANDIDATE_STRATEGIES.map((strategy) => `${strategyName(strategy)} ${valueText(metricValue(runs, aggregate, profile, strategy, key), metricByKey(key))}`).join("; ")}</span>)}</div>)}</section>)}</div></details>
      <details className={styles.raw}><summary>Raw results</summary>{runs.map((report) => <RawTable report={report} key={report.config.seed} />)}</details>
      <details className={styles.definitions}><summary>Metric definitions</summary><dl>{METRICS.map((metric) => <div key={metric.key}><dt>{metric.label}</dt><dd>{metric.definition}</dd></div>)}</dl></details>
    </>}
  </main>;
}
