"use client";

import { readResponse, optimization, optimizationRuns, errorMessage } from "@/lib/contracts";
import dynamic from "next/dynamic";
import { useRouter } from "next/navigation";
import { useCallback, useEffect, useState } from "react";
import styles from "@/app/dispatch/dispatch.module.css";
import { TECH_COLORS } from "@/app/dispatch/colors";
import type { BoardAppointment, BoardTechnician } from "@/app/dispatch/types";
import type { OptimizationRun } from "@/lib/engineClient";

const DispatchMap = dynamic(() => import("@/app/dispatch/DispatchMap"), {
  ssr: false,
  loading: () => <div className={styles.empty}>Loading map...</div>,
});

function formatTime(iso: string, timezone: string) {
  return new Intl.DateTimeFormat("en-US", {
    timeZone: timezone,
    hour: "numeric",
    minute: "2-digit",
  }).format(new Date(iso));
}

function AppointmentCard({ appointment, timezone }: {
  appointment: BoardAppointment;
  timezone: string;
}) {
  return (
    <div className={styles.card}>
      <div className={styles.cardTime}>
        {formatTime(appointment.plannedStart, timezone)} (window{" "}
        {formatTime(appointment.windowStart, timezone)} to{" "}
        {formatTime(appointment.windowEnd, timezone)})
      </div>
      <div className={styles.cardCustomer}>{appointment.customerName}</div>
      <div className={styles.cardMeta}>{appointment.serviceName}</div>
      <div className={styles.cardMeta}>{appointment.addressLine}</div>
      {(appointment.lat === null || appointment.lng === null) && <p role="alert">Location data missing. Routing is blocked.</p>}
    </div>
  );
}

export default function DispatchBoard({ metroId, timezone, date, technicians, appointments, initialRunId }: {
  metroId: string;
  timezone: string;
  date: string;
  technicians: BoardTechnician[];
  appointments: BoardAppointment[];
  initialRunId?: string;
}) {
  const router = useRouter();
  const [invalidResponse, setInvalidResponse] = useState(false);
  const [busy, setBusy] = useState(false);
  const [status, setStatus] = useState<string | null>(null);
  const [preview, setPreview] = useState<OptimizationRun | null>(null);
  const [history, setHistory] = useState<OptimizationRun[]>([]);
  const [mapPhase, setMapPhase] = useState<"current" | "before" | "after">("current");

  const loadHistory = useCallback(async () => {
    const response = await fetch(
      `/api/dispatch/optimize/history?metroId=${encodeURIComponent(metroId)}&date=${encodeURIComponent(date)}${initialRunId ? `&runId=${encodeURIComponent(initialRunId)}` : ""}`,
      { cache: "no-store" }
    );
    if (response.ok) {
      const data = await readResponse(response, optimizationRuns);
      setHistory(data.runs);
      setInvalidResponse(false);
      if (initialRunId) {
        const selected = data.runs.find(run => run.run_id === initialRunId);
        if (selected) setPreview(current => current ?? selected);
      }
    } else throw new Error("Could not load dispatch history");
  }, [metroId, date, initialRunId]);

  useEffect(() => { void loadHistory().catch(error => { setStatus(errorMessage(error)); setInvalidResponse(true); }); }, [loadHistory]);

  function goToDate(nextDate: string) {
    setPreview(null);
    setMapPhase("current");
    router.push(`/dispatch?date=${nextDate}`);
  }

  function shiftDate(days: number) {
    const next = new Date(`${date}T00:00:00Z`);
    next.setUTCDate(next.getUTCDate() + days);
    goToDate(next.toISOString().slice(0, 10));
  }

  async function handlePreview() {
    setBusy(true);
    setStatus(null);
    try {
      const response = await fetch("/api/dispatch/optimize/preview", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ metroId, date }),
      });
      const data = await readResponse(response, optimization);
      setPreview(data);
      setInvalidResponse(false);
      setMapPhase(data.status === "PREVIEW" ? "after" : "current");
      setStatus(data.status === "PREVIEW"
        ? `Preview ready. Modeled cost improvement: $${(data.objective_improvement / 100).toFixed(2)}.`
        : `No applicable proposal: ${data.reason ?? data.status}.`);
      await loadHistory();
    } catch (error) {
      setStatus(errorMessage(error));
      setInvalidResponse(true);
    } finally {
      setBusy(false);
    }
  }

  async function handleApply() {
    if (invalidResponse || !preview || preview.status !== "PREVIEW") return;
    if (!window.confirm("Apply this exact optimization proposal?")) return;
    setBusy(true);
    setStatus(null);
    try {
      const response = await fetch("/api/dispatch/optimize/apply", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ runId: preview.run_id }),
      });
      const data = await readResponse(response, optimization);
      setPreview(data);
      setInvalidResponse(false);
      setMapPhase("current");
      setStatus(`Applied ${data.appointments_moved} appointment change(s).`);
      await loadHistory();
      router.refresh();
    } catch (error) {
      setStatus(errorMessage(error));
      setInvalidResponse(true);
    } finally {
      setBusy(false);
    }
  }

  const total = (routes: OptimizationRun["route_summary_before"], field: "drive_minutes" | "route_minutes" | "overtime_minutes" | "waiting_minutes" | "distance_meters" | "modeled_cost_cents") =>
    routes.reduce((sum, route) => sum + route[field], 0);

  return (
    <div className={styles.wrap}>
      <div className={styles.toolbar}>
        <h1>Dispatch board</h1>
        <button className={styles.button} onClick={() => shiftDate(-1)}>Previous day</button>
        <input className={styles.dateInput} type="date" value={date} onChange={(event) => goToDate(event.target.value)} />
        <button className={styles.button} onClick={() => shiftDate(1)}>Next day</button>
        <button className={styles.button} disabled={busy || appointments.some(a => a.lat === null || a.lng === null)} onClick={handlePreview}>
          {busy ? "Working..." : "Preview optimization"}
        </button>
        {status && <span className={styles.status}>{status}</span>}
      </div>

      {preview && (
        <section className={styles.optimizationPanel}>
          <div className={styles.optimizationHeader}>
            <div><strong>{preview.status.replaceAll("_", " ")}</strong><span>Run {preview.run_id.slice(0, 8)} · {preview.solve_ms} ms</span></div>
            {preview.status === "PREVIEW" && <button className={styles.applyButton} disabled={invalidResponse || busy || appointments.some(a => a.lat === null || a.lng === null)} onClick={handleApply}>Apply proposal</button>}
          </div>
          <div className={styles.fleetTotals}>
            <span title={preview.routing_identity}>Routing graph: {preview.routing_identity.slice(0, 12)}</span>
            <span title={preview.configuration_version}>Configuration: {preview.configuration_version.slice(0, 12)}</span>
            <span>Drive: {total(preview.route_summary_before, "drive_minutes")} to {total(preview.route_summary_after, "drive_minutes")} min</span>
            <span>Paid route: {total(preview.route_summary_before, "route_minutes")} to {total(preview.route_summary_after, "route_minutes")} min</span>
            <span>Overtime: {total(preview.route_summary_before, "overtime_minutes")} to {total(preview.route_summary_after, "overtime_minutes")} min</span>
            <span>Waiting: {total(preview.route_summary_before, "waiting_minutes")} to {total(preview.route_summary_after, "waiting_minutes")} min</span>
            <span>Distance: {(total(preview.route_summary_before, "distance_meters") / 1000).toFixed(1)} to {(total(preview.route_summary_after, "distance_meters") / 1000).toFixed(1)} km</span>
            <span>Modeled cost: ${(total(preview.route_summary_before, "modeled_cost_cents") / 100).toFixed(2)} to ${(total(preview.route_summary_after, "modeled_cost_cents") / 100).toFixed(2)}</span>
          </div>
          <div className={styles.comparisonGrid}>
            {preview.route_summary_before.map((before) => {
              const after = preview.route_summary_after.find((route) => route.technician_id === before.technician_id);
              const tech = technicians.find((item) => item.id === before.technician_id);
              return <div className={styles.routeComparison} key={before.technician_id}>
                <strong>{tech?.name ?? before.technician_id}</strong>
                <span>{before.stop_count} to {after?.stop_count ?? 0} stops</span>
                <span>{before.drive_minutes} to {after?.drive_minutes ?? 0} drive min</span>
                <span>{before.workload_minutes} to {after?.workload_minutes ?? 0} paid min</span>
              </div>;
            })}
          </div>
          <div className={styles.changeList}>
            <strong>Appointment changes</strong>
            {preview.changes.length === 0 && <span>No appointment changes proposed.</span>}
            {preview.changes.map((change) => <span key={change.appointment_id}>
              {change.appointment_id.slice(0, 8)}: tech {change.from_technician_id.slice(0, 6)} to {change.to_technician_id.slice(0, 6)}, sequence {change.from_sequence + 1} to {change.to_sequence + 1}, arrival {change.from_planned_arrival_min} to {change.to_planned_arrival_min}
            </span>)}
          </div>
        </section>
      )}

      {history.length > 0 && <details className={styles.history}>
        <summary>Optimization history ({history.length})</summary>
        {history.map((run) => <button key={run.run_id} onClick={() => { setPreview(run); setMapPhase("current"); }}>
          {new Date(run.created_at).toLocaleString()} · {run.status.replaceAll("_", " ")} · ${(run.objective_improvement / 100).toFixed(2)} modeled cost change
        </button>)}
      </details>}

      <div className={styles.body}>
        <div className={styles.columns}>
          {technicians.length === 0 && <div className={styles.empty}>No active technicians.</div>}
          {technicians.map((tech, index) => {
            const color = TECH_COLORS[index % TECH_COLORS.length];
            const stops = appointments.filter((item) => item.technicianId === tech.id).sort((left, right) => left.sequence - right.sequence);
            return <div className={styles.column} key={tech.id}>
              <div className={styles.columnHeader} style={{ borderColor: color }}>{tech.name}<div className={styles.columnMeta}>{stops.length} stop{stops.length === 1 ? "" : "s"}</div></div>
              {stops.map((appointment) => <AppointmentCard key={appointment.id} appointment={appointment} timezone={timezone} />)}
              {stops.length === 0 && <div className={styles.empty}>No visits scheduled.</div>}
            </div>;
          })}
        </div>
        <div className={styles.mapPane}>
          {preview && <div style={{ padding: 8, display: "flex", gap: 8 }}>
            <button onClick={() => setMapPhase("current")}>Current roads</button>
            <button onClick={() => setMapPhase("before")}>Before preview</button>
            <button onClick={() => setMapPhase("after")}>Proposed roads</button>
          </div>}
          <DispatchMap technicians={technicians} appointments={appointments} timezone={timezone}
            metroId={metroId} date={date} runId={mapPhase === "current" ? undefined : preview?.run_id} phase={mapPhase} />
        </div>
      </div>
    </div>
  );
}
