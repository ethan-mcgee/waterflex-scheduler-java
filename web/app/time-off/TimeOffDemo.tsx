"use client";

import { readResponse, timeOffResult, timeOffCategories, errorMessage } from "@/lib/contracts";
import { useRouter } from "next/navigation";
import { Fragment, useMemo, useState } from "react";
import Avatar from "../components/Avatar";
import StatusPill from "../components/StatusPill";
import styles from "./time-off.module.css";
import ui from "../components/ui.module.css";

type Request = {
  id: string; technicianId: string; technicianName: string; category: string; reason: string; status: string; createdAt: string;
  intervals: Array<{ date: string; startMin: number; endMin: number }>;
  reportStatus: string | null; reportProgress: number | null; report: unknown;
};

const FILTERS = [
  { label: "All requests", statuses: null as string[] | null },
  { label: "Pending", statuses: ["PENDING", "ANALYZING", "NEEDS_COORDINATION"] },
  { label: "Ready for review", statuses: ["READY"] },
  { label: "Approved", statuses: ["APPROVED"] },
];

function minutes(value: string) { const [hour = 0, minute = 0] = value.split(":").map(Number); return hour * 60 + minute; }
function time(value: number) { return `${String(Math.floor(value / 60)).padStart(2, "0")}:${String(value % 60).padStart(2, "0")}`; }

function analysisLabel(request: Request): string {
  if (request.status === "APPROVED") return "Approved";
  if (request.reportStatus === "QUEUED") return "Queued";
  if (request.reportStatus === "ANALYZING") return `Analyzing · ${request.reportProgress ?? 0}%`;
  if (request.reportStatus === "NEEDS_COORDINATION") return "Flagged — needs coordination";
  if (request.reportStatus === "ROUTING_FAILURE" || request.reportStatus === "ANALYSIS_FAILURE") return "Analysis failed — retry queued";
  if (request.status === "READY") return "Complete — ready for review";
  return request.reportStatus ?? "Queued";
}

export default function TimeOffDemo({ technicians, requests }: { technicians: Array<{ id: string; name: string }>; requests: Request[] }) {
  const router = useRouter();
  const [technicianId, setTechnicianId] = useState(technicians[0]?.id ?? "");
  const [firstDate, setFirstDate] = useState("");
  const [lastDate, setLastDate] = useState("");
  const [start, setStart] = useState("08:00");
  const [end, setEnd] = useState("17:00");
  const [category, setCategory] = useState<string>(timeOffCategories[0]);
  const [reason, setReason] = useState("");
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState("");
  const [showForm, setShowForm] = useState(false);
  const [filter, setFilter] = useState(0);
  const [expanded, setExpanded] = useState<string | null>(null);

  const pending = requests.filter((r) => r.status === "PENDING" || r.status === "ANALYZING").length;
  const flagged = requests.filter((r) => r.reportStatus === "NEEDS_COORDINATION").length;
  const approvedThisMonth = requests.filter((r) => {
    if (r.status !== "APPROVED") return false;
    const created = new Date(r.createdAt);
    const now = new Date();
    return created.getUTCFullYear() === now.getUTCFullYear() && created.getUTCMonth() === now.getUTCMonth();
  }).length;

  const visible = useMemo(() => {
    const statuses = FILTERS[filter]?.statuses;
    return statuses ? requests.filter((r) => statuses.includes(r.status)) : requests;
  }, [requests, filter]);

  async function submit(event: React.FormEvent) {
    event.preventDefault(); setBusy(true); setMessage("");
    try {
      const response = await fetch("/api/time-off", { method: "POST", headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ technicianId, firstDate, lastDate: lastDate || firstDate, startMin: minutes(start), endMin: minutes(end), category, reason }) });
      const data = await readResponse(response, timeOffResult);
      setMessage(`Request ${data.requestId} submitted for review.`);
      setReason(""); setShowForm(false); router.refresh();
    } catch (error) { setMessage(errorMessage(error)); }
    finally { setBusy(false); }
  }

  async function approve(id: string) {
    setBusy(true); setMessage("");
    try {
      const response = await fetch("/api/time-off/approve", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ id }) });
      await readResponse(response, timeOffResult);
      setMessage(`Request ${id} approved.`); router.refresh();
    } catch (error) { setMessage(errorMessage(error)); }
    finally { setBusy(false); }
  }

  return (
    <div className={styles.main}>
      <div className={styles.top}>
        <p className={styles.eyebrow}>Dispatch</p>
        <h1 className={styles.title}>Time off</h1>
        <p className={styles.subtitle}>Requests, conflict analysis, and approvals in one queue.</p>
      </div>

      <div className={styles.statRow}>
        <div className={ui.card}><div className={ui.statTile}><div className="n">{pending}</div><div className="l">Pending review</div></div></div>
        <div className={ui.card}><div className={`${ui.statTile} ${styles.statWarn}`}><div className="n">{flagged}</div><div className="l">Analysis flagged a conflict</div></div></div>
        <div className={ui.card}><div className={`${ui.statTile} ${styles.statGood}`}><div className="n">{approvedThisMonth}</div><div className="l">Approved this month</div></div></div>
        <div className={ui.card}><div className={ui.statTile}><div className="n">{technicians.length}</div><div className="l">Technicians</div></div></div>
      </div>

      <div className={styles.toolbar}>
        <div className={styles.seg}>
          {FILTERS.map((f, index) => (
            <button key={f.label} type="button" className={filter === index ? styles.segActive : ""} onClick={() => setFilter(index)}>
              {f.label}
            </button>
          ))}
        </div>
        <button className={`${ui.button} ${ui.buttonBrand}`} type="button" onClick={() => setShowForm(true)}>
          + New request
        </button>
      </div>

      {message && <p role="status" className={styles.message}>{message}</p>}

      <div className={`${ui.card} ${styles.tableCard}`}>
        <table className={styles.table}>
          <thead>
            <tr><th>Technician</th><th>Dates</th><th>Reason</th><th>Status</th><th>Conflict analysis</th><th></th></tr>
          </thead>
          <tbody>
            {visible.map((request) => {
              const isOpen = expanded === request.id;
              const pct = request.status === "APPROVED" || request.status === "READY" ? 100 : request.reportProgress ?? 0;
              return (
                <Fragment key={request.id}>
                  <tr>
                    <td className={styles.who}>
                      <Avatar name={request.technicianName} />
                      <span>
                        <button type="button" className={styles.nameLink} onClick={() => setExpanded(isOpen ? null : request.id)}>
                          {request.technicianName}
                        </button>
                        <br />
                        <span className="sub">Technician</span>
                      </span>
                    </td>
                    <td>
                      <div className={styles.mainDate}>
                        {request.intervals[0]?.date}
                        {request.intervals.length > 1 ? ` – ${request.intervals[request.intervals.length - 1]?.date}` : ""}
                      </div>
                      <div className={styles.times}>
                        {request.intervals.length === 1
                          ? `${time(request.intervals[0]?.startMin ?? 0)} – ${time(request.intervals[0]?.endMin ?? 0)}`
                          : `${request.intervals.length} days`}
                      </div>
                    </td>
                    <td>{request.category}</td>
                    <td><StatusPill status={request.status} /></td>
                    <td>
                      <div className={styles.analysisLbl}>{analysisLabel(request)}</div>
                      <div className={styles.analysisBar}><span style={{ width: `${pct}%`, background: request.reportStatus === "NEEDS_COORDINATION" ? "var(--warning)" : undefined }} /></div>
                    </td>
                    <td>{request.status === "READY" && <button className={styles.approveBtn} disabled={busy} onClick={() => approve(request.id)}>Approve</button>}</td>
                  </tr>
                  {isOpen && (
                    <tr className={styles.detailRow}>
                      <td colSpan={6}>
                        <div className={styles.detailBody}>
                          <div>
                            <p className={ui.sectionLabel}>Request</p>
                            <div className={styles.kvRow}><span className="k">Status</span><span>{request.status}</span></div>
                            <div className={styles.kvRow}><span className="k">Category</span><span>{request.category}</span></div>
                            <div className={styles.kvRow}><span className="k">Submitted</span><span>{new Date(request.createdAt).toLocaleDateString()}</span></div>
                            <p className={ui.sectionLabel} style={{ marginTop: 12 }}>Explanation</p>
                            <p className={styles.explanation}>{request.reason}</p>
                          </div>
                          <div>
                            <p className={ui.sectionLabel}>Requested intervals</p>
                            {request.intervals.map((interval) => (
                              <div key={interval.date} className={styles.intervalRow}>
                                <span>{interval.date}</span><span>{time(interval.startMin)} – {time(interval.endMin)}</span>
                              </div>
                            ))}
                          </div>
                          <div>
                            <p className={ui.sectionLabel}>Conflict analysis</p>
                            <p className={styles.analysisLbl}>{analysisLabel(request)}</p>
                            <div className={styles.analysisBar}><span style={{ width: `${pct}%` }} /></div>
                            {request.report != null && (
                              <details className={styles.rawToggle}>
                                <summary>View full repair report</summary>
                                <pre>{JSON.stringify(request.report, null, 2)}</pre>
                              </details>
                            )}
                            <div style={{ marginTop: 14 }}>
                              <button className={ui.button} type="button" onClick={() => setExpanded(null)}>Collapse</button>
                            </div>
                          </div>
                        </div>
                      </td>
                    </tr>
                  )}
                </Fragment>
              );
            })}
          </tbody>
        </table>
      </div>

      {showForm && (
        <>
          <div className={styles.scrim} onClick={() => setShowForm(false)} />
          <div className={styles.slideover}>
            <button className={styles.close} type="button" onClick={() => setShowForm(false)}>&#10005;</button>
            <h3>New time-off request</h3>
            <form onSubmit={submit} style={{ display: "flex", flexDirection: "column", gap: 14 }}>
              <div className={styles.field}>
                <label>Technician</label>
                <select value={technicianId} onChange={(event) => setTechnicianId(event.target.value)}>
                  {technicians.map((t) => <option key={t.id} value={t.id}>{t.name}</option>)}
                </select>
              </div>
              <div className={styles.fieldRow}>
                <div className={styles.field}>
                  <label>First date</label>
                  <input type="date" required value={firstDate} onChange={(event) => setFirstDate(event.target.value)} />
                </div>
                <div className={styles.field}>
                  <label>Last date</label>
                  <input type="date" value={lastDate} min={firstDate} onChange={(event) => setLastDate(event.target.value)} />
                </div>
              </div>
              <div className={styles.fieldRow}>
                <div className={styles.field}>
                  <label>From</label>
                  <input type="time" required value={start} onChange={(event) => setStart(event.target.value)} />
                </div>
                <div className={styles.field}>
                  <label>To</label>
                  <input type="time" required value={end} onChange={(event) => setEnd(event.target.value)} />
                </div>
              </div>
              <div className={styles.field}>
                <label>Reason category</label>
                <select value={category} onChange={(event) => setCategory(event.target.value)}>
                  {timeOffCategories.map((c) => <option key={c} value={c}>{c}</option>)}
                </select>
              </div>
              <div className={styles.field}>
                <label>Explain the planned absence</label>
                <textarea required maxLength={500} rows={3} value={reason} onChange={(event) => setReason(event.target.value)}
                  placeholder="A couple sentences on what's happening and why it falls on these dates." />
              </div>
              <button className={`${ui.button} ${ui.buttonBrand}`} type="submit" disabled={busy || !technicianId}>
                Submit request
              </button>
            </form>
          </div>
        </>
      )}
    </div>
  );
}
