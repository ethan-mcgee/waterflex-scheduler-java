"use client";

import { useState, type FormEvent } from "react";
import { useRouter } from "next/navigation";
import { errorMessage, readResponse, reasonCodeSaved } from "@/lib/contracts";
import { MAX_DURATION_MIN, MIN_DURATION_MIN, suggestedMinutes } from "@/lib/reasonCodesCore";
import { Button, Panel } from "@/app/portal/ui";
import styles from "../settingsTable.module.css";

export interface ReasonCodeRow {
  code: string;
  name: string;
  estDurationMin: number;
  bufferPct: number;
  active: boolean;
  qualifiedTechnicians: number;
}

/** A buffer as the page shows it: 0.15 is "15". */
const percent = (fraction: number) => String(Math.round(fraction * 10000) / 100);

/** The minutes and buffer as typed, or why they cannot be saved. */
function timing(minutes: string, buffer: string): { estDurationMin: number; bufferPct: number } | { problem: string } {
  const estDurationMin = /^\d+$/.test(minutes.trim()) ? Number(minutes.trim()) : Number.NaN;
  if (!Number.isSafeInteger(estDurationMin) || estDurationMin < MIN_DURATION_MIN || estDurationMin > MAX_DURATION_MIN)
    return { problem: `Minutes must be a whole number from ${MIN_DURATION_MIN} to ${MAX_DURATION_MIN}.` };
  const bufferPercent = buffer.trim() === "" ? Number.NaN : Number(buffer.trim());
  if (!Number.isFinite(bufferPercent) || bufferPercent < 0 || bufferPercent > 100) return { problem: "Buffer must be a percentage from 0 to 100." };
  return { estDurationMin, bufferPct: bufferPercent / 100 };
}

async function send(url: string, method: "POST" | "PATCH", body: unknown) {
  await readResponse(await fetch(url, { method, headers: { "Content-Type": "application/json" }, body: JSON.stringify(body) }), reasonCodeSaved);
}

/** The catalog with a form to add a code, and each row's label, minutes and buffer editable in place. */
export default function ReasonCodesEditor({ codes }: { codes: readonly ReasonCodeRow[] }) {
  const router = useRouter();
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [editing, setEditing] = useState<{ code: string; name: string; minutes: string; buffer: string } | null>(null);
  const [draft, setDraft] = useState({ code: "", name: "", minutes: "", buffer: "15" });

  async function run(action: () => Promise<void>) {
    setBusy(true); setError(null);
    try { await action(); router.refresh(); return true; }
    catch (failure) { setError(errorMessage(failure, "The reason code could not be saved. Try again.")); return false; }
    finally { setBusy(false); }
  }

  async function add(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const time = timing(draft.minutes, draft.buffer);
    if ("problem" in time) { setError(time.problem); return; }
    if (await run(() => send("/api/settings/reason-codes", "POST", { code: draft.code.trim(), name: draft.name.trim(), ...time })))
      setDraft({ code: "", name: "", minutes: "", buffer: "15" });
  }

  async function saveEdit(row: ReasonCodeRow) {
    if (editing === null) return;
    const time = timing(editing.minutes, editing.buffer);
    if ("problem" in time) { setError(time.problem); return; }
    if (await run(() => send(`/api/settings/reason-codes/${encodeURIComponent(row.code)}`, "PATCH", { name: editing.name.trim(), ...time, active: row.active })))
      setEditing(null);
  }

  const setActive = (row: ReasonCodeRow, active: boolean) => run(() => send(`/api/settings/reason-codes/${encodeURIComponent(row.code)}`, "PATCH",
    { name: row.name, estDurationMin: row.estDurationMin, bufferPct: row.bufferPct, active }));

  return <>
    <form className={styles.addForm} onSubmit={add} aria-label="Add a reason code">
      <label>Code<input className={styles.input} required maxLength={40} value={draft.code} onChange={event => setDraft({ ...draft, code: event.target.value })} /></label>
      <label>Label<input className={styles.input} required maxLength={120} value={draft.name} onChange={event => setDraft({ ...draft, name: event.target.value })} /></label>
      <label>Minutes<input className={styles.input} required inputMode="numeric" value={draft.minutes} onChange={event => setDraft({ ...draft, minutes: event.target.value })} /></label>
      <label>Buffer (%)<input className={styles.input} required inputMode="decimal" value={draft.buffer} onChange={event => setDraft({ ...draft, buffer: event.target.value })} /></label>
      <Button type="submit" disabled={busy}>Add reason code</Button>
    </form>
    {error !== null && <p className={styles.error} role="alert">{error}</p>}
    <Panel className={styles.panel} label="Reason codes">
      <table className={styles.table}>
        <thead><tr><th>Code</th><th>Label</th><th className={styles.numeric}>Minutes</th><th className={styles.numeric}>Buffer (%)</th>
          <th className={styles.numeric}>Suggests (min)</th><th className={styles.numeric}>Qualified technicians</th><th>Status</th><th><span className="sr-only">Actions</span></th></tr></thead>
        <tbody>{codes.length === 0 ? <tr><td colSpan={8}>No reason codes yet. Add one above, or import WaterFlex&apos;s list.</td></tr> : codes.map(row => {
          const open = editing?.code === row.code ? editing : null;
          return <tr key={row.code} className={row.active ? undefined : styles.retired} data-reason-code={row.code}>
            <td>{row.code}</td>
            <td>{open === null ? row.name
              : <input className={styles.input} aria-label={`Label for ${row.code}`} maxLength={120} value={open.name} onChange={event => setEditing({ ...open, name: event.target.value })} />}</td>
            <td className={styles.numeric}>{open === null ? row.estDurationMin
              : <input className={styles.input} aria-label={`Minutes for ${row.code}`} inputMode="numeric" value={open.minutes} onChange={event => setEditing({ ...open, minutes: event.target.value })} />}</td>
            <td className={styles.numeric}>{open === null ? percent(row.bufferPct)
              : <input className={styles.input} aria-label={`Buffer for ${row.code}`} inputMode="decimal" value={open.buffer} onChange={event => setEditing({ ...open, buffer: event.target.value })} />}</td>
            <td className={styles.numeric}>{suggestedMinutes([row]) ?? "Not available"}</td>
            <td className={styles.numeric}>{row.qualifiedTechnicians}</td>
            <td><span className={styles.status} data-active={row.active}>{row.active ? "Active" : "Retired"}</span></td>
            <td><div className={styles.actions}>{open !== null ? <>
              <Button disabled={busy} onClick={() => void saveEdit(row)}>Save</Button>
              <Button variant="text" disabled={busy} onClick={() => setEditing(null)}>Cancel</Button>
            </> : <>
              <Button variant="text" disabled={busy} onClick={() => { setError(null); setEditing({ code: row.code, name: row.name, minutes: String(row.estDurationMin), buffer: percent(row.bufferPct) }); }}>
                Edit</Button>
              <Button variant="text" disabled={busy} onClick={() => void setActive(row, !row.active)}>{row.active ? "Retire" : "Restore"}</Button>
            </>}</div></td>
          </tr>;
        })}</tbody>
      </table>
    </Panel>
  </>;
}
