"use client";

import { readResponse, timeOffResult } from "@/lib/contracts";
import { useRouter } from "next/navigation";
import { useState } from "react";

type Request = {
  id: string; technicianId: string; technicianName: string; reason: string; status: string; createdAt: string;
  intervals: Array<{ date: string; startMin: number; endMin: number }>;
  reportStatus: string | null; reportProgress: number | null; report: unknown;
};

function minutes(value: string) { const [hour = 0, minute = 0] = value.split(":").map(Number); return hour * 60 + minute; }
function time(value: number) { return `${String(Math.floor(value / 60)).padStart(2, "0")}:${String(value % 60).padStart(2, "0")}`; }

export default function TimeOffDemo({ technicians, requests }: { technicians: Array<{ id: string; name: string }>; requests: Request[] }) {
  const router = useRouter();
  const [technicianId, setTechnicianId] = useState(technicians[0]?.id ?? "");
  const [firstDate, setFirstDate] = useState("");
  const [lastDate, setLastDate] = useState("");
  const [start, setStart] = useState("08:00");
  const [end, setEnd] = useState("17:00");
  const [reason, setReason] = useState("");
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState("");

  async function submit(event: React.FormEvent) {
    event.preventDefault(); setBusy(true); setMessage("");
    try {
      const response = await fetch("/api/time-off", { method: "POST", headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ technicianId, firstDate, lastDate: lastDate || firstDate, startMin: minutes(start), endMin: minutes(end), reason }) });
      const data = await readResponse(response, timeOffResult);
      setMessage(`Request ${data.requestId} submitted for review.`); setReason(""); router.refresh();
    } catch (error) { setMessage(error instanceof Error ? error.message : "Could not submit request"); }
    finally { setBusy(false); }
  }

  async function approve(id: string) {
    setBusy(true); setMessage("");
    try {
      const response = await fetch("/api/time-off/approve", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ id }) });
      await readResponse(response, timeOffResult);
      setMessage(`Request ${id} approved.`); router.refresh();
    } catch (error) { setMessage(error instanceof Error ? error.message : "Could not approve request"); }
    finally { setBusy(false); }
  }

  return <>
    <form onSubmit={submit} style={{ display: "grid", gap: 10, maxWidth: 420 }}>
      <label>Technician <select value={technicianId} onChange={(event) => setTechnicianId(event.target.value)}>{technicians.map((tech) => <option key={tech.id} value={tech.id}>{tech.name}</option>)}</select></label>
      <label>First date <input type="date" required value={firstDate} onChange={(event) => setFirstDate(event.target.value)} /></label>
      <label>Last date <input type="date" value={lastDate} min={firstDate} onChange={(event) => setLastDate(event.target.value)} /></label>
      <p>The same local time interval applies to each selected date.</p>
      <label>From <input type="time" required value={start} onChange={(event) => setStart(event.target.value)} /></label>
      <label>To <input type="time" required value={end} onChange={(event) => setEnd(event.target.value)} /></label>
      <label>Reason <textarea required maxLength={500} value={reason} onChange={(event) => setReason(event.target.value)} /></label>
      <button disabled={busy || !technicianId}>Submit time-off request</button>
    </form>
    {message && <p role="status">{message}</p>}
    <h2>Request history and staff queue</h2>
    <table cellPadding={8}><thead><tr><th>Technician</th><th>Dates and local times</th><th>Reason</th><th>Status</th><th>Analysis</th><th>Staff action</th></tr></thead>
      <tbody>{requests.map((request) => <tr key={request.id}>
        <td>{request.technicianName}</td>
        <td>{request.intervals.map((interval) => <div key={interval.date}>{interval.date} {time(interval.startMin)} to {time(interval.endMin)}</div>)}</td>
        <td>{request.reason}</td><td>{request.status}</td>
        <td>{request.reportStatus ?? "Queued"} {request.reportProgress ?? 0}%
          {request.report != null && <details><summary>Repair report</summary><pre style={{ whiteSpace: "pre-wrap", maxWidth: 450 }}>{JSON.stringify(request.report, null, 2)}</pre></details>}</td>
        <td>{request.status === "READY" && <button disabled={busy} onClick={() => approve(request.id)}>Approve repair</button>}</td>
      </tr>)}</tbody>
    </table>
  </>;
}
