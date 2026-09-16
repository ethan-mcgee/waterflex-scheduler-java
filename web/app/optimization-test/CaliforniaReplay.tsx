"use client";

import { useEffect, useState } from "react";
import styles from "./optimizationTest.module.css";

type Result = {
  seed: number; strategy: string; profile: string; requests: number; booked: number;
  bookability: number; paid_route_proxy_minutes_per_booked: number;
  mean_days_to_service: number; modeled_arrival_reliability: number;
  offers_below_95_percent: number; window_or_shift_violations: number;
};
type Offer = { rank: number; date: string; window_start_min: number; window_end_min: number;
  modeled_reliability: number | null; expected_added_paid_route_minutes: number | null; reason: string };
type Report = {
  version: string; market: string; timezone: string; audit: { input_rows: number; accepted_rows: number;
    excluded_rows: number; exclusion_reasons: Record<string, number> };
  splits: { training: number; tuning: number; test: number };
  model: { exact_codes: number; pooled_codes: number; gap_observations: number };
  tuning: { duration_p95_coverage_before_scaling: number | null; gap_p95_coverage_before_scaling: number | null;
    duration_scale: number; gap_scale: number };
  replay: { seeds: number[]; samples_per_offer: number; request_limit: number | null; results: Result[];
    ranked_window_examples: { request: string; requested_date: string; strategy: string; offers: Offer[] }[] };
  release_gate: { status: string; full_test: boolean; comparisons: { seed: number; profile: string;
    passes: boolean; checks: Record<string, boolean> }[] };
  limitations: string[];
};

const pct = (n: number) => `${(n * 100).toFixed(1)}%`;
const time = (minute: number) => `${Math.floor(minute / 60) % 12 || 12}:${String(minute % 60).padStart(2, "0")}${minute < 720 ? " AM" : " PM"}`;

export default function CaliforniaReplay() {
  const [report, setReport] = useState<Report | null>(null);
  const [error, setError] = useState("");
  useEffect(() => {
    fetch("/api/optimization-test/california", { cache: "no-store" })
      .then(async (response) => {
        if (!response.ok) throw new Error(response.status === 404 ? "Generate a local report to view the California pilot." : "California report could not be loaded.");
        return response.json() as Promise<Report>;
      })
      .then(setReport).catch((caught: Error) => setError(caught.message));
  }, []);
  const failedChecks = report?.release_gate.comparisons.flatMap((comparison) =>
    Object.entries(comparison.checks).filter(([, passed]) => !passed).map(([check]) => check)) ?? [];
  return <section className={styles.california} aria-label="California historical replay">
    <div className={styles.californiaTitle}><div><p>Read-only dispatcher pilot</p><h2>California historical replay</h2></div>
      {report && <strong className={report.release_gate.status === "fail" ? styles.californiaFailure : undefined}>{report.release_gate.status === "pass" ? "Replay gates pass" : report.release_gate.status === "fail" ? "Replay gates fail" : "Partial replay"}</strong>}</div>
    {!report && <p>{error || "Loading local replay report..."}</p>}
    {report && <>
      <p>Historical recommendations only. Customer-facing offers are unchanged. Paid route time is a <strong>proxy</strong> because technician home locations are unavailable.</p>
      {failedChecks.length > 0 && <p className={styles.californiaWarning}>This ranking is not ready for a customer pilot. Failed checks across matched seeds and choices: {Array.from(new Set(failedChecks)).map((item) => item.replaceAll("_", " ")).join("; ")}.</p>}
      <div className={styles.californiaStats}>
        <div><strong>{report.audit.accepted_rows.toLocaleString()}</strong><span>usable of {report.audit.input_rows.toLocaleString()} exported rows</span></div>
        <div><strong>{report.splits.training.toLocaleString()} / {report.splits.tuning.toLocaleString()} / {report.splits.test.toLocaleString()}</strong><span>train / tune / untouched test</span></div>
        <div><strong>{report.model.exact_codes} / {report.model.pooled_codes}</strong><span>exact / pooled complaint codes</span></div>
        <div><strong>{report.replay.seeds.join(", ")}</strong><span>same-date request-order seeds</span></div>
      </div>
      <p>{report.release_gate.full_test ? "Full held-out replay" : report.replay.request_limit === null ? "Full held-out requests with fewer than three seeds" : `Partial replay of ${report.replay.request_limit} held-out requests`} with {report.replay.samples_per_offer} modeled timing samples per evaluated insertion. Modeled reliability requires every appointment on the affected route to arrive in its window and the route to end by 5 PM. It is not observed arrival performance.</p>
      <div className={styles.tableWrap}><table><caption>Matched control and history-informed results by seed and customer-choice scenario</caption><thead><tr><th>Seed</th><th>Choice</th><th>Ranking</th><th>Booked</th><th>Bookability</th><th>Paid proxy min / booked</th><th>Days to service</th><th>Modeled route success</th><th>Offers below 95%</th><th>Window or shift violations</th></tr></thead><tbody>
        {report.replay.results.map((r) => <tr key={`${r.seed}-${r.profile}-${r.strategy}`}><td>{r.seed}</td><td>{r.profile.replaceAll("_", " ")}</td><td>{r.strategy}</td><td>{r.booked} / {r.requests}</td><td>{pct(r.bookability)}</td><td>{r.paid_route_proxy_minutes_per_booked.toFixed(1)}</td><td>{r.mean_days_to_service.toFixed(2)}</td><td>{pct(r.modeled_arrival_reliability)}</td><td>{r.offers_below_95_percent}</td><td>{r.window_or_shift_violations}</td></tr>)}
      </tbody></table></div>
      <details><summary>Release gates across all seeds and choices</summary><div className={styles.tableWrap}><table><thead><tr><th>Seed</th><th>Choice</th>{Object.keys(report.release_gate.comparisons[0]?.checks ?? {}).map((key) => <th key={key}>{key.replaceAll("_", " ")}</th>)}</tr></thead><tbody>{report.release_gate.comparisons.map((c) => <tr key={`${c.seed}-${c.profile}`}><td>{c.seed}</td><td>{c.profile.replaceAll("_", " ")}</td>{Object.entries(c.checks).map(([key, passed]) => <td key={key}>{passed ? "Pass" : "Fail"}</td>)}</tr>)}</tbody></table></div></details>
      <details><summary>Example ranked two-hour windows and reasons</summary>{report.replay.ranked_window_examples.map((example) => <article className={styles.californiaExample} key={example.request}><h3>{example.request} requested {example.requested_date}</h3>{example.offers.length ? <ol>{example.offers.map((offer) => <li key={offer.rank}>{offer.date}, {time(offer.window_start_min)} to {time(offer.window_end_min)}. {offer.reason}. Modeled reliability {offer.modeled_reliability === null ? "not evaluated" : pct(offer.modeled_reliability)}; expected added paid time {offer.expected_added_paid_route_minutes === null ? "not evaluated" : `${offer.expected_added_paid_route_minutes.toFixed(1)} min`}.</li>)}</ol> : <p>No feasible ranked windows.</p>}</article>)}</details>
      <details><summary>Import exclusions, model tuning and limits</summary><p>{report.audit.excluded_rows.toLocaleString()} rows excluded: {Object.entries(report.audit.exclusion_reasons).map(([key, value]) => `${key.replaceAll("_", " ")} ${value}`).join("; ")}. A row may have multiple reasons.</p><p>2026 H1 tuning coverage of training p95: service duration {report.tuning.duration_p95_coverage_before_scaling === null ? "n/a" : pct(report.tuning.duration_p95_coverage_before_scaling)}, between-visit excess {report.tuning.gap_p95_coverage_before_scaling === null ? "n/a" : pct(report.tuning.gap_p95_coverage_before_scaling)}. Applied scales: {report.tuning.duration_scale} duration, {report.tuning.gap_scale} gap.</p><ul>{report.limitations.map((item) => <li key={item}>{item}</li>)}</ul><p>Before a customer pilot: supply technician homes, a live California schedule and request source, and continuing actual-arrival outcomes.</p></details>
    </>}
  </section>;
}
