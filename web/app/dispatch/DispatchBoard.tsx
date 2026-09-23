"use client";

import { readResponse, optimization, optimizationRuns, errorMessage } from "@/lib/contracts";
import dynamic from "next/dynamic";
import { useRouter } from "next/navigation";
import { useCallback, useEffect, useState } from "react";
import styles from "@/app/dispatch/dispatch.module.css";
import type { BoardAppointment, BoardTechnician } from "@/app/dispatch/types";
import type { OptimizationRun } from "@/lib/engineClient";
import OptimizationReview from "@/app/dispatch/OptimizationReview";

const DispatchMap = dynamic(() => import("@/app/dispatch/DispatchMap"), { ssr: false, loading: () => <div className={styles.empty}>Loading map...</div> });

function formatTime(iso: string, timezone: string) {
  return new Intl.DateTimeFormat("en-US", { timeZone: timezone, hour: "numeric", minute: "2-digit" }).format(new Date(iso));
}
function AppointmentCard({ appointment, timezone }: { appointment: BoardAppointment; timezone: string }) {
  return <div className={styles.card}>
    <div className={styles.cardTime}>{formatTime(appointment.plannedStart, timezone)} (window {formatTime(appointment.windowStart, timezone)} to {formatTime(appointment.windowEnd, timezone)})</div>
    <div className={styles.cardCustomer}>{appointment.customerName}</div><div className={styles.cardMeta}>{appointment.serviceName}</div>
    <div className={styles.cardMeta}>{appointment.addressLine}</div>
    {(appointment.lat === null || appointment.lng === null) && <p role="alert">Location data missing. Routing is blocked.</p>}
  </div>;
}

export default function DispatchBoard({ metroId, timezone, date, technicians, appointments, initialRunId }: {
  metroId: string; timezone: string; date: string; technicians: BoardTechnician[]; appointments: BoardAppointment[]; initialRunId?: string;
}) {
  const router = useRouter();
  const [invalidResponse, setInvalidResponse] = useState(false);
  const [busy, setBusy] = useState(false);
  const [status, setStatus] = useState<string | null>(null);
  const [preview, setPreview] = useState<OptimizationRun | null>(null);
  const [history, setHistory] = useState<OptimizationRun[]>([]);
  const loadHistory = useCallback(async () => {
    const response = await fetch(`/api/dispatch/optimize/history?metroId=${encodeURIComponent(metroId)}&date=${encodeURIComponent(date)}${initialRunId ? `&runId=${encodeURIComponent(initialRunId)}` : ""}`, { cache: "no-store" });
    if (!response.ok) throw new Error("Could not load dispatch history");
    const data = await readResponse(response, optimizationRuns);
    setHistory(data.runs); setInvalidResponse(false);
    if (initialRunId) {
      const selected = data.runs.find(run => run.run_id === initialRunId);
      if (!selected) throw new Error("The requested optimization run is not available for this date.");
      setPreview(current => current ?? selected);
    }
  }, [metroId, date, initialRunId]);
  useEffect(() => { void loadHistory().catch(error => { setStatus(errorMessage(error)); setInvalidResponse(true); }); }, [loadHistory]);
  function goToDate(nextDate: string) { setPreview(null); router.push(`/dispatch?metroId=${encodeURIComponent(metroId)}&date=${nextDate}`); }
  function shiftDate(days: number) { const next = new Date(`${date}T00:00:00Z`); next.setUTCDate(next.getUTCDate() + days); goToDate(next.toISOString().slice(0, 10)); }
  async function handlePreview() {
    setBusy(true); setStatus(null);
    try {
      const response = await fetch("/api/dispatch/optimize/preview", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ metroId, date }) });
      const data = await readResponse(response, optimization);
      setPreview(data); setInvalidResponse(false);
      setStatus(data.status === "PREVIEW" ? `Preview ready. Modeled cost improvement: $${(data.objective_improvement / 100).toFixed(2)}.` : `No applicable proposal: ${data.reason ?? data.status}.`);
      await loadHistory();
    } catch (error) { setStatus(errorMessage(error)); setInvalidResponse(true); }
    finally { setBusy(false); }
  }
  return <div className={styles.wrap}>
    <div className={styles.toolbar}><h1>Dispatch board</h1>
      <button className={styles.button} onClick={() => shiftDate(-1)}>Previous day</button>
      <input className={styles.dateInput} type="date" value={date} onChange={event => goToDate(event.target.value)} />
      <button className={styles.button} onClick={() => shiftDate(1)}>Next day</button>
      <button className={styles.button} disabled={busy || appointments.some(a => a.lat === null || a.lng === null)} onClick={() => void handlePreview()}>{busy ? "Working..." : "Preview optimization"}</button>
      {status && <span className={styles.status}>{status}</span>}
    </div>
    {preview && <OptimizationReview run={preview} disabled={invalidResponse || busy || appointments.some(a => a.lat === null || a.lng === null)}
      technicianName={id => technicians.find(item => item.id === id)?.name ?? id}
      onApplied={async data => { setPreview(data); setInvalidResponse(false); await loadHistory(); router.refresh(); }}
      routes={phase => <div style={{ height: 420 }}><DispatchMap technicians={technicians} appointments={appointments} timezone={timezone}
        metroId={metroId} date={date} runId={phase === "current" ? undefined : preview.run_id} phase={phase} /></div>} />}
    {history.length > 0 && <details className={styles.history}><summary>Optimization history ({history.length})</summary>
      {history.map(run => <button key={run.run_id} onClick={() => setPreview(run)}>{new Date(run.created_at).toLocaleString()} | {run.status.replaceAll("_", " ")} | ${(run.objective_improvement / 100).toFixed(2)} modeled cost change</button>)}
    </details>}
    <div className={styles.body}><div className={styles.columns}>
      {technicians.length === 0 && <div className={styles.empty}>No active technicians.</div>}
      {technicians.map((tech) => {
        const color = tech.color;
        const stops = appointments.filter(item => item.technicianId === tech.id).sort((left, right) => left.sequence - right.sequence);
        return <div className={styles.column} key={tech.id}><div className={styles.columnHeader} style={{ borderColor: color }}>{tech.name}<div className={styles.columnMeta}>{stops.length} stop{stops.length === 1 ? "" : "s"}</div></div>
          {stops.map(appointment => <AppointmentCard key={appointment.id} appointment={appointment} timezone={timezone} />)}
          {stops.length === 0 && <div className={styles.empty}>No visits scheduled.</div>}</div>;
      })}
    </div><div className={styles.mapPane}>{!preview && <DispatchMap technicians={technicians} appointments={appointments} timezone={timezone} metroId={metroId} date={date} phase="current" />}</div></div>
  </div>;
}
