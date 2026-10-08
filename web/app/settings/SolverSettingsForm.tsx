"use client";

import { useState, type ChangeEvent, type FormEvent, type ReactNode } from "react";
import { useRouter } from "next/navigation";
import { z } from "zod";
import { readResponse, errorMessage } from "@/lib/contracts";
import type { SolverSettingsView } from "@/lib/clientSettings";
import ui from "../components/ui.module.css";
import styles from "./settings.module.css";

const savedView = z.object({ version: z.int().nonnegative(), updatedAt: z.iso.datetime() }).loose();

type Draft = {
  regularHourly: string; overtimeHourly: string; mileagePerMile: string; travelBufferPercent: string;
  travelBufferMinutes: string; fairnessBudgetPercent: string; offerLimit: string; bookingHorizonWeekdays: string;
};

const EMPTY: Draft = { regularHourly: "", overtimeHourly: "", mileagePerMile: "", travelBufferPercent: "",
  travelBufferMinutes: "", fairnessBudgetPercent: "", offerLimit: "", bookingHorizonWeekdays: "" };

function draftOf(view: SolverSettingsView | null): Draft {
  if (view === null) return EMPTY;
  return { regularHourly: view.regularHourly, overtimeHourly: view.overtimeHourly, mileagePerMile: view.mileagePerMile,
    travelBufferPercent: view.travelBufferPercent, travelBufferMinutes: String(view.travelBufferMinutes),
    fairnessBudgetPercent: view.fairnessBudgetPercent, offerLimit: String(view.offerLimit),
    bookingHorizonWeekdays: String(view.bookingHorizonWeekdays) };
}

/** Whole numbers only; anything else is sent as typed so the server rejects it instead of the form guessing. */
function whole(value: string): number | string {
  return /^[0-9]+$/.test(value.trim()) ? Number(value.trim()) : value;
}

export default function SolverSettingsForm({ clientName, initial, overnight }: { clientName: string; initial: SolverSettingsView | null; overnight: ReactNode }) {
  const router = useRouter();
  const [draft, setDraft] = useState<Draft>(() => draftOf(initial));
  const [version, setVersion] = useState<number | null>(initial?.version ?? null);
  const [updatedAt, setUpdatedAt] = useState<string | null>(initial?.updatedAt ?? null);
  const [status, setStatus] = useState<{ kind: "saved" | "error"; text: string } | null>(null);
  const [saving, setSaving] = useState(false);

  const field = (key: keyof Draft) => ({
    value: draft[key],
    onChange: (event: ChangeEvent<HTMLInputElement | HTMLSelectElement>) => {
      const value = event.target.value;
      setDraft(current => ({ ...current, [key]: value }));
    },
  });

  async function save(event: FormEvent) {
    event.preventDefault();
    setSaving(true);
    setStatus(null);
    try {
      const response = await fetch("/api/clients/settings", { method: "PUT", headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ expectedVersion: version, settings: {
          regularHourly: draft.regularHourly, overtimeHourly: draft.overtimeHourly, mileagePerMile: draft.mileagePerMile,
          travelBufferPercent: draft.travelBufferPercent, travelBufferMinutes: whole(draft.travelBufferMinutes),
          fairnessBudgetPercent: draft.fairnessBudgetPercent, offerLimit: whole(draft.offerLimit),
          bookingHorizonWeekdays: whole(draft.bookingHorizonWeekdays),
        } }) });
      const saved = await readResponse(response, savedView);
      setVersion(saved.version);
      setUpdatedAt(saved.updatedAt);
      setStatus({ kind: "saved", text: "Settings saved." });
      router.refresh();
    } catch (error) {
      setStatus({ kind: "error", text: errorMessage(error) });
    } finally {
      setSaving(false);
    }
  }

  return (
    <main className={styles.page}>
      <h1>Solver settings</h1>
      <p className={styles.lead}>
        Booking and routing settings for <strong>{clientName}</strong>. They are sent with every booking and daily
        routing calculation for this client once booking and dispatch run through the public scheduling API.
      </p>
      {version === null &&
        <p className={styles.notice} role="status">This client has no settings yet. Nothing is booked or routed for it until they are saved.</p>}
      <form onSubmit={event => void save(event)} className={styles.form}>
        <section className={ui.card}>
          <h2>Booking</h2>
          <Row label="Offers per search" hint="How many appointment choices a customer sees, 1 to 4.">
            <select {...field("offerLimit")} required>
              {draft.offerLimit === "" && <option value="">Choose</option>}
              {[1, 2, 3, 4].map(limit => <option key={limit} value={String(limit)}>{limit}</option>)}
            </select>
          </Row>
          <Row label="Booking horizon" hint="How many weekdays ahead customers can book, starting tomorrow, 1 to 15.">
            <input inputMode="numeric" {...field("bookingHorizonWeekdays")} required /> <span>weekdays</span>
          </Row>
        </section>
        <section className={ui.card}>
          <h2>Daily routing and costs</h2>
          <p className={ui.sectionLabel}>Booking also ranks choices by these costs.</p>
          <Row label="Regular pay" hint="Per technician hour."><span>$</span> <input inputMode="decimal" {...field("regularHourly")} required /></Row>
          <Row label="Overtime pay" hint="Per technician overtime hour."><span>$</span> <input inputMode="decimal" {...field("overtimeHourly")} required /></Row>
          <Row label="Mileage" hint="Per mile driven."><span>$</span> <input inputMode="decimal" {...field("mileagePerMile")} required /></Row>
          <Row label="Travel buffer" hint="Extra time added to each drive, as a share of the drive.">
            <input inputMode="decimal" {...field("travelBufferPercent")} required /> <span>%</span>
          </Row>
          <Row label="Travel buffer per drive" hint="Fixed extra minutes added to each drive, 0 to 120.">
            <input inputMode="numeric" {...field("travelBufferMinutes")} required /> <span>minutes</span>
          </Row>
          <Row label="Fairness budget" hint="How much a daily plan's cost may rise to spread work more evenly. 0 means fairness only breaks ties.">
            <input inputMode="decimal" {...field("fairnessBudgetPercent")} required /> <span>%</span>
          </Row>
        </section>
        <div className={styles.actions}>
          <button type="submit" className={`${ui.button} ${ui.buttonBrand}`} disabled={saving}>{saving ? "Saving" : "Save settings"}</button>
          {updatedAt && <small>Last saved {new Date(updatedAt).toLocaleString()}</small>}
          {status && <p role={status.kind === "error" ? "alert" : "status"} className={status.kind === "error" ? styles.error : styles.saved}>{status.text}</p>}
        </div>
      </form>
      {overnight}
    </main>
  );
}

function Row({ label, hint, children }: { label: string; hint: string; children: ReactNode }) {
  return (
    <label className={styles.row}>
      <span className={styles.label}>{label}<small>{hint}</small></span>
      <span className={styles.control}>{children}</span>
    </label>
  );
}
