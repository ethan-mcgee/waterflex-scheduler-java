"use client";

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
    </div>
  );
}

export default function DispatchBoard({ metroId, timezone, date, technicians, appointments }: {
  metroId: string;
  timezone: string;
  date: string;
  technicians: BoardTechnician[];
  appointments: BoardAppointment[];
}) {
  const router = useRouter();
  const [busy, setBusy] = useState(false);
  const [status, setStatus] = useState<string | null>(null);
  const [preview, setPreview] = useState<OptimizationRun | null>(null);
  const [history, setHistory] = useState<OptimizationRun[]>([]);

  const loadHistory = useCallback(async () => {
    const response = await fetch(
      `/api/dispatch/optimize/history?metroId=${encodeURIComponent(metroId)}&date=${encodeURIComponent(date)}`,
      { cache: "no-store" }
    );
    if (response.ok) {
      const data = (await response.json()) as { runs: OptimizationRun[] };
      setHistory(data.runs);
    }
  }, [metroId, date]);

  useEffect(() => { void loadHistory(); }, [loadHistory]);

  function goToDate(nextDate: string) {
    setPreview(null);
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
      const data = (await response.json()) as OptimizationRun & { error?: string };
      if (!response.ok) {
        setStatus(`Error: ${data.error ?? "optimization failed"}`);
        return;
      }
      setPreview(data);
      setStatus(data.status === "PREVIEW_READY"
        ? `Preview ready. Net improvement: ${data.objective_improvement} points.`
        : `No applicable proposal: ${data.reason ?? data.status}.`);
      await loadHistory();
    } catch {
      setStatus("Network error.");
    } finally {
      setBusy(false);
    }
  }

  async function handleApply() {
    if (!preview || preview.status !== "PREVIEW_READY") return;
    if (!window.confirm("Apply this exact optimization proposal?")) return;
    setBusy(true);
    setStatus(null);
    try {
      const response = await fetch("/api/dispatch/optimize/apply", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ runId: preview.run_id }),
      });
      const data = (await response.json()) as OptimizationRun & { error?: string };
      if (!response.ok) {
        setStatus(`Apply failed: ${data.error ?? "optimization failed"}`);
        await loadHistory();
        return;
      }
      setPreview(data);
      setStatus(`Applied ${data.appointments_moved} appointment change(s).`);
      await loadHistory();
      router.refresh();
    } catch {
      setStatus("Network error.");
    } finally {
      setBusy(false);
    }
  }

  const total = (routes: OptimizationRun["route_summary_before"], field: "drive_minutes" | "route_minutes" | "overtime_minutes") =>
    routes.reduce((sum, route) => sum + route[field], 0);

  return (
    <div className={styles.wrap}>
      <div className={styles.toolbar}>
        <h1>Dispatch board</h1>
        <button className={styles.button} onClick={() => shiftDate(-1)}>Previous day</button>
        <input className={styles.dateInput} type="date" value={date} onChange={(event) => goToDate(event.target.value)} />
        <button className={styles.button} onClick={() => shiftDate(1)}>Next day</button>
        <button className={styles.button} disabled={busy} onClick={handlePreview}>
          {busy ? "Working..." : "Preview optimization"}
        </button>
        {status && <span className={styles.status}>{status}</span>}
      </div>

      {preview && (
        <section className={styles.optimizationPanel}>
          <div className={styles.optimizationHeader}>
            <div><strong>{preview.status.replaceAll("_", " ")}</strong><span>Run {preview.run_id.slice(0, 8)} · {preview.solve_ms} ms</span></div>
            {preview.status === "PREVIEW_READY" && <button className={styles.applyButton} disabled={busy} onClick={handleApply}>Apply proposal</button>}
          </div>
          <div className={styles.fleetTotals}>
            <span>Drive: {total(preview.route_summary_before, "drive_minutes")} to {total(preview.route_summary_after, "drive_minutes")} min</span>
            <span>Paid route: {total(preview.route_summary_before, "route_minutes")} to {total(preview.route_summary_after, "route_minutes")} min</span>
            <span>Overtime: {total(preview.route_summary_before, "overtime_minutes")} to {total(preview.route_summary_after, "overtime_minutes")} min</span>
            <span>Churn cost: {preview.churn_penalty_minutes} points</span>
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
        {history.map((run) => <button key={run.run_id} onClick={() => setPreview(run)}>
          {new Date(run.created_at).toLocaleString()} · {run.status.replaceAll("_", " ")} · {run.objective_improvement} points
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
        <div className={styles.mapPane}><DispatchMap technicians={technicians} appointments={appointments} timezone={timezone} /></div>
      </div>
    </div>
  );
}
