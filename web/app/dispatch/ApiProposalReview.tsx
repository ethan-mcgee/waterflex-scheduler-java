"use client";

import { apiProposal, errorMessage, readResponse } from "@/lib/contracts";
import { formatCents } from "@/lib/money";
import { useEffect, useState } from "react";
import type { z } from "zod";
import styles from "@/app/dispatch/dispatch.module.css";

export type ApiProposal = z.infer<typeof apiProposal>;

const STATE_LABEL: Record<ApiProposal["state"], string> = {
  OPEN: "Ready to apply", COMMITTED: "Applied", REFUSED: "Cannot be applied", NOT_COMMITTABLE: "Nothing to apply",
};

/** A daily proposal from the scheduling API: what it changes, and applying it when the scheduler found an improvement. */
export default function ApiProposalReview({ proposal, timezone, technicianName, disabled = false, onApplied }: {
  proposal: ApiProposal;
  timezone: string;
  technicianName: (id: string) => string;
  disabled?: boolean;
  onApplied: (proposal: ApiProposal) => void | Promise<void>;
}) {
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<string | null>(null);
  useEffect(() => { setMessage(null); }, [proposal.proposalId, proposal.state]);
  const time = (iso: string) => new Intl.DateTimeFormat("en-US", { timeZone: timezone, hour: "numeric", minute: "2-digit" }).format(new Date(iso));
  async function apply() {
    if (proposal.state !== "OPEN" || disabled || !window.confirm("Apply this optimization proposal?")) return;
    setBusy(true); setMessage(null);
    try {
      const response = await fetch("/api/dispatch/proposals/commit", { method: "POST", headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ proposalId: proposal.proposalId }) });
      const applied = await readResponse(response, apiProposal);
      setMessage(`Applied ${applied.changes.length} appointment change(s).`);
      await onApplied(applied);
    } catch (error) { setMessage(errorMessage(error)); }
    finally { setBusy(false); }
  }
  return <section className={styles.optimizationPanel} aria-label="Optimization proposal">
    <div className={styles.optimizationHeader}>
      <div><strong>{STATE_LABEL[proposal.state]}</strong><span>{proposal.overnightRunId !== null ? "Overnight proposal" : "Proposal"} {proposal.proposalId.slice(0, 12)} | {new Date(proposal.createdAt).toLocaleString()}</span></div>
      {proposal.state === "OPEN" && <button className={styles.applyButton} disabled={disabled || busy} onClick={() => void apply()}>{busy ? "Applying..." : "Apply proposal"}</button>}
    </div>
    <p>Scheduler decision: {proposal.decision.replaceAll("_", " ").toLowerCase()}. {proposal.reason}.</p>
    <p>Modeled fleet cost of these routes: ${formatCents(proposal.costCents)}.</p>
    {proposal.state === "REFUSED" && proposal.refusal && <p role="alert">{proposal.refusal}</p>}
    {proposal.state === "COMMITTED" && proposal.committedAt && <p>Applied {new Date(proposal.committedAt).toLocaleString()}.</p>}
    {proposal.unresolvedAppointmentIds.length > 0 && <p role="alert">Unresolved appointments: {proposal.unresolvedAppointmentIds.join(", ")}.</p>}
    {proposal.skippedTechnicianDays.map(day => <p key={day.technicianId} role="alert">{technicianName(day.technicianId)} was left as scheduled: {day.message}</p>)}
    {message && <p role="status" className={message.startsWith("Applied") ? undefined : styles.error}>{message}</p>}
    <div className={styles.changeList}><strong>Appointment changes</strong>
      {proposal.changes.length === 0 && <span>No appointment changes proposed.</span>}
      {proposal.changes.map(change => <span key={change.appointmentId}>{change.appointmentId.slice(0, 8)}: {technicianName(change.fromTechnicianId)} to {technicianName(change.toTechnicianId)},
        {" "}stop {change.fromSequence + 1} to {change.toSequence + 1}, start {time(change.fromStart)} to {time(change.toStart)}</span>)}
    </div>
    <div className={styles.comparisonGrid}>{proposal.routes.map(route => <div className={styles.routeComparison} key={route.technicianId}>
      <strong>{technicianName(route.technicianId)}</strong>
      {route.stops.length === 0 && <span>No visits.</span>}
      {route.stops.map(stop => <span key={stop.appointmentId}>{stop.sequence + 1}. {time(stop.plannedStart)} to {time(stop.plannedEnd)} ({stop.appointmentId.slice(0, 8)})</span>)}
    </div>)}</div>
  </section>;
}
