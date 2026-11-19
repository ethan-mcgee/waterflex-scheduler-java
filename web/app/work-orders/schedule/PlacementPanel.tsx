"use client";

import { useState, type PointerEvent as ReactPointerEvent, type ReactNode } from "react";
import { ChevronsLeft, ChevronsRight } from "lucide-react";
import { todayInTz } from "@/lib/date";
import { bestOptions, findGap, findStart, spotKey, verdict, type PlaceOption, type Spot, type Verdict } from "@/lib/placementRules";
import type { InsertionGap, InsertionStart } from "@/lib/insertionContract";
import { workOrderLabel } from "@/lib/workOrdersDayCore";
import styles from "./calendar.module.css";
import WorkOrderPanel from "./WorkOrderPanel";
import type { PlacementState, PlacementSubject } from "./usePlacement";
import type { JobOffers } from "./useJobOffers";
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

/** The jobs waiting for the calendar: each can be dragged onto a route or placed from the best fits. */
function JobList({ jobs, waitingCount, active, onPlace, onPress, clickSuppressed }: {
  jobs: ScheduleJob[]; waitingCount: number; active: string | null; onPlace: (job: ScheduleJob) => void;
  onPress: (job: ScheduleJob, event: ReactPointerEvent) => void; clickSuppressed: () => boolean;
}) {
  if (jobs.length === 0) return <p className={styles.subtle}>
    {waitingCount === 0 ? "No jobs are waiting to be scheduled." : "No waiting jobs match these filters."}
  </p>;
  return (
    <ul className={styles.jobList} aria-label="Unscheduled jobs">
      {jobs.map(job => (
        <li key={job.id}>
          <button type="button" className={`${styles.jobCard} ${active === job.id ? styles.jobCardActive : ""}`} data-job-id={job.id} data-waiting-order={job.id}
            style={{ borderLeftColor: job.serviceColor.color }} disabled={!job.located} onPointerDown={job.located ? event => onPress(job, event) : undefined}
            onClick={() => { if (!clickSuppressed()) onPlace(job); }}>
            <span className={styles.cardCustomer}>{job.customerName}</span>
            <span className={styles.cardService}>{workOrderLabel(job.workOrder)}, {job.durationMin} min</span>
            <span className={styles.subtle}>{job.addressLine}</span>
            {!job.located && <span className={styles.subtle}>Needs its pin confirmed before it can be placed.</span>}
            {job.followUp !== null && <span className={styles.subtle}>Follow-up: {job.followUp.toLowerCase()}</span>}
          </button>
        </li>
      ))}
    </ul>
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

/** "+6 min", or "-3 min" when the place sits on the way: buffered minutes, as the scheduler prices them. */
function driveDelta(minutes: number): string {
  return minutes >= 0 ? `+${minutes} min` : `${minutes} min`;
}

/**
 * One offered option. A new job's row leads with the arrival window, which is what the customer chooses; a move's
 * window is fixed, so its row leads with the technician and start.
 */
function OptionRow({ option, moving, best, pick, on, name, timezone, onChoose }: {
  option: PlaceOption; moving: boolean; best: boolean; pick: boolean; on: boolean; name: (id: string) => string; timezone: string;
  onChoose: (spot: Spot, plannedStart: string | null) => void;
}) {
  const time = formatTime(option.start.plannedStart, timezone), date = formatDate(option.spot.serviceDate), who = name(option.spot.technicianId);
  const main = moving ? `${who}, ${time}` : windowText(option.start.window, timezone);
  const notes = [date, ...(moving ? [] : [who, `from ${time}`]), ...(option.samePlace ? ["where it is now"] : []),
    ...(moving && option.start.keepsWindow === false ? ["changes the window"] : [])];
  return (
    <li>
      <button type="button" className={`${styles.option} ${on ? styles.optionOn : ""}`} aria-pressed={on} data-option={option.key}
        data-option-best={best ? "true" : undefined} onClick={() => onChoose(option.spot, option.start.plannedStart)}>
        <span className={styles.optionMain}>{main}</span>
        <span className={styles.optionDrive}>
          {best && <span className={styles.optionTag}>Best</span>}
          {pick && <span className={styles.optionTagPick}>Your pick</span>}
          {driveDelta(option.addedBufferedMinutes)}
        </span>
        <span className={styles.optionMeta}>{notes.join(", ")}</span>
      </button>
    </li>
  );
}

/** "4:59", the time a held offer has left. */
function heldFor(expiresAt: string, now: number): string {
  const seconds = Math.max(0, Math.floor((Date.parse(expiresAt) - now) / 1000));
  return `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, "0")}`;
}

/**
 * The booking service's recommended times for a waiting job: the same search the booking page runs, under the dealer's
 * solver settings and from tomorrow on, each time held for a few minutes. Booking one writes the appointment; the
 * by-hand places below stay open the whole time.
 */
function RecommendedTimes({ offers, now, timezone, onBook, onSearch }: {
  offers: JobOffers; now: number; timezone: string; onBook: (offerId: string) => void; onSearch: () => void;
}) {
  const again = (busy: boolean) => <button className={styles.textButton} type="button" disabled={busy} onClick={onSearch}>Search again</button>;
  const body = (() => {
    if (offers.status === "searching") return <>
      <p role="status" className={styles.subtle}>Searching the booking service...</p>
      <ul className={styles.offerList} aria-hidden="true">{[0, 1].map(index => <li key={index} className={styles.optionSkeleton} />)}</ul>
    </>;
    if (offers.status === "failed") return <><p role="status" className={styles.subtle}>{offers.message}</p>{again(false)}</>;
    const busy = offers.booking !== null;
    return <>
      {offers.offers.length === 0
        ? <p role="status" className={styles.subtle}>{offers.message ?? "The booking service found no time. Place it yourself below, or search again."}</p>
        : <ul className={styles.offerList} aria-label="Recommended times">
          {offers.offers.map(item => {
            const left = Date.parse(item.expiresAt) - now;
            return <li key={item.offerId} className={styles.offerRow} data-offer={item.offerId}>
              <strong>{windowText({ start: item.windowStart, end: item.windowEnd }, timezone)}</strong>
              <small>{formatDate(item.date)}, {left > 0 ? `held for ${heldFor(item.expiresAt, now)}` : "hold expired"}</small>
              <button className={styles.button} type="button" disabled={busy || left <= 0} onClick={() => onBook(item.offerId)}>
                {offers.booking === item.offerId ? "Booking..." : left <= 0 ? "Expired" : "Book"}
              </button>
            </li>;
          })}
        </ul>}
      {offers.error !== null && <p className={styles.deleteError} role="alert">{offers.error}</p>}
      {again(busy)}
    </>;
  })();
  return <section className={styles.recommended} data-recommended-times={offers.status} aria-busy={offers.status === "searching"}>
    <h3 className={styles.optionsTitle}>Recommended times</h3>
    {body}
  </section>;
}

/** The place being chosen: the best options to offer, the chosen one's details, and Save. */function PlacementCard({ placement, technicians, timezone, recommended, onChoose, onAcknowledge, onAcknowledgeSameDay, onSave, onCancel, onDetails }: {
  placement: PlacementState; technicians: ScheduleTechnician[]; timezone: string;
  /** A waiting job's recommended times from the booking service, shown above the places to choose by hand. */
  recommended: ReactNode;
  onChoose: (spot: Spot, plannedStart: string | null) => void; onAcknowledge: (value: boolean) => void; onAcknowledgeSameDay: (value: boolean) => void;
  onSave: () => void; onCancel: () => void;
  onDetails: (() => void) | null;
}) {
  const moving = placement.subject.kind === "appointment";
  const header = (
    <div className={styles.placeHeader}>
      <div className={styles.placeTitle}>
        <span className={styles.placeEyebrow}>{moving ? "Moving" : "Placing"}</span>
        <h2>{placement.subject.label}</h2>
      </div>
      <div className={styles.placeHeaderActions}>
        {onDetails !== null && <button className={styles.button} type="button" onClick={onDetails}>Details</button>}
        <button className={styles.button} type="button" onClick={onCancel}>Cancel</button>
      </div>
    </div>
  );
  const title = <h3 className={styles.optionsTitle}>{moving ? "Best places" : "Or place it yourself"}</h3>;
  if (placement.status === "loading") return (
    <section className={styles.placementCard} aria-busy="true">{header}{recommended}{title}
      <p role="status" className={styles.subtle}>Finding the best times this week...</p>
      <ul className={styles.optionList} aria-hidden="true">{[0, 1, 2, 3].map(index => <li key={index} className={styles.optionSkeleton} />)}</ul>
    </section>
  );
  if (placement.status === "error") return <section className={styles.placementCard}>{header}{recommended}{title}<p role="alert">{placement.message}</p></section>;
  const { preview, chosen } = placement;
  const decided = currentVerdict(placement);
  // Today's route is already out: its places come after every later day's, and saving one needs the dispatcher's word.
  const today = todayInTz(timezone);
  const options = bestOptions(preview.answer, preview.original, 4, today);
  const onToday = chosen !== null && chosen.spot.serviceDate === today;
  const name = (id: string) => technicians.find(item => item.id === id)?.name ?? id;
  const found = chosen === null ? undefined : findGap(preview.answer, chosen.spot);
  const chosenStart = chosen === null || chosen.plannedStart === null || found === undefined ? undefined : findStart(found.gap, chosen.plannedStart);
  const isChosen = (option: PlaceOption) => chosen !== null && chosen.plannedStart !== null && option.key === spotKey(chosen.spot)
    && Date.parse(option.start.plannedStart) === Date.parse(chosen.plannedStart);
  // A place picked on the calendar that is not among the options is listed above them, so the choice is always in view.
  const extra: PlaceOption | null = chosen === null || found === undefined || chosenStart === undefined || found.gap.addedBufferedMinutes === undefined
    || options.some(isChosen) ? null : { spot: chosen.spot, key: spotKey(chosen.spot), start: chosenStart, addedBufferedMinutes: found.gap.addedBufferedMinutes,
      samePlace: decided?.kind === "KEEPS_PROMISE" && decided.samePlace };
  const starts: InsertionStart[] = found?.gap.starts ?? [];
  const legs = found === undefined ? null : legText(found.gap);
  const savable = decided !== null && decided.kind !== "CANNOT_HOLD" && !(decided.kind === "KEEPS_PROMISE" && decided.samePlace)
    && (decided.kind !== "CHANGES_PROMISE" || placement.acknowledged) && (!onToday || placement.sameDayAcknowledged) && !placement.saving;
  const row = (option: PlaceOption, best: boolean, pick: boolean) => <OptionRow key={`${option.key}|${option.start.plannedStart}`} option={option}
    moving={moving} best={best} pick={pick} on={isChosen(option)} name={name} timezone={timezone} onChoose={onChoose} />;
  const compared = decided !== null && (decided.kind === "CHANGES_PROMISE" || (decided.kind === "KEEPS_PROMISE" && !decided.samePlace)) ? decided : null;
  return (
    <section className={styles.placementCard} data-placement-state={decided?.kind ?? "READY"} aria-live="polite">
      {header}
      {preview.original !== null && <p className={styles.placePromise}>Promised {formatDate(preview.original.serviceDate)}, {windowText(preview.original.window, timezone)}</p>}
      {placement.notice !== null && <p className={styles.subtle} role="status" data-placement-notice="true">{placement.notice}</p>}
      {placement.timingError !== null && <p className={styles.subtle} role="status">
        This day&apos;s visits show their booked times: {placement.timingError}</p>}
      {recommended}
      {title}
      {options.length === 0 && extra === null
        ? <p className={styles.subtle}>Nothing fits this week without breaking an arrival window, a shift or the paid limit. Try another week.</p>
        : <ul className={styles.optionList} aria-label="Places to choose">
          {extra !== null && row(extra, false, true)}
          {options.map((option, index) => row(option, index === 0, false))}
        </ul>}
      {moving && options.length > 0 && options.every(option => option.start.keepsWindow === false)
        && <p className={styles.subtle}>No place this week keeps the promised window.</p>}
      {chosen === null ? <p className={styles.subtle}>Pick a row, or drag onto a technician.</p> : <div className={styles.choice}>
        {decided?.kind === "CANNOT_HOLD" && <p className={styles.placementProblem} role="alert">{decided.reason} It can be dropped here, but not saved.</p>}
        {legs !== null && <p className={styles.subtle}>{legs}</p>}
        {starts.length > 1 && <fieldset className={styles.startChips}>
          <legend>Start</legend>
          {starts.map(start => (
            <label key={start.plannedStart} className={chosen.plannedStart === start.plannedStart ? styles.startChipChosen : styles.startChip}>
              <input type="radio" name="placement-start" checked={chosen.plannedStart === start.plannedStart}
                onChange={() => onChoose(chosen.spot, start.plannedStart)} />
              {formatTime(start.plannedStart, timezone)}
            </label>
          ))}
        </fieldset>}
        {decided?.kind === "KEEPS_PROMISE" && decided.samePlace && <p role="status" className={styles.subtle}>This is where the appointment is now.</p>}
        {onToday && decided !== null && decided.kind !== "CANNOT_HOLD" && <div className={styles.policyViolation} role="alert" data-same-day-warning>
          <strong>This is today.</strong> The route is already out: contact the technician before saving.
          <label className={styles.acknowledge}>
            <input type="checkbox" checked={placement.sameDayAcknowledged} onChange={event => onAcknowledgeSameDay(event.target.checked)} />
            I will contact the technician about this change to today&apos;s route.
          </label>
        </div>}
        {decided?.kind === "CHANGES_PROMISE" && <div className={styles.policyViolation} role="alert">
          <strong>Dealership policy violation.</strong> This move changes what the customer was promised, from{" "}
          {formatDate(decided.previous.serviceDate)}, {windowText(decided.previous.window, timezone)} to{" "}
          {formatDate(chosen.spot.serviceDate)}, {windowText(decided.start.window, timezone)}. Contact the customer before saving.
          <label className={styles.acknowledge}>
            <input type="checkbox" checked={placement.acknowledged} onChange={event => onAcknowledge(event.target.checked)} />
            I acknowledge this breaks the dealership&apos;s policy on promised arrival windows.
          </label>
        </div>}
        {compared !== null && <details className={styles.compare}>
          <summary>Compare with where it is now</summary>
          <Comparison verdict={compared} />
        </details>}
        <div className={styles.panelActions}>
          <button className={styles.primaryButton} type="button" disabled={!savable} onClick={onSave}>{placement.saving ? "Saving..." : "Save"}</button>
        </div>
        {placement.saveError !== null && <p className={styles.deleteError} role="alert">{placement.saveError}</p>}
      </div>}
    </section>
  );
}

/**
 * The calendar's Unassigned column: the place being chosen, the New work order button, and the jobs waiting for the calendar, each
 * of which can be dragged onto a route. It folds to a narrow strip while nothing is being placed.
 */
export default function PlacementPanel({ jobs, waitingCount, startFolded, services, placement, technicians, timezone, onPlaceJob, onPressJob,
  clickSuppressed, onNewWorkOrder, onChoose, onAcknowledge, onAcknowledgeSameDay, onSave, onCancel, onDetails, placingJob, onWorkOrderSaved,
  offers, offersNow, onBookOffer, onSearchOffers }: {
  /** The waiting jobs the filters show, and how many are waiting in all. */
  jobs: ScheduleJob[]; waitingCount: number;
  /** Folded when the page opens, where the calendar has little room (beside the map). */
  startFolded: boolean;
  services: ScheduleService[]; placement: PlacementState | null; technicians: ScheduleTechnician[]; timezone: string;
  onPlaceJob: (subject: PlacementSubject) => void; onPressJob: (job: ScheduleJob, event: ReactPointerEvent) => void; clickSuppressed: () => boolean;
  /** Opens the new work order form. */
  onNewWorkOrder: () => void;
  onChoose: (spot: Spot, plannedStart: string | null) => void; onAcknowledge: (value: boolean) => void; onAcknowledgeSameDay: (value: boolean) => void;
  onSave: () => void; onCancel: () => void;
  /** Opens the moved appointment's details; null while a job is being placed. */
  onDetails: (() => void) | null;
  /** The waiting job being placed, whose work order can be edited here, codes and time included. */
  placingJob: ScheduleJob | null;
  onWorkOrderSaved: () => void;
  /** The booking service's recommended times for the job being placed, the clock their holds count down on, and its actions. */
  offers: JobOffers | null; offersNow: number; onBookOffer: (offerId: string) => void; onSearchOffers: (jobId: string) => void;
}) {
  const [folded, setFolded] = useState(startFolded);
  // Something being placed always keeps the column open, so its places and Save stay in reach.
  const open = !folded || placement !== null;
  return (
    <aside className={`${styles.placementPanel} ${open ? "" : styles.placementFolded}`} aria-label="Place a job">
      <div className={styles.unassignedHead} data-unassigned-head>
        <h2><span className={styles.unassignedLabel} data-unassigned-label>Unassigned</span> <span className={styles.unassignedCount} data-unassigned-count>{waitingCount}</span></h2>
        {placement === null && <button type="button" className={styles.foldButton} aria-expanded={open}
          aria-label={open ? "Collapse unassigned" : "Expand unassigned"} title={open ? "Collapse unassigned" : "Expand unassigned"}
          onClick={() => setFolded(!folded)}>
          {open ? <ChevronsLeft size={18} aria-hidden /> : <ChevronsRight size={18} aria-hidden />}
        </button>}
      </div>
      {open && <>
        {placement !== null && <PlacementCard placement={placement} technicians={technicians} timezone={timezone}
          recommended={placement.subject.kind === "job" && offers !== null && offers.jobId === placement.subject.id
            ? <RecommendedTimes offers={offers} now={offersNow} timezone={timezone} onBook={onBookOffer} onSearch={() => onSearchOffers(offers.jobId)} /> : null}
          onChoose={onChoose} onAcknowledge={onAcknowledge} onAcknowledgeSameDay={onAcknowledgeSameDay} onSave={onSave} onCancel={onCancel} onDetails={onDetails} />}
        {placingJob !== null && <WorkOrderPanel key={placingJob.id} jobId={placingJob.id} workOrder={placingJob.workOrder} codeOptions={services}
          onSaved={onWorkOrderSaved} />}
        <section data-unassigned-column>
          <button className={styles.button} type="button" onClick={onNewWorkOrder}>New work order</button>
          <JobList jobs={jobs} waitingCount={waitingCount} active={placement?.subject.kind === "job" ? placement.subject.id : null}
            clickSuppressed={clickSuppressed} onPress={onPressJob}
            onPlace={job => onPlaceJob({ kind: "job", id: job.id, label: `${job.customerName}, ${job.serviceName}` })} />
        </section>
      </>}
    </aside>
  );
}
