"use client";

import { optimization, readResponse, errorMessage } from "@/lib/contracts";
import type { OptimizationRun } from "@/lib/engineClient";
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
  async function apply() {
    if (run.status !== "PREVIEW" || disabled || !window.confirm("Apply this exact optimization proposal?")) return;
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
      {run.status === "PREVIEW" && <button className={styles.applyButton} disabled={disabled || busy} onClick={() => void apply()}>{busy ? "Applying..." : "Apply proposal"}</button>}
    </div>
    {run.status === "SKIPPED" && <p><strong>Not applyable.</strong> {run.reason ?? "The optimizer did not produce an independently validated cost improvement."}</p>}
    {run.status !== "PREVIEW" && run.status !== "SKIPPED" && run.reason && <p>{run.reason}</p>}
    {message && <p role="status" className={message.startsWith("Applied") ? undefined : styles.error}>{message}</p>}
    <div className={styles.fleetTotals}>
      <span title={run.routing_identity}>Routing graph: {run.routing_identity.slice(0, 12)}</span>
      <span title={run.configuration_version}>Configuration: {run.configuration_version.slice(0, 12)}</span>
      <span>Drive: {total(run.route_summary_before, "drive_minutes")} to {total(run.route_summary_after, "drive_minutes")} min</span>
      <span>Paid route: {total(run.route_summary_before, "route_minutes")} to {total(run.route_summary_after, "route_minutes")} min</span>
      <span>Overtime: {total(run.route_summary_before, "overtime_minutes")} to {total(run.route_summary_after, "overtime_minutes")} min</span>
      <span>Waiting: {total(run.route_summary_before, "waiting_minutes")} to {total(run.route_summary_after, "waiting_minutes")} min</span>
      <span>Distance: {(total(run.route_summary_before, "distance_meters") / 1000).toFixed(1)} to {(total(run.route_summary_after, "distance_meters") / 1000).toFixed(1)} km</span>
      <span>Modeled cost: ${(total(run.route_summary_before, "modeled_cost_cents") / 100).toFixed(2)} to ${(total(run.route_summary_after, "modeled_cost_cents") / 100).toFixed(2)}</span>
    </div>
    <div className={styles.comparisonGrid}>{run.route_summary_before.map(before => {
      const after = run.route_summary_after.find(route => route.technician_id === before.technician_id);
      return <div className={styles.routeComparison} key={before.technician_id}><strong>{technicianName?.(before.technician_id) ?? before.technician_id}</strong>
        <span>{before.stop_count} to {after?.stop_count ?? 0} stops</span><span>{before.drive_minutes} to {after?.drive_minutes ?? 0} drive min</span>
        <span>{before.workload_minutes} to {after?.workload_minutes ?? 0} paid min</span></div>;
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
