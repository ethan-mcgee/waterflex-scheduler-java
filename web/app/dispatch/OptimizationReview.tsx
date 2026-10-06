"use client";

import { optimization, readResponse, errorMessage } from "@/lib/contracts";
import type { OptimizationRun } from "@/lib/engineClient";
import { formatCents } from "@/lib/money";
import { useEffect, useState, type ReactNode } from "react";
import styles from "@/app/dispatch/dispatch.module.css";

export type RoutePhase = "current" | "before" | "after";

export default function OptimizationReview({ run, technicianName, disabled = false, onApplied, routes }: {
  run: OptimizationRun;
  technicianName?: (id: string) => string;
  disabled?: boolean;
  onApplied: (run: OptimizationRun) => void | Promise<void>;
  routes?: (phase: RoutePhase) => ReactNode;
}) {
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<string | null>(null);
  const [phase, setPhase] = useState<RoutePhase>(run.status === "PREVIEW" ? "after" : "current");
  useEffect(() => { setPhase(run.status === "PREVIEW" ? "after" : "current"); setMessage(null); }, [run.run_id, run.status]);
  const total = (values: OptimizationRun["route_summary_before"], field: "drive_minutes" | "route_minutes" | "overtime_minutes" | "waiting_minutes" | "distance_meters" | "modeled_cost_cents") =>
    values.reduce((sum, route) => sum + route[field], 0);
  const beforeCost = run.fleet_cost_before_cents ?? run.policy_analysis?.before.costCents;
  const afterCost = run.fleet_cost_after_cents ?? run.policy_analysis?.after.costCents;
  const applicable = run.score_model_version === "bendable-decimal-repair-v2" && run.calculation_outcome?.policyEligible === true;
  async function apply() {
    if (run.status !== "PREVIEW" || !applicable || disabled || !window.confirm("Apply this exact optimization proposal?")) return;
    setBusy(true); setMessage(null);
    try {
      const response = await fetch("/api/dispatch/optimize/apply", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ runId: run.run_id }) });
      const applied = await readResponse(response, optimization);
      setPhase("current"); setMessage(`Applied ${applied.appointments_moved} appointment change(s).`);
      await onApplied(applied);
    } catch (error) { setMessage(errorMessage(error)); }
    finally { setBusy(false); }
  }
  return <section className={styles.optimizationPanel}>
    <div className={styles.optimizationHeader}>
      <div><strong>{run.status.replaceAll("_", " ")}</strong><span>Run {run.run_id.slice(0, 8)} | {run.solve_ms} ms</span></div>
      {run.status === "PREVIEW" && <button className={styles.applyButton} disabled={disabled || busy || !applicable} onClick={() => void apply()}>{busy ? "Applying..." : "Apply proposal"}</button>}
    </div>
    {run.status === "SKIPPED" && <p><strong>Not applyable.</strong> {run.reason ?? "The optimizer did not produce an independently validated policy improvement."}</p>}
    {run.calculation_outcome ? <p>Demand coverage: {run.calculation_outcome.assignedVisitIds.length} assigned,
      {" "}{run.calculation_outcome.unassignedVisitIds.length} unresolved. Served work independently validated:
      {" "}{run.calculation_outcome.assignedWorkFeasible ? "yes" : "no"}.
      {!run.calculation_outcome.complete && " A complete schedule is required before application."}</p>
      : <p>Coverage provenance unavailable. Generate a fresh preview before applying.</p>}
    {run.policy_analysis ? <div>
      <p>Policy: {run.policy_analysis.decision.reason.replaceAll("_", " ").toLowerCase()}.
        {" "}Modeled cost change: {run.policy_analysis.costChangeCents > 0 ? "+" : ""}${formatCents(run.policy_analysis.costChangeCents)}.
        {" "}Reference cost: ${formatCents(run.policy_analysis.decision.referenceCostCents)};
        {" "}allowed ceiling: ${formatCents(run.policy_analysis.decision.costCeilingCents)}.</p>
      <p>Workload variance: {run.policy_analysis.before.fairness.variance.toFixed(4)} to {run.policy_analysis.after.fairness.variance.toFixed(4)}.
        {" "}Maximum utilization: {(100 * run.policy_analysis.before.fairness.maximumUtilization).toFixed(1)}% to {(100 * run.policy_analysis.after.fairness.maximumUtilization).toFixed(1)}%.</p>
      {run.policy_analysis.after.fairness.workloads.map(workload => <p key={workload.technicianId}>
        {technicianName?.(workload.technicianId) ?? workload.technicianId}: {workload.paidMinutes} paid minutes /
        {" "}{workload.regularCapacityMinutes} regular capacity minutes ({(100 * workload.utilization).toFixed(1)}%)
      </p>)}
    </div> : <p>Policy metrics unavailable for this historical run. Generate a fresh preview before applying.</p>}
    {run.solver_analysis ? <details><summary>Search diagnostics</summary>
      {run.solver_analysis.phases.map((phase, index) => <p key={index}>{phase.name.toLowerCase()}: {phase.statistics.variant},
        {" "}{phase.statistics.solveMs} ms, {phase.statistics.moveEvaluations} moves evaluated,
        {" "}stopped by {phase.statistics.termination.replaceAll("_", " ").toLowerCase()}.
        {" "}Time to best: {phase.statistics.timeToBestMs == null ? "unavailable" : `${phase.statistics.timeToBestMs} ms`}.</p>)}
    </details> : <p>Solver diagnostics unavailable for this run.</p>}
    {run.status !== "PREVIEW" && run.status !== "SKIPPED" && run.reason && <p>{run.reason}</p>}
    {message && <p role="status" className={message.startsWith("Applied") ? undefined : styles.error}>{message}</p>}
    <div className={styles.fleetTotals}>
      <span title={run.routing_identity}>Routing graph: {run.routing_identity.slice(0, 12)}</span>
      <span title={run.configuration_version}>Configuration: {run.configuration_version.slice(0, 12)}</span>
      <span>Travel including buffers: {total(run.route_summary_before, "drive_minutes")} to {total(run.route_summary_after, "drive_minutes")} min</span>
      <span>Paid route: {total(run.route_summary_before, "route_minutes")} to {total(run.route_summary_after, "route_minutes")} min</span>
      <span>Overtime: {total(run.route_summary_before, "overtime_minutes")} to {total(run.route_summary_after, "overtime_minutes")} min</span>
      <span>Waiting: {total(run.route_summary_before, "waiting_minutes")} to {total(run.route_summary_after, "waiting_minutes")} min</span>
      <span>Distance: {(total(run.route_summary_before, "distance_meters") / 1000).toFixed(1)} to {(total(run.route_summary_after, "distance_meters") / 1000).toFixed(1)} km</span>
      <span>Modeled fleet cost: {beforeCost == null ? "unavailable" : `$${formatCents(beforeCost)}`} to {afterCost == null ? "unavailable" : `$${formatCents(afterCost)}`}</span>
    </div>
    <div className={styles.comparisonGrid}>{run.route_summary_before.map(before => {
      const after = run.route_summary_after.find(route => route.technician_id === before.technician_id);
      return <div className={styles.routeComparison} key={before.technician_id}><strong>{technicianName?.(before.technician_id) ?? before.technician_id}</strong>
        <span>{before.stop_count} to {after?.stop_count ?? "unavailable"} stops</span><span>{before.drive_minutes} to {after?.drive_minutes ?? "unavailable"} drive min including buffers</span>
        <span>{before.workload_minutes} to {after?.workload_minutes ?? "unavailable"} paid min</span>
        {before.travel_breakdown ? <span>Current road travel: {(before.travel_breakdown.road_seconds / 60).toFixed(1)} min;
          {" "}configured buffer: {(before.travel_breakdown.configured_buffer_seconds / 60).toFixed(1)} min;
          {" "}rounding: {(before.travel_breakdown.rounding_seconds / 60).toFixed(1)} min</span>
          : <span>Current road and buffer metrics unavailable.</span>}
        {after?.travel_breakdown ? <span>Proposed road travel: {(after.travel_breakdown.road_seconds / 60).toFixed(1)} min;
          {" "}configured buffer: {(after.travel_breakdown.configured_buffer_seconds / 60).toFixed(1)} min;
          {" "}rounding: {(after.travel_breakdown.rounding_seconds / 60).toFixed(1)} min</span>
          : <span>Separate road and buffer metrics unavailable.</span>}
        {after?.segments?.map((segment, index) => <span key={index}>Segment {index + 1}: depart {new Date(segment.departure).toLocaleTimeString("en-US", { timeZone: "America/Chicago", hour: "numeric", minute: "2-digit" })},
          {" "}return {new Date(segment.returned_at).toLocaleTimeString("en-US", { timeZone: "America/Chicago", hour: "numeric", minute: "2-digit" })}</span>)}</div>;
    })}</div>
    <div className={styles.changeList}><strong>Appointment changes</strong>
      {run.changes.length === 0 && <span>No appointment changes proposed.</span>}
      {run.changes.map(change => <span key={change.appointment_id}>{change.appointment_id.slice(0, 8)}: tech {change.from_technician_id.slice(0, 6)} to {change.to_technician_id.slice(0, 6)}, sequence {change.from_sequence + 1} to {change.to_sequence + 1}, arrival {change.from_planned_arrival_min} to {change.to_planned_arrival_min}</span>)}
    </div>
    {routes && <><div className={styles.routePhases} aria-label="Road route phase">
      <button aria-pressed={phase === "current"} onClick={() => setPhase("current")}>Current roads</button>
      <button aria-pressed={phase === "before"} onClick={() => setPhase("before")}>Before preview</button>
      <button aria-pressed={phase === "after"} onClick={() => setPhase("after")}>Proposed roads</button>
    </div>{routes(phase)}</>}
  </section>;
}
