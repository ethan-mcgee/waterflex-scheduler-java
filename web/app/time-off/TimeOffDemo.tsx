"use client";

import { errorMessage, readResponse, timeOffCategories, timeOffRequest, timeOffResult } from "@/lib/contracts";
import { additionalRepairOvertime, type ParsedTimeOffReport, type TimeOffIntervalView } from "@/lib/timeOffView";
import { useRouter } from "next/navigation";
import { Fragment, useEffect, useRef, useState } from "react";
import Avatar from "../components/Avatar";
import StatusPill from "../components/StatusPill";
import ui from "../components/ui.module.css";
import styles from "./time-off.module.css";

type Request = {
  id: string; technicianId: string; technicianName: string; category: string; reason: string; status: string; createdAt: string;
  intervals: TimeOffIntervalView[]; intervalsValid: boolean; reportStatus: string | null; reportProgress: number | null; report: ParsedTimeOffReport;
};
type FilterKey = "all" | "pending" | "ready" | "approved" | "denied";
const FILTERS: Array<{ key: FilterKey; label: string }> = [
  { key: "all", label: "Active requests" }, { key: "pending", label: "Pending" }, { key: "ready", label: "Ready for review" }, { key: "approved", label: "Approved" }, { key: "denied", label: "Denied history" },
];
function minutes(value: string): number | null {
  if (!/^\d{2}:\d{2}$/.test(value)) return null;
  const [hour, minute] = value.split(":").map(Number);
  return hour == null || minute == null || hour > 23 || minute > 59 ? null : hour * 60 + minute;
}
function time(value: number) { return `${String(Math.floor(value / 60)).padStart(2, "0")}:${String(value % 60).padStart(2, "0")}`; }
function analysisLabel(request: Request): string {
  if (request.status === "DENIED") return "Denied";
  if (request.status === "APPROVED") return "Approved";
  if (request.reportStatus === "QUEUED") return "Queued for analysis";
  if (request.reportStatus === "ANALYZING") return request.reportProgress == null ? "Analyzing, progress unknown" : `Analyzing, ${request.reportProgress}%`;
  if (request.reportStatus === "NEEDS_COORDINATION") return "Needs coordination";
  if (request.reportStatus === "ROUTING_FAILURE") return "Analysis failed: routing unavailable";
  if (request.reportStatus === "ANALYSIS_FAILURE") return "Analysis failed";
  if (request.status === "READY") return "Complete, ready for review";
  return request.reportStatus ?? "Analysis status unavailable";
}
function reportMatches(request: Request): boolean {
  if (!request.intervalsValid || request.report.kind !== "complete" || request.report.summary.days.length !== request.intervals.length) return false;
  return request.intervals.every((interval, index) => {
    const day = request.report.kind === "complete" ? request.report.summary.days[index] : undefined;
    return day != null && (day.status === "NO_SHIFT" || (day.status === "REPAIR_PREVIEW" && day.run_id != null)) && day.service_date === interval.date
      && day.start_min === interval.startMin && day.end_min === interval.endMin;
  });
}
function metric(value: number | undefined, suffix = "") { return value == null ? "Missing" : `${value.toLocaleString()}${suffix}`; }
function dayReason(reason: string | null | undefined): string {
  if (reason === "ACTIVE_RESERVATIONS") return "Active booking reservations need to expire or be resolved before analysis.";
  if (reason === "VALIDATED_CONSTRAINT_CONFLICT") return "The requested absence conflicts with appointment or route constraints.";
  if (reason === "SEARCH_BUDGET_EXHAUSTED") return "The solver could not find a feasible repair in its search time.";
  return reason ?? "No reason reported";
}
type MetricValues = { route_minutes: number; overtime_minutes: number; drive_minutes: number; waiting_minutes: number; distance_meters: number; modeled_cost_cents: number };

export default function TimeOffDemo({ technicians, requests, selectedFilter, truncated }: {
  technicians: Array<{ id: string; name: string }>; requests: Request[]; selectedFilter: FilterKey; truncated: boolean;
}) {
  const router = useRouter(), opener = useRef<HTMLButtonElement>(null), firstControl = useRef<HTMLSelectElement>(null), dialog = useRef<HTMLDivElement>(null), busyRef = useRef(false);
  const [technicianId, setTechnicianId] = useState(technicians[0]?.id ?? ""), [firstDate, setFirstDate] = useState(""), [lastDate, setLastDate] = useState("");
  const [start, setStart] = useState("08:00"), [end, setEnd] = useState("17:00"), [category, setCategory] = useState<string>(timeOffCategories[0]), [reason, setReason] = useState("");
  const [busy, setBusy] = useState(false), [message, setMessage] = useState(""), [formError, setFormError] = useState(""), [showForm, setShowForm] = useState(false), [expanded, setExpanded] = useState<string | null>(null);

  const [overtimeApprovals, setOvertimeApprovals] = useState<Record<string, string>>({});

  useEffect(() => {
    if (!showForm) return;
    firstControl.current?.focus();
    function dismiss(event: KeyboardEvent) {
      if (event.key === "Escape" && !busyRef.current) { event.preventDefault(); setShowForm(false); opener.current?.focus(); }
    }
    document.addEventListener("keydown", dismiss, true);
    return () => document.removeEventListener("keydown", dismiss, true);
  }, [showForm]);
  function handleDialogKeyDown(event: React.KeyboardEvent) {
    if (event.key === "Escape" && !busyRef.current) { event.preventDefault(); setShowForm(false); opener.current?.focus(); return; }
    if (event.key !== "Tab" || !dialog.current) return;
    const controls = [...dialog.current.querySelectorAll<HTMLElement>('button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])')];
    const first = controls[0], last = controls[controls.length - 1];
    if (first == null || last == null) return;
    if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last.focus(); }
    else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus(); }
  }
  function closeForm() { if (!busy) { setShowForm(false); setFormError(""); opener.current?.focus(); } }
  async function submit(event: React.FormEvent) {
    event.preventDefault(); setFormError("");
    const startMin = minutes(start), endMin = minutes(end);
    const parsed = timeOffRequest.safeParse({ technicianId, firstDate, lastDate: lastDate || firstDate, startMin, endMin, category, reason });
    if (!parsed.success) { setFormError("Select a technician and category, enter valid dates, and provide hours in chronological order."); return; }
    busyRef.current = true; setBusy(true);
    try {
      const response = await fetch("/api/time-off", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(parsed.data) });
      const data = await readResponse(response, timeOffResult); setMessage(`Request ${data.requestId} submitted for review.`); setReason(""); setShowForm(false); router.refresh();
    } catch (error) {
      setFormError(errorMessage(error));
      window.setTimeout(() => firstControl.current?.focus(), 0);
    } finally { busyRef.current = false; setBusy(false); }
  }
  async function approve(request: Request) {
    const additional = additionalRepairOvertime(request.report);
    if (!reportMatches(request) || additional === null || request.report.kind !== "complete") { setMessage("Review a fresh repair report before approving."); return; }
    const allowAdditionalOvertime = additional > 0 && overtimeApprovals[request.id] === JSON.stringify(request.report.summary);
    if (additional > 0 && !allowAdditionalOvertime) { setMessage("Explicit approval is required for additional overtime."); return; }
    const approvedRepairIds = request.report.summary.days.flatMap(day => day.status === "REPAIR_PREVIEW" && day.run_id ? [day.run_id] : []);
    const id = request.id;
    setBusy(true); setMessage("");
    try { const response = await fetch("/api/time-off/approve", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ id, allowAdditionalOvertime, approvedRepairIds }) }); await readResponse(response, timeOffResult); setMessage(`Request ${id} approved.`); router.refresh(); }
    catch (error) { setMessage(errorMessage(error)); } finally { setBusy(false); }
  }
  async function act(id: string, action: "retry" | "deny") {
    setBusy(true); setMessage("");
    try {
      const response = await fetch(`/api/time-off/${action}`, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ id }) });
      await readResponse(response, timeOffResult);
      setMessage(action === "retry" ? `Request ${id} queued for another analysis.` : `Request ${id} denied.`);
      router.refresh();
    } catch (error) { setMessage(errorMessage(error)); } finally { setBusy(false); }
  }

  return <div className={styles.main}><div className={styles.top}><p className={styles.eyebrow}>Dispatch</p><h1 className={styles.title}>Time off</h1><p className={styles.subtitle}>Requests, conflict analysis, and approvals in one queue.</p></div>
    <div className={styles.toolbar}><div className={styles.seg}>{FILTERS.map(filter => <button key={filter.key} type="button" aria-pressed={selectedFilter === filter.key} className={selectedFilter === filter.key ? styles.segActive : ""} onClick={() => router.push(filter.key === "all" ? "/time-off" : `/time-off?status=${filter.key}`)}>{filter.label}</button>)}</div>
      <button ref={opener} className={`${ui.button} ${ui.buttonBrand}`} type="button" onClick={() => { setFormError(""); setShowForm(true); }}>+ New request</button></div>
    {message && <p role="status" className={styles.message}>{message}</p>}
    {truncated && <p role="note" className={styles.limitNotice}>Showing the first 100 matching requests.</p>}
    {requests.length === 0 ? <div className={`${ui.card} ${styles.emptyState}`}>{selectedFilter === "all" ? "No active time-off requests." : `No requests match the ${FILTERS.find(item => item.key === selectedFilter)?.label.toLowerCase()} filter.`}</div> :
    <div className={`${ui.card} ${styles.tableCard}`}><table className={styles.table}><thead><tr><th>Technician</th><th>Dates</th><th>Reason</th><th>Status</th><th>Conflict analysis</th><th>Action</th></tr></thead><tbody>{requests.map(request => {
      const additional = additionalRepairOvertime(request.report);
      const approvalKey = request.report.kind === "complete" ? JSON.stringify(request.report.summary) : null;
      const overtimeApproved = approvalKey !== null && overtimeApprovals[request.id] === approvalKey;
      const isOpen = expanded === request.id, canApprove = request.status === "READY" && reportMatches(request)
        && additional !== null && (additional === 0 || overtimeApproved), progress = request.reportProgress;
      return <Fragment key={request.id}><tr><td className={styles.who}><Avatar name={request.technicianName} /><span><button type="button" className={styles.nameLink} aria-expanded={isOpen} aria-controls={`request-${request.id}`} onClick={() => setExpanded(isOpen ? null : request.id)}>{request.technicianName}</button><br /><span className={styles.sub}>Technician</span></span></td>
        <td>{request.intervalsValid ? <><div className={styles.mainDate}>{request.intervals[0]?.date}{request.intervals.length > 1 ? ` to ${request.intervals.at(-1)?.date}` : ""}</div><div className={styles.times}>{request.intervals.length === 1 && request.intervals[0] ? `${time(request.intervals[0].startMin)} to ${time(request.intervals[0].endMin)}` : `${request.intervals.length} days`}</div></> : <span className={styles.invalid}>Invalid interval data</span>}</td>
        <td>{request.category}</td><td><StatusPill status={request.status} /></td><td><div className={styles.analysisLbl}>{analysisLabel(request)}</div>{request.report.kind === "failure" && <div className={styles.analysisLbl}>{request.report.reason}</div>}{request.status !== "DENIED" && (progress == null ? <div className={styles.progressUnknown}>Progress unknown</div> : <div className={styles.analysisBar} aria-label={`Analysis ${progress}% complete`}><span style={{ width: `${Math.max(0, Math.min(100, progress))}%` }} /></div>)}{(request.reportStatus === "QUEUED" || request.reportStatus === "ANALYZING") && <button className={styles.refreshBtn} type="button" onClick={() => router.refresh()}>Refresh analysis</button>}</td>
        <td><div className={styles.actions}>{request.status === "READY" && additional !== null && additional > 0 && approvalKey !== null &&
          <label><input type="checkbox" checked={overtimeApproved} disabled={busy}
            onChange={event => setOvertimeApprovals(current => ({ ...current, [request.id]: event.target.checked ? approvalKey : "" }))} />
            Approve {additional} additional overtime minutes to preserve appointments</label>}{request.status === "READY" && <button className={styles.approveBtn} disabled={busy || !canApprove} title={!canApprove ? "A valid matching report is required" : undefined} onClick={() => approve(request)}>Approve</button>}{request.status === "PENDING" && (request.reportStatus === "ANALYSIS_FAILURE" || request.reportStatus === "ROUTING_FAILURE") && <button className={ui.button} type="button" disabled={busy} onClick={() => act(request.id, "retry")}>Retry analysis</button>}{(request.status === "PENDING" || request.status === "READY") && <button className={ui.button} type="button" disabled={busy} onClick={() => act(request.id, "deny")}>Deny</button>}</div></td></tr>
        {isOpen && <tr className={styles.detailRow}><td colSpan={6}><div className={styles.detailBody} id={`request-${request.id}`}><div><p className={ui.sectionLabel}>Request</p><div className={styles.kvRow}><span className={styles.key}>Status</span><span>{request.status}</span></div><div className={styles.kvRow}><span className={styles.key}>Category</span><span>{request.category}</span></div><div className={styles.kvRow}><span className={styles.key}>Submitted</span><span>{new Date(request.createdAt).toLocaleDateString()}</span></div><p className={ui.sectionLabel}>Explanation</p><p className={styles.explanation}>{request.reason}</p></div>
          <div><p className={ui.sectionLabel}>Requested intervals</p>{request.intervalsValid ? request.intervals.map(interval => <div key={interval.date} className={styles.intervalRow}><span>{interval.date}</span><span>{time(interval.startMin)} to {time(interval.endMin)}</span></div>) : <p className={styles.invalid}>Missing or invalid requested intervals. Approval is unavailable.</p>}</div>
          <div><p className={ui.sectionLabel}>Conflict analysis</p><p className={styles.analysisLbl}>{analysisLabel(request)}</p><ReportDetails report={request.report} /><button className={ui.button} type="button" onClick={() => setExpanded(null)}>Collapse</button></div></div></td></tr>}
      </Fragment>;
    })}</tbody></table></div>}
    {showForm && <><div className={styles.scrim} onClick={closeForm} /><div ref={dialog} className={styles.slideover} role="dialog" aria-modal="true" aria-labelledby="time-off-dialog-title" onKeyDown={handleDialogKeyDown}><button className={styles.close} aria-label="Close new time-off request" type="button" disabled={busy} onClick={closeForm}>&#10005;</button><h3 id="time-off-dialog-title">New time-off request</h3>
      <form onSubmit={submit} className={styles.requestForm}><div className={styles.field}><label htmlFor="timeoff-technician">Technician</label><select ref={firstControl} id="timeoff-technician" value={technicianId} onChange={event => setTechnicianId(event.target.value)}><option value="">Select a technician</option>{technicians.map(technician => <option key={technician.id} value={technician.id}>{technician.name}</option>)}</select></div>
        <div className={styles.fieldRow}><div className={styles.field}><label htmlFor="timeoff-first-date">First date</label><input id="timeoff-first-date" type="date" required value={firstDate} onChange={event => setFirstDate(event.target.value)} /></div><div className={styles.field}><label htmlFor="timeoff-last-date">Last date</label><input id="timeoff-last-date" type="date" value={lastDate} min={firstDate} onChange={event => setLastDate(event.target.value)} /></div></div>
        <div className={styles.fieldRow}><div className={styles.field}><label htmlFor="timeoff-start">From</label><input id="timeoff-start" type="time" required value={start} onChange={event => setStart(event.target.value)} /></div><div className={styles.field}><label htmlFor="timeoff-end">To</label><input id="timeoff-end" type="time" required value={end} onChange={event => setEnd(event.target.value)} /></div></div>
        <div className={styles.field}><label htmlFor="timeoff-category">Reason category</label><select id="timeoff-category" value={category} onChange={event => setCategory(event.target.value)}>{timeOffCategories.map(item => <option key={item} value={item}>{item}</option>)}</select></div>
        <div className={styles.field}><label htmlFor="timeoff-reason">Explain the planned absence</label><textarea id="timeoff-reason" required maxLength={500} rows={3} value={reason} onChange={event => setReason(event.target.value)} placeholder="A couple sentences on what is happening and why it falls on these dates." /></div>
        {formError && <p role="alert" className={styles.formError}>{formError}</p>}<button className={`${ui.button} ${ui.buttonBrand}`} type="submit" disabled={busy || !technicianId}>Submit request</button>
      </form></div></>}
  </div>;
}

function ReportDetails({ report }: { report: ParsedTimeOffReport }) {
  if (report.kind === "missing") return <p className={styles.invalid}>No analysis report is available.</p>;
  if (report.kind === "malformed") return <p className={styles.invalid}>The saved analysis report is malformed.</p>;
  if (report.kind === "failure") return <p className={styles.invalid}>Analysis failure reason: {report.reason}</p>;
  const summary = report.summary;
  return <div className={styles.report}><p>{summary.reassigned_jobs == null ? "Reassignment count missing" : `${summary.reassigned_jobs} reassigned job${summary.reassigned_jobs === 1 ? "" : "s"}`}</p>
    {summary.days.map(day => <div className={styles.reportDay} key={`${day.service_date}-${day.start_min}`}><strong>{day.service_date}: {day.status === "NO_SHIFT" ? "No scheduled shift" : day.status === "SKIPPED" ? "Needs coordination" : day.status === "FROZEN_CSR_COORDINATION" ? "Past scheduling cutoff" : "Repair preview"}</strong><span>{dayReason(day.reason)}</span>{day.status === "REPAIR_PREVIEW" && <><span>{day.reassigned_jobs == null ? "Reassignment count missing" : `${day.reassigned_jobs} reassigned`}</span><MetricComparison before={day.daily_before} after={day.daily_after} /></>}</div>)}
    <strong>Combined metrics</strong><MetricComparison before={summary.total_before ?? undefined} after={summary.total_after ?? undefined} /></div>;
}

function MetricComparison({ before, after }: { before?: MetricValues; after?: MetricValues }) {
  return <div className={styles.metrics}>
    <span>Route: {metric(before?.route_minutes, " min")} before, {metric(after?.route_minutes, " min")} after</span>
    <span>Drive: {metric(before?.drive_minutes, " min")} before, {metric(after?.drive_minutes, " min")} after</span>
    <span>Waiting: {metric(before?.waiting_minutes, " min")} before, {metric(after?.waiting_minutes, " min")} after</span>
    <span>Overtime: {metric(before?.overtime_minutes, " min")} before, {metric(after?.overtime_minutes, " min")} after</span>
    <span>Distance: {metric(before?.distance_meters, " m")} before, {metric(after?.distance_meters, " m")} after</span>
    <span>Modeled cost: {metric(before?.modeled_cost_cents, " cents")} before, {metric(after?.modeled_cost_cents, " cents")} after</span>
  </div>;
}
