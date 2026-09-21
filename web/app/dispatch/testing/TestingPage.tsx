"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { calendarDateInTz } from "@/lib/date";
import { DEFAULT_TEST_CONFIG, type TestConfig } from "@/lib/bookingTestCore";
import { FAKE_SERVICE_CODES, OMAHA_TIMEZONE, type FakeLocation } from "@/lib/fakeDataCore";
import type { OptimizationRun, SlotOffer } from "@/lib/engineClient";
import styles from "./testing.module.css";

interface RunSummary { id: string; status: string; createdAt: string; config: TestConfig }
interface Run extends RunSummary {
  revision: number; error: string | null; horizon: string[]; currentHorizon: string[];
  requests: Array<{ id: string; ordinal: number; input: { location: FakeLocation; serviceCode: string }; status: string;
    offers: SlotOffer[]; selected: SlotOffer | null; elapsedMs: number; serviceDate: string | null;
    startedAt: string | null; error: string | null; attempts: unknown[] }>;
  previews: Array<{ id: string; serviceDate: string; optimizationId: string | null; result: OptimizationRun | null; error: string | null }>;
  applied: Array<{ id: string; status: string; appliedAt: string | null }>;
}
async function api(body?: object, id?: string) {
  const response = await fetch(`/api/dispatch/testing${id ? `?id=${encodeURIComponent(id)}` : ""}`, body ? {
    method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body), signal: AbortSignal.timeout(170_000),
  } : { cache: "no-store", signal: AbortSignal.timeout(15_000) });
  const result = await response.json();
  if (!response.ok) throw new Error(result.error ?? `Request failed (${response.status})`);
  return result;
}
function windowLabel(offer: SlotOffer) {
  const format = (value: string) => new Intl.DateTimeFormat("en-US", { timeZone: OMAHA_TIMEZONE, hour: "numeric", minute: "2-digit" }).format(new Date(value));
  return `${offer.date}: ${format(offer.windowStart)} to ${format(offer.windowEnd)}`;
}
const mean = (values: number[]) => values.length ? (values.reduce((a, b) => a + b, 0) / values.length).toFixed(1) : "n/a";
export default function TestingPage() {
  const [config, setConfig] = useState<TestConfig>({ ...DEFAULT_TEST_CONFIG, weights: [...DEFAULT_TEST_CONFIG.weights] });
  const [runs, setRuns] = useState<RunSummary[]>([]);
  const [run, setRun] = useState<Run | null>(null);
  const [horizon, setHorizon] = useState<string[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [driving, setDriving] = useState(false);
  const continueRef = useRef(false);
  const mounted = useRef(true);
  const newId = useRef<string | null>(null);
  const refreshHistory = useCallback(async () => {
    const data = await api(); setRuns(data.runs); setHorizon(data.horizon);
  }, []);
  useEffect(() => {
    mounted.current = true;
    void refreshHistory().then(async () => {
      const id = new URLSearchParams(window.location.search).get("run");
      if (id) setRun(await api(undefined, id));
    }).catch(e => setError(e.message));
    const halt = () => { continueRef.current = false; };
    window.addEventListener("pagehide", halt);
    return () => { mounted.current = false; halt(); window.removeEventListener("pagehide", halt); };
  }, [refreshHistory]);
  useEffect(() => {
    if (!run) return;
    // Read-only polling shows the in-flight row and remote Pause/Stop actions.
    const timer = setInterval(() => { void api(undefined, run.id).then(data => { if (mounted.current) setRun(data); }).catch(e => setError(e.message)); }, 2000);
    return () => clearInterval(timer);
  }, [run?.id]); // eslint-disable-line react-hooks/exhaustive-deps

  async function drive(initial: Run) {
    continueRef.current = true; setDriving(true);
    let current = initial;
    try {
      while (continueRef.current && mounted.current && current.status === "RUNNING") {
        current = await api({ id: current.id, action: "advance", revision: current.revision });
        if (mounted.current) setRun(current);
      }
    } catch (e) {
      setError(`${(e as Error).message} Progression halted. Reload progress and explicitly Resume to reconcile before retrying.`);
      // Do not retry an ambiguous network outcome automatically.
      continueRef.current = false;
    } finally { if (mounted.current) { setDriving(false); void refreshHistory(); } }
  }
  async function start() {
    setBusy(true); setError(null);
    try {
      newId.current ??= crypto.randomUUID();
      const created = await api({ id: newId.current, action: "create", config });
      setRun(created); window.history.replaceState(null, "", `?run=${created.id}`);
      void refreshHistory();
      newId.current = null;
      const current = await api({ id: created.id, action: "resume" }); setRun(current);
      void drive(current);
    } catch (e) { setError((e as Error).message); } finally { setBusy(false); }
  }
  async function control(action: "resume" | "pause" | "stop") {
    if (!run) return;
    if (action !== "resume") continueRef.current = false;
    setBusy(true); setError(null);
    try {
      const current = await api({ id: run.id, action }); setRun(current);
      if (action === "resume") void drive(current);
    } catch (e) { setError((e as Error).message); } finally { setBusy(false); }
  }
  async function load(id: string) {
    setError(null);
    try { setRun(await api(undefined, id)); window.history.replaceState(null, "", `?run=${id}`); }
    catch (e) { setError((e as Error).message); }
  }
  const booked = run?.requests.filter(r => r.status === "BOOKED") ?? [];
  const noOffer = run?.requests.filter(r => r.status === "NO_OFFER") ?? [];
  const completed = [...booked, ...noOffer];
  const daysUntil = booked.flatMap(r => r.serviceDate && r.startedAt ? [(Date.parse(r.serviceDate) - Date.parse(calendarDateInTz(new Date(r.startedAt), OMAHA_TIMEZONE))) / 86400000] : []);
  return <main className={styles.page}>
    <nav><a href="/dispatch">Dispatch</a><a href="/schedule">Schedule</a></nav>
    <h1>Sequential booking test</h1>
    <p>Book synthetic Omaha requests into the current local schedule, then preview each affected day. Existing appointments and configuration are retained.</p>
    <p><strong>Booking horizon:</strong> {(run?.currentHorizon ?? horizon).join(", ")} (America/Chicago, next ten weekdays).</p>
    <section className={styles.panel}>
      <h2>New run</h2>
      <fieldset disabled={busy || driving} className={styles.controls}>
        <label>Requests<input type="number" min="1" max="100" value={config.count} onChange={e => setConfig({ ...config, count: Number(e.target.value) })} /></label>
        <label>Random seed<input type="number" min="0" max="4294967295" value={config.seed} onChange={e => setConfig({ ...config, seed: Number(e.target.value) })} /></label>
        <label>Offer selection<select value={config.policy} onChange={e => setConfig({ ...config, policy: e.target.value as TestConfig["policy"] })}>
          <option value="earliest">Earliest offered window</option><option value="first">Scheduler&apos;s first offer</option><option value="random">Seeded random</option>
        </select></label>
        {FAKE_SERVICE_CODES.map((code, index) => <label key={code}>{code.replaceAll("_", " ")} weight<input type="number" min="0" max="100" value={config.weights[index]} onChange={e => setConfig({ ...config, weights: config.weights.map((w, i) => i === index ? Number(e.target.value) : w) })} /></label>)}
        <button onClick={() => void start()}>Start new run</button>
      </fieldset>
      <p>A seed reproduces inputs and selection randomness. The schedule, current date, road graph, and solver can change results.</p>
    </section>
    <section className={styles.panel}>
      <label>Run history<select aria-label="Run history" value={run?.id ?? ""} disabled={busy || driving} onChange={e => { if (e.target.value) void load(e.target.value); }}>
        <option value="">Choose a saved run</option>{runs.map(r => <option key={r.id} value={r.id}>{new Date(r.createdAt).toLocaleString()} | {r.status} | seed {r.config.seed} | {r.id.slice(0, 8)}</option>)}
      </select></label>
      {run && <>
        <h2>{run.status} {driving ? "(progressing in this tab)" : ["COMPLETED", "STOPPED"].includes(run.status) ? "(saved history)" : "(read-only until Resume)"}</h2>
        <p>Run {run.id} | {run.config.policy} | seed {run.config.seed}</p>
        <div className={styles.controls}>
          <button disabled={busy || driving || ["COMPLETED", "STOPPED"].includes(run.status)} onClick={() => void control("resume")}>Resume</button>
          <button disabled={busy || run.status !== "RUNNING"} onClick={() => void control("pause")}>Pause</button>
          <button disabled={busy || ["COMPLETED", "STOPPED"].includes(run.status)} onClick={() => void control("stop")}>Stop</button>
          <button disabled={busy} onClick={() => void load(run.id)}>Reload progress</button>
        </div>
        <p>Pause and Stop finish the current operation. Stop is final. Closing this page stops progression after the current operation; reopening requires Resume.</p>
        <div className={styles.stats}>
          <span>Requested <strong>{run.requests.length}</strong></span><span>Booked <strong>{booked.length}</strong></span><span>No offer <strong>{noOffer.length}</strong></span>
          <span>Errors <strong>{run.requests.filter(r => r.status === "ERROR").length}</strong></span>
          <span>Offer availability <strong>{completed.length ? `${(100 * booked.length / completed.length).toFixed(1)}%` : "n/a"}</strong></span>
          <span>Mean offers / completed request <strong>{mean(completed.map(r => r.offers.length))}</strong></span>
          <span>Mean booking latency <strong>{mean(booked.map(r => r.elapsedMs / 1000))} s</strong></span><span>Mean days until service <strong>{mean(daysUntil)}</strong></span>
        </div>
        <p>Availability excludes pending and failed requests. Latency totals recorded processing attempts, excluding paused time.</p>
        {run.error && <p role="alert" className={styles.error}>{run.error}</p>}
        <div className={styles.scroll}><table><thead><tr><th>#</th><th>Location</th><th>Service</th><th>Offered windows</th><th>Chosen window</th><th>Outcome</th><th>Elapsed</th></tr></thead><tbody>
          {run.requests.map(r => <tr key={r.id}><td>{r.ordinal + 1}</td><td>{r.input.location.neighborhood}<br />{r.input.location.line1}</td><td>{r.input.serviceCode.replaceAll("_", " ")}</td>
            <td>{r.offers.map(o => <div key={o.offerId}>{windowLabel(o)}</div>)}</td><td>{r.selected ? windowLabel(r.selected) : "None"}</td><td>{r.status}{r.error && <div className={styles.error}>{r.error}</div>}
              {r.attempts.length > 0 && <details><summary>{r.attempts.length} recorded attempts</summary><pre>{JSON.stringify(r.attempts, null, 2)}</pre></details>}</td><td>{(r.elapsedMs / 1000).toFixed(1)} s</td></tr>)}
        </tbody></table></div>
        <h2>Optimization previews</h2>
        <p>Each preview covers the entire affected day, including pre-existing appointments. Savings are proposals. Review routes and use the existing guarded Apply control in Dispatch for manual approval.</p>
        {!run.previews.length && <p>Previews start automatically after all requests finish while this page is progressing.</p>}
        {run.previews.map(p => {
          const result = p.result;
          const actual = run.applied?.find(a => a.id === p.optimizationId);
          const sum = (phase: "before" | "after", key: "drive_minutes" | "route_minutes" | "overtime_minutes" | "modeled_cost_cents") => result?.[`route_summary_${phase}`].reduce((total, route) => total + route[key], 0) ?? 0;
          return <article key={p.id} className={styles.preview}><h3>{p.serviceDate}: {result?.status ?? "Pending"}</h3>
            <p>{result?.reason ?? p.error} {actual?.appliedAt ? `Applied at ${new Date(actual.appliedAt).toLocaleString()}. Metrics below are the saved proposal.` : "Not applied."}</p>
            {result && <><table><thead><tr><th>Day metric</th><th>Before</th><th>Proposed</th></tr></thead><tbody>
              {([ ["Drive minutes", "drive_minutes"], ["Paid route minutes", "route_minutes"], ["Overtime minutes", "overtime_minutes"], ["Modeled cost ($)", "modeled_cost_cents"] ] as const).map(([label, key]) => <tr key={key}><td>{label}</td><td>{(sum("before", key) / (key === "modeled_cost_cents" ? 100 : 1)).toFixed(2)}</td><td>{(sum("after", key) / (key === "modeled_cost_cents" ? 100 : 1)).toFixed(2)}</td></tr>)}
            </tbody></table><p>Proposed savings: ${(result.objective_improvement / 100).toFixed(2)} | Changed appointments: {result.appointments_moved} | Solver: {result.solver_status}</p></>}
            <a href={`/schedule?week=${p.serviceDate}`}>View schedule</a>{" | "}<a href={`/dispatch?date=${p.serviceDate}&run=${p.optimizationId ?? ""}`}>Compare routes and review proposal</a>
          </article>;
        })}
      </>}
    </section>
    {error && <p role="alert" className={styles.error}>{error}</p>}
  </main>;
}
