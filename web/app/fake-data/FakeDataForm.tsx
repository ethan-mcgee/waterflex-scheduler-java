"use client";

import Link from "next/link";
import { useState } from "react";
import styles from "./fakeData.module.css";

interface Summary {
  requested: number;
  created: number;
  skipped: number;
  seed: number;
  startDate: string;
  endDate: string;
  byDate: Record<string, number>;
  byService: Record<string, number>;
  warnings: string[];
}

const SERVICE_NAMES: Record<string, string> = {
  FILTER_SWAP: "Filter replacement",
  SYSTEM_INSPECTION: "System inspection",
  REPAIR_DIAGNOSTIC: "Repair or diagnostic",
  SOFTENER_INSTALL: "Softener installation",
};

async function readResponse<T>(response: Response): Promise<T> {
  const body = (await response.json()) as T & { error?: string };
  if (!response.ok) throw new Error(body.error ?? "Request failed.");
  return body;
}

export default function FakeDataForm({ defaultDate }: { defaultDate: string }) {
  const [startDate, setStartDate] = useState(defaultDate);
  const [endDate, setEndDate] = useState(defaultDate);
  const [totalCalls, setTotalCalls] = useState("10");
  const [seed, setSeed] = useState("");
  const [summary, setSummary] = useState<Summary | null>(null);
  const [notice, setNotice] = useState("");
  const [error, setError] = useState("");
  const [busy, setBusy] = useState<"generate" | "clear" | null>(null);

  async function generate(event: React.FormEvent) {
    event.preventDefault();
    setBusy("generate");
    setError("");
    setNotice("");
    try {
      const result = await readResponse<Summary>(
        await fetch("/api/fake-data", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({
            startDate,
            endDate,
            totalCalls: Number(totalCalls),
            ...(seed === "" ? {} : { seed: Number(seed) }),
          }),
        })
      );
      setSummary(result);
    } catch (caught) {
      setError(caught instanceof Error ? caught.message : "Generation failed.");
    } finally {
      setBusy(null);
    }
  }

  async function clearGenerated() {
    if (!window.confirm(`Clear generated appointments from ${startDate} through ${endDate}?`)) return;
    setBusy("clear");
    setError("");
    setNotice("");
    try {
      const query = new URLSearchParams({ startDate, endDate });
      const result = await readResponse<{ removed: number }>(
        await fetch(`/api/fake-data?${query}`, { method: "DELETE" })
      );
      setSummary(null);
      setNotice(`Removed ${result.removed} generated appointment${result.removed === 1 ? "" : "s"}.`);
    } catch (caught) {
      setError(caught instanceof Error ? caught.message : "Cleanup failed.");
    } finally {
      setBusy(null);
    }
  }

  return (
    <>
      <form className={styles.card} onSubmit={generate}>
        <div className={styles.dateRow}>
          <label>
            <span>Start date</span>
            <input type="date" value={startDate} onChange={(event) => setStartDate(event.target.value)} required />
          </label>
          <label>
            <span>End date</span>
            <input type="date" value={endDate} onChange={(event) => setEndDate(event.target.value)} required />
          </label>
        </div>
        <div className={styles.dateRow}>
          <label>
            <span>Total calls</span>
            <input type="number" min="1" max="100" step="1" value={totalCalls} onChange={(event) => setTotalCalls(event.target.value)} required />
          </label>
          <label>
            <span>Reproducible seed (optional)</span>
            <input type="number" min="0" max="4294967295" step="1" value={seed} onChange={(event) => setSeed(event.target.value)} placeholder="Generated automatically" />
          </label>
        </div>
        <p className={styles.hint}>
          Ranges may span up to 31 days. Calls are placed on weekdays only. Missing Omaha demo
          configuration is restored automatically. Leave the seed blank for new random addresses on every run.
        </p>
        {error && <p className={styles.error} role="alert">{error}</p>}
        {notice && <p className={styles.notice} role="status">{notice}</p>}
        <div className={styles.actions}>
          <button className={styles.primary} type="submit" disabled={busy !== null}>
            {busy === "generate" ? "Generating..." : "Generate appointments"}
          </button>
          <button className={styles.danger} type="button" onClick={clearGenerated} disabled={busy !== null}>
            {busy === "clear" ? "Clearing..." : "Clear generated data"}
          </button>
        </div>
      </form>

      {summary && (
        <section className={styles.summary} aria-live="polite">
          <h2>Generation result</h2>
          <div className={styles.metrics}>
            <div><strong>{summary.requested}</strong><span>Requested</span></div>
            <div><strong>{summary.created}</strong><span>Created</span></div>
            <div><strong>{summary.skipped}</strong><span>Skipped</span></div>
            <div><strong>{summary.seed}</strong><span>Seed</span></div>
          </div>
          {summary.warnings.length > 0 && (
            <div className={styles.warning}>
              <strong>Warnings</strong>
              <ul>{summary.warnings.map((warning) => <li key={warning}>{warning}</li>)}</ul>
            </div>
          )}
          <div className={styles.breakdowns}>
            <div>
              <h3>By date</h3>
              {Object.entries(summary.byDate).map(([date, count]) => (
                <div className={styles.resultRow} key={date}>
                  <span>{date}: {count}</span>
                  <span><Link href={`/schedule?week=${date}`}>Schedule</Link> · <Link href={`/dispatch?date=${date}`}>Dispatch</Link></span>
                </div>
              ))}
            </div>
            <div>
              <h3>By service</h3>
              {Object.entries(summary.byService).map(([service, count]) => (
                <div className={styles.resultRow} key={service}><span>{SERVICE_NAMES[service] ?? service}</span><strong>{count}</strong></div>
              ))}
            </div>
          </div>
        </section>
      )}
    </>
  );
}
