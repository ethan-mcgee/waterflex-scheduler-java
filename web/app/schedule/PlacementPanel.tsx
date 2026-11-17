"use client";

import { useState, type FormEvent, type PointerEvent as ReactPointerEvent } from "react";
import { errorMessage } from "@/lib/contracts";
import { findGap, rankedFits, recommendation, relativeToBest, spotKey, verdict, type Fit, type Spot, type Verdict } from "@/lib/placementRules";
import type { InsertionGap, InsertionStart } from "@/lib/insertionContract";
import styles from "./schedule.module.css";
import type { PlacementState, PlacementSubject } from "./usePlacement";
import type { ScheduleJob, ScheduleService, ScheduleTechnician } from "./types";

const METERS_PER_MILE = 1609.344;

function formatTime(value: string, timezone: string): string {
  return new Date(value).toLocaleTimeString(undefined, { timeZone: timezone, hour: "numeric", minute: "2-digit" });
}

function formatDate(dateKey: string): string {
  const [year, month, day] = dateKey.split("-").map(Number);
  return new Date(year ?? 1970, (month ?? 1) - 1, day ?? 1).toLocaleDateString(undefined, { weekday: "short", month: "short", day: "numeric" });
}

const windowText = (window: { start: string; end: string }, timezone: string) => `${formatTime(window.start, timezone)} to ${formatTime(window.end, timezone)}`;

/** "adds 14 minutes of driving", or "saves 3 minutes" when the job sits on the way; buffered minutes, as the scheduler prices them. */
export function addedDriveText(gap: InsertionGap): string {
  if (gap.addedBufferedMinutes === undefined) return "No road route";
  const minutes = gap.addedBufferedMinutes;
  return minutes >= 0 ? `+${minutes} min drive` : `${minutes} min drive`;
}

function legText(gap: InsertionGap): string | null {
  if (gap.toJob === undefined || gap.fromJob === undefined || gap.addedMeters === undefined) return null;
  const replaced = gap.replaced === undefined ? "" : `, instead of ${gap.replaced.bufferedMinutes} min`;
  return `${gap.toJob.bufferedMinutes} min there and ${gap.fromJob.bufferedMinutes} min on${replaced} (${(gap.addedMeters / METERS_PER_MILE).toFixed(1)} miles added)`;
}

/** How the chosen gap's added drive compares with the recommendation's, as a sentence; null when it has no road route. */
function versusBest(gap: InsertionGap, best: Fit): { text: string; kind: "SAME" | "WORSE" | "BETTER" } | null {
  if (gap.addedBufferedMinutes === undefined) return null;
  const difference = gap.addedBufferedMinutes - best.addedBufferedMinutes;
  if (difference === 0) return { text: "The same added driving as the recommendation.", kind: "SAME" };
  return difference > 0 ? { text: `${difference} min more driving than the recommendation.`, kind: "WORSE" }
    : { text: `${-difference} min less driving than the recommendation.`, kind: "BETTER" };
}

/** The place the scheduler's answer recommends: the least added drive, and for a move only where the promise is kept. */
function Recommendation({ best, moving, chosenIsBest, name, timezone, onChoose }: {
  best: Fit; moving: boolean; chosenIsBest: boolean; name: (id: string) => string; timezone: string;
  onChoose: (spot: Spot, plannedStart: string | null) => void;
}) {
  const legs = legText(best.gap);
  return (
    <div className={styles.recommendation} data-recommendation={best.key}>
      <p className={styles.recommendationTitle}>Recommended</p>
      <p className={styles.recommendationPlace}>
        {name(best.spot.technicianId)}, {formatDate(best.spot.serviceDate)}, from {formatTime(best.firstStart.plannedStart, timezone)}
      </p>
      <p>Arrival window {windowText(best.firstStart.window, timezone)}{moving ? ", as promised" : ""}.</p>
      <p>{addedDriveText(best.gap)}{legs === null ? "" : `: ${legs}`}.</p>
      {!chosenIsBest && <button className={styles.recommendationButton} type="button"
        onClick={() => onChoose(best.spot, best.firstStart.plannedStart)}>Use the recommendation</button>}
    </div>
  );
}

/** The jobs waiting for the calendar: each can be dragged onto a route or placed from the best fits. */
function JobList({ jobs, active, onPlace, onPress, clickSuppressed }: {
  jobs: ScheduleJob[]; active: string | null; onPlace: (job: ScheduleJob) => void;
  onPress: (job: ScheduleJob, event: ReactPointerEvent) => void; clickSuppressed: () => boolean;
}) {
  if (jobs.length === 0) return <p className={styles.subtle}>No jobs are waiting to be scheduled.</p>;
  return (
    <ul className={styles.jobList} aria-label="Unscheduled jobs">
      {jobs.map(job => (
        <li key={job.id}>
          <button type="button" className={`${styles.jobCard} ${active === job.id ? styles.jobCardActive : ""}`} data-job-id={job.id}
            style={{ borderLeftColor: job.serviceColor.color }} disabled={!job.located} onPointerDown={job.located ? event => onPress(job, event) : undefined}
            onClick={() => { if (!clickSuppressed()) onPlace(job); }}>
            <span className={styles.cardCustomer}>{job.customerName}</span>
            <span className={styles.cardService}>{job.serviceName}, {job.durationMin} min</span>
            <span className={styles.subtle}>{job.addressLine}</span>
            {!job.located && <span className={styles.subtle}>Needs its pin confirmed before it can be placed.</span>}
            {job.followUp !== null && <span className={styles.subtle}>Follow-up: {job.followUp.toLowerCase()}</span>}
          </button>
        </li>
      ))}
    </ul>
  );
}

/**
 * A new job for the calendar. The server takes it in as the booking wizard does and needs a rooftop match for the
 * address; an address that needs its pin placed by hand goes through the booking wizard.
 */
function NewJobForm({ services, onCreated }: { services: ScheduleService[]; onCreated: (jobId: string, label: string) => void }) {
  const [open, setOpen] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [requestId, setRequestId] = useState(() => crypto.randomUUID());

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    const field = (name: string) => { const value = form.get(name); return typeof value === "string" ? value.trim() : ""; };
    const body = { requestId, firstName: field("firstName"), lastName: field("lastName"), email: field("email"), phone: field("phone"),
      line1: field("line1"), ...(field("line2") ? { line2: field("line2") } : {}), city: field("city"), state: field("state"),
      postalCode: field("postalCode"), serviceCode: field("serviceCode") };
    setBusy(true);
    setError(null);
    try {
      const response = await fetch("/api/schedule/placement/job", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body) });
      const payload: unknown = await response.json().catch(() => undefined);
      if (!response.ok) throw new Error(errorMessage(payload, "The job could not be created."));
      const jobId = payload !== null && typeof payload === "object" && "jobId" in payload && typeof payload.jobId === "string" ? payload.jobId : null;
      if (jobId === null) throw new Error("The portal did not return the new job.");
      const service = services.find(item => item.code === body.serviceCode)?.name ?? "service";
      onCreated(jobId, `${body.firstName} ${body.lastName}, ${service}`);
      setRequestId(crypto.randomUUID());
      setOpen(false);
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : "The job could not be created.");
    } finally {
      setBusy(false);
    }
  }

  if (!open) return <button className={styles.button} type="button" onClick={() => setOpen(true)}>New job</button>;
  return (
    <form className={styles.newJobForm} onSubmit={submit} aria-label="New job">
      <label>First name<input className={styles.select} name="firstName" required autoComplete="off" /></label>
      <label>Last name<input className={styles.select} name="lastName" required autoComplete="off" /></label>
      <label>Email<input className={styles.select} name="email" type="email" required autoComplete="off" /></label>
      <label>Phone<input className={styles.select} name="phone" type="tel" required autoComplete="off" /></label>
      <label>Street<input className={styles.select} name="line1" required autoComplete="off" /></label>
      <label>Unit<input className={styles.select} name="line2" autoComplete="off" /></label>
      <label>City<input className={styles.select} name="city" required autoComplete="off" /></label>
      <label>State<input className={styles.select} name="state" required maxLength={2} autoComplete="off" /></label>
      <label>ZIP<input className={styles.select} name="postalCode" required autoComplete="off" /></label>
      <label>Service
        <select className={styles.select} name="serviceCode" required defaultValue="">
          <option value="" disabled>Choose a service</option>
          {services.map(service => <option key={service.code} value={service.code}>{service.name}</option>)}
        </select>
      </label>
      <div className={styles.panelActions}>
        <button className={styles.button} type="submit" disabled={busy}>{busy ? "Creating..." : "Create and place"}</button>
        <button className={styles.button} type="button" onClick={() => setOpen(false)}>Cancel</button>
      </div>
      {error !== null && <p className={styles.deleteError} role="alert">{error} An address that needs its pin placed by hand can be booked in the booking wizard.</p>}
    </form>
  );
}

/** The verdict for the chosen spot, or null while nothing is chosen or the answer is not in. */
export function currentVerdict(placement: PlacementState | null): Verdict | null {
  if (placement === null || placement.status !== "ready" || placement.chosen === null) return null;
  return verdict(placement.preview.answer, placement.preview.original, placement.chosen.spot, placement.chosen.plannedStart);
}

function Comparison({ verdict: decided }: { verdict: Extract<Verdict, { kind: "KEEPS_PROMISE" | "CHANGES_PROMISE" }> }) {
  if (decided.comparison === null) return <p className={styles.subtle}>The previous assignment cannot be timed against this one: its route no longer holds as it stands.</p>;
  const { previous, next, sentence } = decided.comparison;
  const row = (label: string, a: string, b: string) => <tr><th scope="row">{label}</th><td>{a}</td><td>{b}</td></tr>;
  return (
    <div data-placement-comparison={decided.comparison.better}>
      <p>{sentence}</p>
      <table className={styles.comparisonTable}>
        <thead><tr><th scope="col">Both routes</th><th scope="col">Previous</th><th scope="col">With the move</th></tr></thead>
        <tbody>
          {row("Driving", `${previous.driveMinutes} min`, `${next.driveMinutes} min`)}
          {row("Distance", `${(previous.meters / METERS_PER_MILE).toFixed(1)} mi`, `${(next.meters / METERS_PER_MILE).toFixed(1)} mi`)}
          {row("Cost", `$${(previous.costCents / 100).toFixed(2)}`, `$${(next.costCents / 100).toFixed(2)}`)}
          {row("Late-arrival buffer used", `${Math.round(previous.bufferSeconds / 60)} min`, `${Math.round(next.bufferSeconds / 60)} min`)}
        </tbody>
      </table>
    </div>
  );
}

/** The chosen place: its starts, what it promises, how it compares and whether it can be saved. */
function PlacementCard({ placement, technicians, timezone, onChoose, onAcknowledge, onSave, onCancel }: {
  placement: PlacementState; technicians: ScheduleTechnician[]; timezone: string;
  onChoose: (spot: Spot, plannedStart: string | null) => void; onAcknowledge: (value: boolean) => void; onSave: () => void; onCancel: () => void;
}) {
  const verb = placement.subject.kind === "job" ? "Placing" : "Moving";
  const header = <div className={styles.panelHeader}><h2>{verb} {placement.subject.label}</h2>
    <button className={styles.button} type="button" onClick={onCancel}>Cancel</button></div>;
  if (placement.status === "loading") return <section className={styles.placementCard} aria-busy="true">{header}
    <p role="status">Finding every place on this week&apos;s routes...</p></section>;
  if (placement.status === "error") return <section className={styles.placementCard}>{header}<p role="alert">{placement.message}</p></section>;
  const { preview, chosen } = placement;
  const decided = currentVerdict(placement);
  const fits = rankedFits(preview.answer).slice(0, 5);
  const name = (id: string) => technicians.find(item => item.id === id)?.name ?? id;
  const found = chosen === null ? undefined : findGap(preview.answer, chosen.spot);
  const starts: InsertionStart[] = found?.gap.starts ?? [];
  const savable = decided !== null && decided.kind !== "CANNOT_HOLD" && !(decided.kind === "KEEPS_PROMISE" && decided.samePlace)
    && (decided.kind !== "CHANGES_PROMISE" || placement.acknowledged) && !placement.saving;
  const moving = preview.original !== null;
  const best = recommendation(preview.answer, moving);
  const chosenIsBest = best !== null && chosen !== null && spotKey(chosen.spot) === best.key && chosen.plannedStart !== null
    && Date.parse(chosen.plannedStart) === Date.parse(best.firstStart.plannedStart);
  const versus = best === null || found === undefined || chosenIsBest ? null : versusBest(found.gap, best);
  return (
    <section className={styles.placementCard} data-placement-state={decided?.kind ?? "READY"} aria-live="polite">
      {header}
      {preview.original !== null && <p className={styles.subtle}>Promised {formatDate(preview.original.serviceDate)}, {windowText(preview.original.window, timezone)}.</p>}
      {best !== null && <Recommendation best={best} moving={moving} chosenIsBest={chosenIsBest} name={name} timezone={timezone} onChoose={onChoose} />}
      {best === null && moving && fits.length > 0 && <p className={styles.subtle}>No place this week keeps the promised day and arrival window.</p>}
      {chosen === null ? <p role="status">Drag onto a route, or pick a best fit. Each gap shows its driving against the recommendation.</p> : <>
        <h3>Your pick: {name(chosen.spot.technicianId)}, {formatDate(chosen.spot.serviceDate)}</h3>
        {chosenIsBest && <p className={styles.versusBest} data-versus-best="BEST">This is the recommendation.</p>}
        {versus !== null && <p className={`${styles.versusBest} ${versus.kind === "WORSE" ? styles.versusBestWorse : ""}`} data-versus-best={versus.kind}>{versus.text}</p>}
        {found !== undefined && <p>{addedDriveText(found.gap)}{legText(found.gap) === null ? "" : `: ${legText(found.gap)}`}</p>}
        {decided?.kind === "CANNOT_HOLD" && <p className={styles.placementProblem} role="alert">{decided.reason} It can be dropped here, but not saved.</p>}
        {starts.length > 0 && <fieldset className={styles.startChips}>
          <legend>Start</legend>
          {starts.map(start => (
            <label key={start.plannedStart} className={chosen.plannedStart === start.plannedStart ? styles.startChipChosen : styles.startChip}>
              <input type="radio" name="placement-start" checked={chosen.plannedStart === start.plannedStart}
                onChange={() => onChoose(chosen.spot, start.plannedStart)} />
              {formatTime(start.plannedStart, timezone)}
            </label>
          ))}
        </fieldset>}
        {decided !== null && decided.kind !== "CANNOT_HOLD" && <p>Arrival window promised: {windowText(decided.start.window, timezone)}.</p>}
        {decided?.kind === "KEEPS_PROMISE" && (decided.samePlace
          ? <p role="status">This is where the appointment is now.</p>
          : <Comparison verdict={decided} />)}
        {decided?.kind === "CHANGES_PROMISE" && <div className={styles.policyViolation} role="alert">
          <strong>Dealership policy violation.</strong> This move changes what the customer was promised, from{" "}
          {formatDate(decided.previous.serviceDate)}, {windowText(decided.previous.window, timezone)} to{" "}
          {formatDate(chosen.spot.serviceDate)}, {windowText(decided.start.window, timezone)}. Contact the customer before saving.
          <label className={styles.acknowledge}>
            <input type="checkbox" checked={placement.acknowledged} onChange={event => onAcknowledge(event.target.checked)} />
            I acknowledge this breaks the dealership&apos;s policy on promised arrival windows.
          </label>
          <Comparison verdict={decided} />
        </div>}
        <div className={styles.panelActions}>
          <button className={styles.button} type="button" disabled={!savable} onClick={onSave}>{placement.saving ? "Saving..." : "Save"}</button>
        </div>
        {placement.saveError !== null && <p className={styles.deleteError} role="alert">{placement.saveError}</p>}
      </>}
      {fits.length > 0 && <>
        <h3>Best fits this week</h3>
        <ol className={styles.bestFits} aria-label="Best fits">
          {fits.map(fit => (
            <li key={fit.key}>
              <button type="button" className={styles.fitButton} data-fit={fit.key} onClick={() => onChoose(fit.spot, fit.firstStart.plannedStart)}>
                {formatDate(fit.spot.serviceDate)}, {name(fit.spot.technicianId)}, from {formatTime(fit.firstStart.plannedStart, timezone)}: {addedDriveText(fit.gap)}
                {best === null ? "" : best.key === fit.key ? ", recommended" : `, ${relativeToBest(fit.addedBufferedMinutes, best)}`}
              </button>
            </li>
          ))}
        </ol>
      </>}
      {fits.length === 0 && <p className={styles.subtle}>No place this week keeps every arrival window, shift and paid limit. Try another week.</p>}
    </section>
  );
}

/** The placement side panel: jobs waiting for the calendar, a new-job form, and the place being chosen. */
export default function PlacementPanel({ jobs, services, placement, technicians, timezone, onPlaceJob, onPressJob, clickSuppressed,
  onCreated, onChoose, onAcknowledge, onSave, onCancel }: {
  jobs: ScheduleJob[]; services: ScheduleService[]; placement: PlacementState | null; technicians: ScheduleTechnician[]; timezone: string;
  onPlaceJob: (subject: PlacementSubject) => void; onPressJob: (job: ScheduleJob, event: ReactPointerEvent) => void; clickSuppressed: () => boolean;
  onCreated: (jobId: string, label: string) => void;
  onChoose: (spot: Spot, plannedStart: string | null) => void; onAcknowledge: (value: boolean) => void; onSave: () => void; onCancel: () => void;
}) {
  return (
    <aside className={styles.placementPanel} aria-label="Place a job">
      {placement !== null && <PlacementCard placement={placement} technicians={technicians} timezone={timezone}
        onChoose={onChoose} onAcknowledge={onAcknowledge} onSave={onSave} onCancel={onCancel} />}
      <section>
        <h2>Unscheduled jobs</h2>
        <NewJobForm services={services} onCreated={onCreated} />
        <JobList jobs={jobs} active={placement?.subject.kind === "job" ? placement.subject.id : null} clickSuppressed={clickSuppressed}
          onPress={onPressJob} onPlace={job => onPlaceJob({ kind: "job", id: job.id, label: `${job.customerName}, ${job.serviceName}` })} />
      </section>
    </aside>
  );
}
