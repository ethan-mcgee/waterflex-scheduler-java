"use client";

import { z } from "zod";
import { readResponse, testRun, testHistory, testConfig, errorMessage } from "@/lib/contracts";
import { useCallback, useEffect, useRef, useState } from "react";
import dynamic from "next/dynamic";
import { calendarDateInTz } from "@/lib/date";
import { DEFAULT_TEST_CONFIG, type TestConfig, type TestConfigInput } from "@/lib/bookingTestCore";
import { FAKE_SERVICE_CODES, OMAHA_TIMEZONE, type FakeLocation } from "@/lib/fakeDataCore";
import type { OptimizationRun, SlotOffer } from "@/lib/engineClient";
import OptimizationReview from "@/app/dispatch/OptimizationReview";
import type { BoardAppointment, BoardTechnician } from "@/app/dispatch/types";
import styles from "./testing.module.css";

const DispatchMap = dynamic(() => import("@/app/dispatch/DispatchMap"), { ssr: false, loading: () => <p>Loading road routes...</p> });
const NO_TECHNICIANS: BoardTechnician[] = [];
const NO_APPOINTMENTS: BoardAppointment[] = [];
interface GenerationProgress { acceptedCount: number; targetCount: number; candidatesTried: number; batches: number; elapsedMs: number;
  completedAt: string | null; consecutiveNoProgressBatches: number }
interface RunSummary { id: string; status: string; createdAt: string; config: TestConfig; purgedAt: string | null; purgedCount: number | null;
  generation: GenerationProgress | null }
interface Run extends RunSummary {
  revision: number; error: string | null; horizon: string[]; currentHorizon: string[];
  requests: Array<{ id: string; ordinal: number; input: { location: FakeLocation; serviceCode: string }; status: string;
    offers: SlotOffer[]; selected: SlotOffer | null; elapsedMs: number; serviceDate: string | null;
    startedAt: string | null; error: string | null; attempts: unknown[] }>;
  previews: Array<{ id: string; serviceDate: string; optimizationId: string | null; result: OptimizationRun | null; error: string | null }>;
  applied: Array<{ id: string; status: string; appliedAt: string | null }>;
}
async function api<T>(schema: z.ZodType<T>, body?: object, id?: string) {
  const response = await fetch(`/api/dispatch/testing${id ? `?id=${encodeURIComponent(id)}` : ""}`, body ? {
    method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body), signal: AbortSignal.timeout(60_000),
  } : { cache: "no-store", signal: AbortSignal.timeout(15_000) });
  return readResponse(response, schema);
}
function windowLabel(offer: SlotOffer) {
  const format = (value: string) => new Intl.DateTimeFormat("en-US", { timeZone: OMAHA_TIMEZONE, hour: "numeric", minute: "2-digit" }).format(new Date(value));
  return `${offer.date}: ${format(offer.windowStart)} to ${format(offer.windowEnd)}`;
}
const mean = (values: number[]) => values.length ? (values.reduce((a, b) => a + b, 0) / values.length).toFixed(1) : "n/a";
function duration(ms: number) {
  const seconds = Math.floor(ms / 1000), minutes = Math.floor(seconds / 60), hours = Math.floor(minutes / 60);
  return hours ? `${hours}h ${minutes % 60}m ${seconds % 60}s` : minutes ? `${minutes}m ${seconds % 60}s` : `${seconds}s`;
}
function generationStatus(run: Run) {
  if (!run.generation) return "Address generation was completed by the legacy runner.";
  if (run.generation.completedAt) return "Address generation complete. Booking is underway or ready to continue.";
  if (run.status === "RUNNING") return "Searching for routable addresses.";
  if (run.status === "STOPPED") return "Address generation stopped.";
  if (run.status === "PAUSED") return run.error ? "Address generation failed and is paused." : "Address generation paused.";
  return `Address generation ${run.status.toLowerCase()}.`;
}
export default function TestingPage() {
  const [config, setConfig] = useState<TestConfigInput>({ ...DEFAULT_TEST_CONFIG, weights: [...DEFAULT_TEST_CONFIG.weights] });
  const [runs, setRuns] = useState<RunSummary[]>([]);
  const [run, setRun] = useState<Run | null>(null);
  const [horizon, setHorizon] = useState<string[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false), [driving, setDriving] = useState(false);
  const continueRef = useRef(false), mounted = useRef(true), newId = useRef<string | null>(null);
  const pollingRunId = run?.status === "RUNNING" ? run.id : null;
  const refreshHistory = useCallback(async () => { const data = await api(testHistory); setRuns(data.runs); setHorizon(data.horizon); }, []);
  useEffect(() => {
    mounted.current = true;
    void refreshHistory().then(async () => { const id = new URLSearchParams(window.location.search).get("run"); if (id) setRun(await api(testRun, undefined, id)); }).catch(e => setError(errorMessage(e)));
    const halt = () => { continueRef.current = false; }; window.addEventListener("pagehide", halt);
    return () => { mounted.current = false; halt(); window.removeEventListener("pagehide", halt); };
  }, [refreshHistory]);
  useEffect(() => {
    if (!pollingRunId) return;
    const timer = setInterval(() => { void api(testRun, undefined, pollingRunId).then(data => { if (mounted.current) setRun(data); }).catch(e => setError(errorMessage(e))); }, 2000);
    return () => clearInterval(timer);
  }, [pollingRunId]);
  async function drive(initial: Run) {
    continueRef.current = true; setDriving(true); let current = initial;
    try { while (continueRef.current && mounted.current && current.status === "RUNNING") { current = await api(testRun, { id: current.id, action: "advance", revision: current.revision }); if (mounted.current) setRun(current); } }
    catch (e) { setError(`${errorMessage(e)} Progression halted. Reload progress and explicitly Resume to reconcile before retrying.`); continueRef.current = false; }
    finally { if (mounted.current) { setDriving(false); void refreshHistory(); } }
  }
  async function start() {
    setBusy(true); setError(null);
    try {
      newId.current ??= crypto.randomUUID();
      const created = await api(testRun, { id: newId.current, action: "create", config }); setRun(created);
      window.history.replaceState(null, "", `?run=${created.id}`); void refreshHistory(); newId.current = null;
      const current = await api(testRun, { id: created.id, action: "resume" }); setRun(current); void drive(current);
    } catch (e) { setError(errorMessage(e)); } finally { setBusy(false); }
  }
  async function control(action: "resume" | "pause" | "stop") {
    if (!run) return; if (action !== "resume") continueRef.current = false; setBusy(true); setError(null);
    try { const current = await api(testRun, { id: run.id, action }); setRun(current); if (action === "resume") void drive(current); }
    catch (e) { setError(errorMessage(e)); } finally { setBusy(false); }
  }
  async function purge() {
    if (!run || !window.confirm("Delete only the generated appointments and booking records for this saved run? Optimization history and any effects already applied to surviving appointments remain.")) return;
    setBusy(true); setError(null);
    try { const current = await api(testRun, { id: run.id, action: "purge" }); setRun(current); await refreshHistory(); }
    catch (e) { setError(errorMessage(e)); } finally { setBusy(false); }
  }
  async function load(id: string) { setError(null); try { setRun(await api(testRun, undefined, id)); window.history.replaceState(null, "", `?run=${id}`); } catch (e) { setError(errorMessage(e)); } }
  const booked = run?.requests.filter(request => request.status === "BOOKED") ?? [];
  const noOffer = run?.requests.filter(request => request.status === "NO_OFFER") ?? [];
  const completed = [...booked, ...noOffer];
  const daysUntil = booked.flatMap(request => request.serviceDate && request.startedAt ? [(Date.parse(request.serviceDate) - Date.parse(calendarDateInTz(new Date(request.startedAt), OMAHA_TIMEZONE))) / 86400000] : []);
  return <main className={styles.page}><nav><a href="/dispatch">Dispatch</a><a href="/schedule">Schedule</a></nav>
    <h1>Sequential booking test</h1><p>Book distinct real OSM house addresses across the configured Omaha service area, then review and optionally apply each guarded whole-day optimization.</p>
    <p><strong>Booking horizon:</strong> {(run?.currentHorizon ?? horizon).join(", ")} (America/Chicago, next ten weekdays).</p>
    <section className={styles.panel}><h2>New run</h2><fieldset disabled={busy || driving} className={styles.controls}>
      <label>Requests<input type="number" min="1" max="100" value={config.count} onChange={event => setConfig({ ...config, count: Number(event.target.value) })} /></label>
      <label>Random seed (optional)<input type="number" min="0" max="4294967295" value={config.seed ?? ""} placeholder="Generated securely" onChange={event => setConfig({ ...config, seed: event.target.value === "" ? null : Number(event.target.value) })} /></label>
      <label>Offer selection<select value={config.policy} onChange={event => setConfig({ ...config, policy: testConfig.shape.policy.parse(event.target.value) })}>
        <option value="earliest">Earliest offered window</option><option value="first">Scheduler&apos;s first offer</option><option value="random">Seeded random</option></select></label>
      {FAKE_SERVICE_CODES.map((code, index) => <label key={code}>{code.replaceAll("_", " ")} weight<input type="number" min="0" max="100" value={config.weights[index]} onChange={event => setConfig({ ...config, weights: config.weights.map((weight, item) => item === index ? Number(event.target.value) : weight) })} /></label>)}
      <button onClick={() => void start()}>Start new run</button>
    </fieldset><p>A blank seed generates and saves a cryptographically random unsigned seed. An explicit seed reproduces candidate order when the map, Nominatim data, and database state are unchanged.</p></section>
    <section className={styles.panel}><label>Run history<select aria-label="Run history" value={run?.id ?? ""} disabled={busy || driving} onChange={event => { if (event.target.value) void load(event.target.value); }}>
      <option value="">Choose a saved run</option>{runs.map(item => <option key={item.id} value={item.id}>{new Date(item.createdAt).toLocaleString()} | {item.status} | {item.generation && !item.generation.completedAt ? `${item.generation.acceptedCount}/${item.generation.targetCount} addresses | ` : ""}seed {item.config.seed} | {item.id.slice(0, 8)}</option>)}</select></label>
      {run && <><h2>{run.status} {driving ? "(progressing in this tab)" : ["COMPLETED", "STOPPED", "PURGED"].includes(run.status) ? "(saved history)" : "(read-only until Resume)"}</h2>
        <p>Run {run.id} | {run.config.policy} | seed {run.config.seed}</p>
        <div className={styles.controls}>
          <button disabled={busy || driving || ["COMPLETED", "STOPPED", "PURGED"].includes(run.status)} onClick={() => void control("resume")}>Resume</button>
          <button disabled={busy || run.status !== "RUNNING"} onClick={() => void control("pause")}>Pause</button>
          <button disabled={busy || ["COMPLETED", "STOPPED", "PURGED"].includes(run.status)} onClick={() => void control("stop")}>Stop</button>
          <button disabled={busy} onClick={() => void load(run.id)}>Reload progress</button>
          <button className={styles.danger} disabled={busy || driving || !["PAUSED", "STOPPED", "COMPLETED", "PURGED"].includes(run.status)} onClick={() => void purge()}>Delete generated appointments</button>
        </div>
        {run.purgedAt && <p>Purged {run.purgedCount ?? 0} generated appointment(s) at {new Date(run.purgedAt).toLocaleString()}. This cleanup is complete and idempotent.</p>}
        <p>Pause and Stop finish the current operation. Stop is final. Purge removes only this run&apos;s generated booking records and does not reverse an already applied optimization.</p>
        {run.generation && <section className={styles.generation} aria-labelledby="generation-heading"><h3 id="generation-heading">Finding routable addresses</h3>
          <progress aria-label="Finding routable addresses" max={run.generation.targetCount} value={run.generation.acceptedCount} />
          <p aria-live="polite"><strong>{run.generation.acceptedCount} of {run.generation.targetCount} addresses ready.</strong> {generationStatus(run)}</p>
          <p>{run.generation.candidatesTried} candidates examined in {run.generation.batches} batches. Active elapsed time: {duration(run.generation.elapsedMs)}.</p>
          {run.generation.consecutiveNoProgressBatches >= 3 && !run.generation.completedAt && <p role="status" className={styles.warning}>No new addresses were found in the last {run.generation.consecutiveNoProgressBatches} batches. Searching will continue until you Pause or Stop the run.</p>}
        </section>}
        <div className={styles.stats}><span>Requested <strong>{run.config.count}</strong></span><span>Booked <strong>{booked.length}</strong></span><span>No offer <strong>{noOffer.length}</strong></span>
          <span>Errors <strong>{run.requests.filter(request => request.status === "ERROR").length}</strong></span><span>Offer availability <strong>{completed.length ? `${(100 * booked.length / completed.length).toFixed(1)}%` : "n/a"}</strong></span>
          <span>Mean offers / completed request <strong>{mean(completed.map(request => request.offers.length))}</strong></span><span>Mean booking latency <strong>{mean(booked.map(request => request.elapsedMs / 1000))} s</strong></span><span>Mean days until service <strong>{mean(daysUntil)}</strong></span></div>
        {run.error && <p role="alert" className={styles.error}>{run.error}</p>}
        <div className={styles.scroll}><table><thead><tr><th>#</th><th>Location</th><th>Service</th><th>Offered windows</th><th>Chosen window</th><th>Outcome</th><th>Elapsed</th></tr></thead><tbody>
          {run.requests.map(request => <tr key={request.id}><td>{request.ordinal + 1}</td><td>{request.input.location.line1}<br />{request.input.location.city}, {request.input.location.state} {request.input.location.postalCode}</td><td>{request.input.serviceCode.replaceAll("_", " ")}</td>
            <td>{request.offers.map(offer => <div key={offer.offerId}>{windowLabel(offer)}</div>)}</td><td>{request.selected ? windowLabel(request.selected) : "None"}</td><td>{request.status}{request.error && <div className={styles.error}>{request.error}</div>}</td><td>{(request.elapsedMs / 1000).toFixed(1)} s</td></tr>)}</tbody></table></div>
        <h2>Optimization previews</h2><p>Each preview covers the whole affected day. Apply is available only while the exact saved result remains a current PREVIEW; the scheduler rechecks every guarded apply condition.</p>
        {!run.previews.length && <p>Previews start automatically after all requests finish while this page is progressing.</p>}
        {run.previews.map(preview => {
          const actual = run.applied.find(item => item.id === preview.optimizationId);
          const result = preview.result && actual ? { ...preview.result, status: actual.status, applied_at: actual.appliedAt } : preview.result;
          return <article key={preview.id} className={styles.preview}><h3>{preview.serviceDate}</h3>{preview.error && <p className={styles.error}>{preview.error}</p>}
            {result && <OptimizationReview run={result} onApplied={async () => setRun(await api(testRun, undefined, run.id))}
              routes={phase => <div className={styles.testMap}><DispatchMap technicians={NO_TECHNICIANS} appointments={NO_APPOINTMENTS} timezone={OMAHA_TIMEZONE} metroId="metro-omaha"
                date={preview.serviceDate} runId={phase === "current" ? undefined : result.run_id} phase={phase} /></div>} />}
            <a href={`/schedule?week=${preview.serviceDate}`}>View schedule</a>{" | "}<a href={`/dispatch?date=${preview.serviceDate}&run=${preview.optimizationId ?? ""}`}>Open exact run in Dispatch</a>
          </article>;
        })}</>}
    </section>{error && <p role="alert" className={styles.error}>{error}</p>}
  </main>;
}
