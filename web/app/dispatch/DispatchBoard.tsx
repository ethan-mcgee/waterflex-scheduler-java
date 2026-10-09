"use client";

import { readResponse, errorMessage, apiProposal, apiProposals } from "@/lib/contracts";
import dynamic from "next/dynamic";
import { useRouter } from "next/navigation";
import { useCallback, useEffect, useState } from "react";
import styles from "@/app/dispatch/dispatch.module.css";
import type { BoardAppointment, BoardTechnician } from "@/app/dispatch/types";
import ApiProposalReview, { type ApiProposal } from "@/app/dispatch/ApiProposalReview";

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

type BoardProps = { metroId: string; timezone: string; date: string; technicians: BoardTechnician[]; appointments: BoardAppointment[] };

function DateToolbar({ metroId, date, busy, previewDisabled, status, onPreview }: { metroId: string; date: string; busy: boolean; previewDisabled: boolean;
  status: string | null; onPreview: () => void }) {
  const router = useRouter();
  function goToDate(nextDate: string) { router.push(`/dispatch?metroId=${encodeURIComponent(metroId)}&date=${nextDate}`); }
  function shiftDate(days: number) { const next = new Date(`${date}T00:00:00Z`); next.setUTCDate(next.getUTCDate() + days); goToDate(next.toISOString().slice(0, 10)); }
  return <div className={styles.toolbar}><h1>Dispatch board</h1>
    <button className={styles.button} onClick={() => shiftDate(-1)}>Previous day</button>
    <input className={styles.dateInput} type="date" value={date} onChange={event => goToDate(event.target.value)} />
    <button className={styles.button} onClick={() => shiftDate(1)}>Next day</button>
    <button className={styles.button} disabled={busy || previewDisabled} onClick={onPreview}>{busy ? "Working..." : "Preview optimization"}</button>
    {status && <span className={styles.status}>{status}</span>}
  </div>;
}

function Columns({ technicians, appointments, timezone }: { technicians: BoardTechnician[]; appointments: BoardAppointment[]; timezone: string }) {
  return <div className={styles.columns}>
    {technicians.length === 0 && <div className={styles.empty}>No active technicians.</div>}
    {technicians.map((tech) => {
      const color = tech.color;
      const stops = appointments.filter(item => item.technicianId === tech.id).sort((left, right) => left.sequence - right.sequence);
      return <div className={styles.column} key={tech.id}><div className={styles.columnHeader} style={{ borderColor: color }}>{tech.name}<div className={styles.columnMeta}>{stops.length} stop{stops.length === 1 ? "" : "s"}</div></div>
        {tech.routeTiming?.status === "AVAILABLE" && tech.routeTiming.segments.map((segment, index) => <div className={styles.cardMeta} key={segment.departure}>
          Segment {index + 1}: depart {formatTime(segment.departure, timezone)}, return {formatTime(segment.returnedAt, timezone)}
        </div>)}
        {stops.length > 0 && tech.routeTiming?.status === "UNAVAILABLE" && <p className={styles.cardMeta}>Departure and return times unavailable until this route is replanned.</p>}
        {tech.routeTiming?.status === "INVALID" && <p role="alert">Saved route timing is invalid. Replan this day before using departure times.</p>}
        {stops.map(appointment => <AppointmentCard key={appointment.id} appointment={appointment} timezone={timezone} />)}
        {stops.length === 0 && <div className={styles.empty}>No visits scheduled.</div>}</div>;
    })}
  </div>;
}

/** The board when scheduling runs through the public API: proposals for this client's routes only. */
export default function DispatchBoard({ metroId, timezone, date, technicians, appointments }: BoardProps) {
  const router = useRouter();
  const [busy, setBusy] = useState(false);
  const [status, setStatus] = useState<string | null>(null);
  const [selected, setSelected] = useState<ApiProposal | null>(null);
  const [history, setHistory] = useState<ApiProposal[]>([]);
  const loadHistory = useCallback(async () => {
    const response = await fetch(`/api/dispatch/proposals?metroId=${encodeURIComponent(metroId)}&date=${encodeURIComponent(date)}`, { cache: "no-store" });
    const proposals = (await readResponse(response, apiProposals)).proposals;
    setHistory(proposals);
    return proposals;
  }, [metroId, date]);
  useEffect(() => {
    setSelected(null);
    void loadHistory().then(proposals => {
      // An overnight improvement waits here for a dispatcher; show the newest one that can still be applied.
      const waiting = proposals.find(item => item.overnightRunId !== null && item.state === "OPEN");
      if (waiting === undefined) return;
      setSelected(current => current ?? waiting);
      setStatus(`An overnight proposal is waiting for approval: ${waiting.changes.length} appointment change(s).`);
    }).catch(error => setStatus(errorMessage(error)));
  }, [loadHistory]);
  const unlocated = appointments.some(a => a.lat === null || a.lng === null);
  const technicianName = (id: string) => technicians.find(item => item.id === id)?.name ?? id;
  async function handlePreview() {
    setBusy(true); setStatus(null);
    try {
      const response = await fetch("/api/dispatch/proposals", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ metroId, date }) });
      const proposal = await readResponse(response, apiProposal);
      setSelected(proposal);
      setStatus(proposal.state === "OPEN" ? `Proposal ready: ${proposal.changes.length} appointment change(s).` : `No applicable proposal: ${proposal.reason}.`);
      await loadHistory();
    } catch (error) { setStatus(errorMessage(error)); }
    finally { setBusy(false); }
  }
  return <div className={styles.wrap}>
    <DateToolbar metroId={metroId} date={date} busy={busy} previewDisabled={unlocated} status={status} onPreview={() => void handlePreview()} />
    {selected && <ApiProposalReview proposal={selected} timezone={timezone} technicianName={technicianName} disabled={busy || unlocated}
      onApplied={async applied => { setSelected(applied); await loadHistory(); router.refresh(); }} />}
    {history.length > 0 && <details className={styles.history}><summary>Optimization history ({history.length})</summary>
      {history.map(item => <button key={item.proposalId} onClick={() => setSelected(item)}>{new Date(item.createdAt).toLocaleString()}{item.overnightRunId !== null ? " | Overnight" : ""} | {item.decision.replaceAll("_", " ")} | {item.state.replaceAll("_", " ")}</button>)}
    </details>}
    <div className={styles.body}><Columns technicians={technicians} appointments={appointments} timezone={timezone} />
      <div className={styles.mapPane}><DispatchMap technicians={technicians} appointments={appointments} timezone={timezone} metroId={metroId} date={date} /></div></div>
  </div>;
}
