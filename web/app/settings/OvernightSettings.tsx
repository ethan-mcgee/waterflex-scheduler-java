"use client";

import Link from "next/link";
import { useCallback, useEffect, useState } from "react";
import type { z } from "zod";
import { errorMessage, overnightRun, overnightRunRequested, overnightSettings, overnightTimesSaved, readResponse } from "@/lib/contracts";
import { MAX_RUN_TIMES, OVERNIGHT_TIME_ZONE } from "@/lib/overnightCore";
import ui from "../components/ui.module.css";
import styles from "./settings.module.css";

type Run = z.infer<typeof overnightRun>;

const STATUS_LABEL: Record<Run["status"], string> = { QUEUED: "Waiting for the overnight worker", RUNNING: "Running", FINISHED: "Finished", ABANDONED: "Stopped before it finished" };

function summary(run: Run): string {
  const waiting = run.days.filter(day => day.proposal?.state === "WAITING").length;
  const skipped = run.days.filter(day => day.outcome === "SKIPPED").length;
  const failed = run.days.filter(day => day.outcome === "FAILED").length;
  return `${run.days.length} day(s): ${waiting} waiting for approval, ${skipped} skipped, ${failed} failed`;
}

function dayText(day: Run["days"][number]): string {
  if (day.proposal === null) return `${day.outcome === "SKIPPED" ? "Skipped" : "Failed"}: ${day.message ?? "no reason recorded"}`;
  const state = { WAITING: "improvement waiting for approval", APPLIED: "applied", REFUSED: "can no longer be applied", NOTHING_TO_APPLY: "no improvement found" };
  return state[day.proposal.state];
}

/** A client's overnight optimization: when it runs, "Run now", and what the latest runs proposed. */
export default function OvernightSettings({ initialTimes }: { initialTimes: string[] }) {
  const [times, setTimes] = useState<string[]>(initialTimes);
  const [added, setAdded] = useState("");
  const [runs, setRuns] = useState<Run[]>([]);
  const [status, setStatus] = useState<{ kind: "saved" | "error"; text: string } | null>(null);
  const [busy, setBusy] = useState(false);

  const load = useCallback(async () => {
    const loaded = await readResponse(await fetch("/api/clients/overnight", { cache: "no-store" }), overnightSettings);
    setRuns(loaded.runs);
  }, []);
  useEffect(() => { void load().catch(error => setStatus({ kind: "error", text: errorMessage(error) })); }, [load]);
  const active = runs.some(run => run.status === "QUEUED" || run.status === "RUNNING");
  // While a run is queued or running, its days appear as they finish.
  useEffect(() => {
    if (!active) return;
    const timer = setInterval(() => { void load().catch(error => setStatus({ kind: "error", text: errorMessage(error) })); }, 5000);
    return () => clearInterval(timer);
  }, [active, load]);

  async function save(next: string[]) {
    setBusy(true); setStatus(null);
    try {
      const response = await fetch("/api/clients/overnight", { method: "PUT", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ times: next }) });
      const saved = await readResponse(response, overnightTimesSaved);
      setTimes(saved.times); setAdded("");
      setStatus({ kind: "saved", text: saved.times.length === 0 ? "Overnight runs are manual only." : "Run times saved." });
    } catch (error) { setStatus({ kind: "error", text: errorMessage(error) }); }
    finally { setBusy(false); }
  }

  async function runNow() {
    setBusy(true); setStatus(null);
    try {
      const requested = await readResponse(await fetch("/api/clients/overnight/run", { method: "POST" }), overnightRunRequested);
      setStatus({ kind: "saved", text: requested.alreadyActive ? "A run is already queued or running." : "Run queued." });
      await load();
    } catch (error) { setStatus({ kind: "error", text: errorMessage(error) }); }
    finally { setBusy(false); }
  }

  return (
    <section className={ui.card} aria-label="Overnight optimization">
      <h2>Overnight optimization</h2>
      <p className={ui.sectionLabel}>
        Proposes an optimized day for every day ahead (ten weekdays) in each of this client&apos;s metros. Improvements wait on the
        dispatch board for a dispatcher to apply them. Times are {OVERNIGHT_TIME_ZONE} time.
      </p>
      <div className={styles.times}>
        {times.length === 0 && <span>Manual only: runs start with Run now.</span>}
        {times.map(time => <span key={time} className={styles.time}>{time}
          <button type="button" className={ui.iconButton} aria-label={`Remove ${time}`} disabled={busy} onClick={() => void save(times.filter(item => item !== time))}>x</button>
        </span>)}
      </div>
      <div className={styles.actions}>
        <label>Add a run time <input type="time" value={added} onChange={event => setAdded(event.target.value)} /></label>
        <button type="button" className={ui.button} disabled={busy || added === "" || times.includes(added) || times.length >= MAX_RUN_TIMES}
          onClick={() => void save([...times, added].sort())}>Add</button>
        <button type="button" className={`${ui.button} ${ui.buttonBrand}`} disabled={busy || active} onClick={() => void runNow()}>Run now</button>
        {status && <p role={status.kind === "error" ? "alert" : "status"} className={status.kind === "error" ? styles.error : styles.saved}>{status.text}</p>}
      </div>
      <div className={styles.runs}>
        {runs.length === 0 && <span>No runs yet.</span>}
        {runs.map(run => <details key={run.runId} open={run.status === "RUNNING"}>
          <summary>
            {run.trigger === "MANUAL" ? "Run now" : `Scheduled ${new Date(run.scheduledFor ?? run.createdAt).toLocaleString()}`}
            {" | "}{STATUS_LABEL[run.status]}{" | "}{summary(run)}
          </summary>
          <ul>{run.days.map(day => <li key={`${day.metroId}|${day.serviceDate}`}>
            <Link href={`/dispatch?metroId=${encodeURIComponent(day.metroId)}&date=${day.serviceDate}`}>{day.metroId} {day.serviceDate}</Link>: {dayText(day)}
          </li>)}</ul>
        </details>)}
      </div>
    </section>
  );
}
