"use client";

import { useState, type ChangeEvent, type FormEvent, type ReactNode } from "react";
import { useRouter } from "next/navigation";
import { z } from "zod";
import { readResponse, errorMessage } from "@/lib/contracts";
import { DAILY_SEARCHES, MAX_BOOKING_DEADLINE_MS, MAX_SEARCH_SECONDS, MIN_BOOKING_DEADLINE_MS, type DailySearch, type SolverSettingsView } from "@/lib/clientSettings";
import { HORIZON_MODES, MAX_FULL_PERCENT, MAX_LEAD_WEEKDAYS, MIN_FULL_PERCENT, MIN_LEAD_WEEKDAYS, type HorizonMode } from "@/lib/bookingHorizonCore";
import ui from "../components/ui.module.css";
import styles from "./settings.module.css";

const savedView = z.object({ version: z.int().nonnegative(), updatedAt: z.iso.datetime() }).loose();

type Draft = {
  regularHourly: string; overtimeHourly: string; mileagePerMile: string; travelBufferPercent: string;
  travelBufferMinutes: string; fairnessBudgetPercent: string; offerLimit: string; windowHours: string; bookingHorizonWeekdays: string;
  horizonMode: string; horizonLeadWeekdays: string; horizonFullPercent: string;
  dailySearch: string; costSearchSeconds: string; fairnessSearchSeconds: string; bookingDeadlineMs: string; regularWindowThreshold: string;
};

const SEARCH_LABELS: Record<DailySearch, string> = { TABU_KOPT: "Tabu + K opt (recommended)", TABU: "Tabu (previous default)" };
const HORIZON_LABELS: Record<HorizonMode, string> = { FIXED: "Fixed number of weekdays", DYNAMIC: "Follows the schedule" };

const EMPTY: Draft = { regularHourly: "", overtimeHourly: "", mileagePerMile: "", travelBufferPercent: "",
  travelBufferMinutes: "", fairnessBudgetPercent: "", offerLimit: "", windowHours: "", bookingHorizonWeekdays: "",
  horizonMode: "", horizonLeadWeekdays: "", horizonFullPercent: "",
  dailySearch: "", costSearchSeconds: "", fairnessSearchSeconds: "", bookingDeadlineMs: "", regularWindowThreshold: "" };

function draftOf(view: SolverSettingsView | null): Draft {
  if (view === null) return EMPTY;
  return { regularHourly: view.regularHourly, overtimeHourly: view.overtimeHourly, mileagePerMile: view.mileagePerMile,
    travelBufferPercent: view.travelBufferPercent, travelBufferMinutes: String(view.travelBufferMinutes),
    fairnessBudgetPercent: view.fairnessBudgetPercent, offerLimit: String(view.offerLimit), windowHours: String(view.windowHours),
    bookingHorizonWeekdays: String(view.bookingHorizonWeekdays), horizonMode: view.horizonMode,
    horizonLeadWeekdays: String(view.horizonLeadWeekdays), horizonFullPercent: String(view.horizonFullPercent), dailySearch: view.dailySearch,
    costSearchSeconds: String(view.costSearchSeconds), fairnessSearchSeconds: String(view.fairnessSearchSeconds),
    bookingDeadlineMs: String(view.bookingDeadlineMs), regularWindowThreshold: String(view.regularWindowThreshold) };
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
          fairnessBudgetPercent: draft.fairnessBudgetPercent, offerLimit: whole(draft.offerLimit), windowHours: whole(draft.windowHours),
          bookingHorizonWeekdays: whole(draft.bookingHorizonWeekdays), horizonMode: draft.horizonMode,
          horizonLeadWeekdays: whole(draft.horizonLeadWeekdays), horizonFullPercent: whole(draft.horizonFullPercent), dailySearch: draft.dailySearch,
          costSearchSeconds: whole(draft.costSearchSeconds), fairnessSearchSeconds: whole(draft.fairnessSearchSeconds),
          bookingDeadlineMs: whole(draft.bookingDeadlineMs), regularWindowThreshold: whole(draft.regularWindowThreshold),
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
          <Row label="Arrival window" hint="How long each offered arrival window is, 1 to 8 hours. Windows start on the hour.">
            <select {...field("windowHours")} required>
              {draft.windowHours === "" && <option value="">Choose</option>}
              {[1, 2, 3, 4, 5, 6, 7, 8].map(hours => <option key={hours} value={String(hours)}>{hours}</option>)}
            </select> <span>hours</span>
          </Row>
          <Row label="Booking horizon" hint="Fixed books the same number of weekdays every time. Follows the schedule books a few weekdays past the furthest booked-out weekday; overnight runs cover the same days.">
            <select {...field("horizonMode")} required>
              {draft.horizonMode === "" && <option value="">Choose</option>}
              {HORIZON_MODES.map(mode => <option key={mode} value={mode}>{HORIZON_LABELS[mode]}</option>)}
            </select>
          </Row>
          <Row label={draft.horizonMode === "DYNAMIC" ? "Longest horizon" : "Horizon length"}
            hint={draft.horizonMode === "DYNAMIC" ? "The furthest customers can book, in weekdays from tomorrow, 1 to 15." : "How many weekdays ahead customers can book, starting tomorrow, 1 to 15."}>
            <input inputMode="numeric" {...field("bookingHorizonWeekdays")} required /> <span>weekdays</span>
          </Row>
          <Row label="Weekdays past booked out" hint={`For a horizon that follows the schedule: weekdays added past the furthest booked-out weekday, ${MIN_LEAD_WEEKDAYS} to ${MAX_LEAD_WEEKDAYS}. Days nobody works are not counted.`}>
            <input inputMode="numeric" {...field("horizonLeadWeekdays")} required /> <span>weekdays</span>
          </Row>
          <Row label="Booked out at" hint={`For a horizon that follows the schedule: how much of the technicians' open time their routes must fill, from shift start to the end of the last visit, ${MIN_FULL_PERCENT} to ${MAX_FULL_PERCENT}.`}>
            <input inputMode="numeric" {...field("horizonFullPercent")} required /> <span>%</span>
          </Row>
          <Row label="Search deadline" hint={`How long a booking search may take before it answers with what it found, ${MIN_BOOKING_DEADLINE_MS} to ${MAX_BOOKING_DEADLINE_MS}.`}>
            <input inputMode="numeric" {...field("bookingDeadlineMs")} required /> <span>ms</span>
          </Row>
          <Row label="Window threshold" hint="Distinct regular windows a first pass must exceed before the search stops widening early. Lower answers faster; higher looks harder for more windows.">
            <input inputMode="numeric" {...field("regularWindowThreshold")} required /> <span>windows</span>
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
        <section className={ui.card}>
          <h2>Daily search</h2>
          <p className={ui.sectionLabel}>
            Used for dispatch proposals, overnight runs and time-off repairs. A proposal waits for the whole search time,
            and an overnight run spends it on each day. Together the two times may be at most {MAX_SEARCH_SECONDS} seconds.
          </p>
          <Row label="Search method" hint="Tabu + K opt also moves runs of visits and untangles routes; it found cheaper plans in the Tabu variant study.">
            <select {...field("dailySearch")} required>
              {draft.dailySearch === "" && <option value="">Choose</option>}
              {DAILY_SEARCHES.map(search => <option key={search} value={search}>{SEARCH_LABELS[search]}</option>)}
            </select>
          </Row>
          <Row label="Cost search time" hint="Seconds spent looking for the lowest-cost plan, 5 to 240. A repair spends both times here.">
            <input inputMode="numeric" {...field("costSearchSeconds")} required /> <span>seconds</span>
          </Row>
          <Row label="Fairness search time" hint="Seconds then spent spreading work more evenly within the fairness budget. 0 skips it.">
            <input inputMode="numeric" {...field("fairnessSearchSeconds")} required /> <span>seconds</span>
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
