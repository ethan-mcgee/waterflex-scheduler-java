"use client";

import Link from "next/link";
import { errorMessage } from "@/lib/contracts";
import { useRouter } from "next/navigation";
import { useCallback, useEffect, useMemo, useRef, useState, type CSSProperties, type PointerEvent as ReactPointerEvent } from "react";
import styles from "./schedule.module.css";
import { addCalendarDays, todayInTz, weekDates } from "@/lib/date";
import type { MoveCheckResult } from "@/lib/contracts";
import { moveCheckStart, recordMoveCheck, requestMoveCheck } from "@/lib/moveCheck";
import { useAppointmentDrag, type DropCell } from "./useAppointmentDrag";
import { usePlacement, type PlacementSubject } from "./usePlacement";
import PlacementPanel, { addedDriveText, currentVerdict } from "./PlacementPanel";
import { findGap, gapSpot, recommendation, relativeToBest, spotAt, spotKey, verdict, type Fit, type Spot } from "@/lib/placementRules";
import type { InsertionGap } from "@/lib/insertionContract";
import { driveLabel, type VisitDrive } from "@/lib/scheduleDrive";
import { useScheduleDrive, type DriveState } from "./useScheduleDrive";
import type { PlacementPreview, PlacementSaved } from "@/lib/contracts";
import type { ScheduleAbsence, ScheduleAppointment, ScheduleJob, ScheduleService, ScheduleServiceLegend, ScheduleTechnician, ScheduleViewMode } from "./types";

const DAY_FORMAT = new Intl.DateTimeFormat(undefined, { weekday: "short" });
const DATE_FORMAT = new Intl.DateTimeFormat(undefined, { month: "short", day: "numeric" });
const TIMELINE_HOUR_HEIGHT_PX = 64;
const DATE_KEY = /^\d{4}-\d{2}-\d{2}$/;

function localDate(dateKey: string): Date {
  const [year, month, day] = dateKey.split("-").map(Number);
  return new Date(year || 1970, (month || 1) - 1, day || 1);
}

function formatTime(value: string, timezone: string): string {
  return new Date(value).toLocaleTimeString(undefined, {
    timeZone: timezone,
    hour: "numeric",
    minute: "2-digit",
  });
}

function formatMinuteOfDay(value: number): string {
  const hour = Math.floor(value / 60);
  const minute = value % 60;
  const period = hour >= 12 ? "PM" : "AM";
  const displayHour = hour % 12 || 12;
  return minute === 0 ? `${displayHour} ${period}` : `${displayHour}:${String(minute).padStart(2, "0")} ${period}`;
}

function minuteOfDay(value: string, timezone: string): number {
  const parts = new Intl.DateTimeFormat("en-US", {
    timeZone: timezone,
    hourCycle: "h23",
    hour: "2-digit",
    minute: "2-digit",
  }).formatToParts(new Date(value));
  const get = (type: string) => Number(parts.find((part) => part.type === type)?.value ?? 0);
  return get("hour") * 60 + get("minute");
}

function scheduleDay(technician: ScheduleTechnician, dateKey: string) {
  const day = technician.days[dateKey];
  if (!day) throw new Error(`Missing technician availability for ${dateKey}`);
  if (day.available && (day.shiftStartMin == null || day.shiftEndMin == null || day.shiftStartMin >= day.shiftEndMin))
    throw new Error(`Invalid technician availability for ${dateKey}`);
  return day;
}

function formatWindow(appointment: ScheduleAppointment, timezone: string): string {
  return `${formatTime(appointment.windowStart, timezone)} - ${formatTime(
    appointment.windowEnd,
    timezone
  )}`;
}

function formatWeekLabel(monday: string): string {
  const start = localDate(monday);
  const end = localDate(addCalendarDays(monday, 6));
  return `${start.toLocaleDateString(undefined, { month: "short", day: "numeric" })} - ${end.toLocaleDateString(
    undefined,
    { month: "short", day: "numeric", year: "numeric" }
  )}`;
}

/** A drive band of a visit's block: its height on the timeline and what it says. */
interface DriveBand { px: number; minutes: number; label: string }

function AppointmentCard({
  appointment,
  timezone,
  onSelect,
  timelineStyle,
  drag,
  drive,
}: {
  appointment: ScheduleAppointment;
  timezone: string;
  onSelect: (appointment: ScheduleAppointment) => void;
  timelineStyle?: CSSProperties;
  /** Present on the timelines, where a card can be dragged to another place. */
  drag?: { press: (appointment: ScheduleAppointment, event: ReactPointerEvent) => void; clickSuppressed: () => boolean; dragging: boolean };
  /** On the day view once drive times are in: the drive drawn before (first visit of a stretch) and after the visit. */
  drive?: { before: DriveBand | null; after: DriveBand };
}) {
  const colors = appointment.serviceColor;
  const band = (part: DriveBand, edge: "before" | "after") => (
    <span className={styles.driveBand} style={{ height: part.px, background: colors.drive }} data-drive={edge} data-drive-minutes={part.minutes}>
      <span className={styles.driveLabel} style={{ color: colors.text }}>{part.label}</span>
    </span>
  );
  return (
    <button
      className={`${styles.card} ${timelineStyle ? styles.timelineCard : ""} ${drive ? styles.driveBlock : ""} ${drag?.dragging ? styles.draggingCard : ""}`}
      style={{ borderColor: colors.border, borderLeftColor: colors.color, background: colors.tint, color: colors.text, ...timelineStyle }}
      type="button"
      data-appointment-id={appointment.id}
      onPointerDown={drag ? (event) => drag.press(appointment, event) : undefined}
      onClick={() => { if (!drag?.clickSuppressed()) onSelect(appointment); }}
    >
      {drive?.before && band(drive.before, "before")}
      <span className={styles.cardBody}>
        <span className={styles.cardTime}>
          {formatTime(appointment.plannedStart, timezone)}
          {timelineStyle && ` - ${formatTime(appointment.plannedEnd, timezone)}`}
        </span>
        <span className={styles.cardCustomer}>{appointment.customerName}</span>
        <span className={styles.cardService}>{appointment.serviceName}</span>
      </span>
      {drive && band(drive.after, "after")}
    </button>
  );
}

function TimelineCell({
  day,
  technician,
  entries,
  absences,
  timelineStart,
  timelineEnd,
  timezone,
  onSelect,
  drag,
  dropState,
  overlay,
  drives,
}: {
  day: string;
  technician: ScheduleTechnician;
  entries: ScheduleAppointment[];
  absences: ScheduleAbsence[];
  timelineStart: number;
  timelineEnd: number;
  timezone: string;
  onSelect: (appointment: ScheduleAppointment) => void;
  drag: CardDrag;
  /** While a card is dragged: whether this cell can take it, and whether the pointer is over it. */
  dropState: "none" | "valid" | "over";
  /** While a job or an appointment is being placed: this day's gaps and the preview of the chosen start. */
  overlay?: CellOverlay;
  /** The drive around each visit, on the day view once it is in; a visit without one is drawn on its own. */
  drives?: Map<string, VisitDrive>;
}) {
  const daily = scheduleDay(technician, day);
  const px = (minute: number) => ((minute - timelineStart) / 60) * TIMELINE_HOUR_HEIGHT_PX;
  const closed = !daily.member || !daily.available;
  const timelineHeight = ((timelineEnd - timelineStart) / 60) * TIMELINE_HOUR_HEIGHT_PX;
  const shiftStart = Math.max(daily.shiftStartMin ?? timelineStart, timelineStart);
  const shiftEnd = Math.min(daily.shiftEndMin ?? timelineStart, timelineEnd);
  const shiftTop = ((shiftStart - timelineStart) / 60) * TIMELINE_HOUR_HEIGHT_PX;
  const shiftHeight = Math.max(0, ((shiftEnd - shiftStart) / 60) * TIMELINE_HOUR_HEIGHT_PX);
  const hourCount = (timelineEnd - timelineStart) / 60;

  return (
    <div
      className={`${styles.cell} ${closed ? styles.closedCell : ""} ${dropState === "valid" ? styles.dropTarget : ""} ${dropState === "over" ? styles.dropOver : ""}`}
      style={{ height: timelineHeight }}
      data-drop-technician={technician.id}
      data-drop-date={day}
      data-drop-start-minute={timelineStart}
      data-drop-hour-px={TIMELINE_HOUR_HEIGHT_PX}
    >
      {!daily.member && <span className={styles.subtle}>Assigned to another metro</span>}
      {!closed && (
        <div
          className={styles.shiftBand}
          style={{ top: shiftTop, height: shiftHeight }}
          aria-label={`${technician.name} shift ${formatMinuteOfDay(shiftStart)} to ${formatMinuteOfDay(
            shiftEnd
          )}`}
        />
      )}
      {Array.from({ length: hourCount + 1 }, (_, index) => (
        <span
          className={styles.hourLine}
          style={{ top: index * TIMELINE_HOUR_HEIGHT_PX }}
          aria-hidden="true"
          key={index}
        />
      ))}
      {absences.map((absence) => <div key={`${absence.date}-${absence.startMin}`} className={styles.absenceBand}
        style={{ top: ((absence.startMin - timelineStart) / 60) * TIMELINE_HOUR_HEIGHT_PX,
          height: ((absence.endMin - absence.startMin) / 60) * TIMELINE_HOUR_HEIGHT_PX }}
        aria-label={`Approved time off ${formatMinuteOfDay(absence.startMin)} to ${formatMinuteOfDay(absence.endMin)}`}>
        Time off {formatMinuteOfDay(absence.startMin)} to {formatMinuteOfDay(absence.endMin)}
      </div>)}
      {closed && entries.length === 0 && <span className={styles.closedLabel}>No service</span>}
      {entries.map((appointment) => {
        const plannedStart = minuteOfDay(appointment.plannedStart, timezone);
        const plannedEnd = minuteOfDay(appointment.plannedEnd, timezone);
        const drive = drives?.get(appointment.id);
        let timelineStyle: CSSProperties, block: { before: DriveBand | null; after: DriveBand } | undefined;
        if (drive === undefined) {
          const visibleStart = Math.max(plannedStart, timelineStart);
          const visibleEnd = Math.min(plannedEnd, timelineEnd);
          timelineStyle = { top: px(visibleStart) + 3, height: Math.max(40, px(visibleEnd) - px(visibleStart) - 6) };
        } else {
          // The block runs from the drive in (first visit of a stretch) through the visit to the end of the drive after it.
          const blockStart = Math.max(plannedStart - (drive.before?.minutes ?? 0), timelineStart);
          const blockEnd = Math.min(plannedEnd + drive.after.minutes, timelineEnd);
          const top = px(blockStart), bottom = Math.max(px(blockEnd), top);
          timelineStyle = { top, height: bottom - top };
          block = {
            before: drive.before === null ? null : { px: Math.max(0, px(plannedStart) - top), minutes: drive.before.minutes,
              label: driveLabel(drive.before.minutes, { from: drive.before.from }) },
            after: { px: Math.max(0, bottom - px(plannedEnd)), minutes: drive.after.minutes, label: driveLabel(drive.after.minutes, { to: drive.after.to }) },
          };
        }
        return (
          <AppointmentCard
            key={appointment.id}
            appointment={appointment}
            timezone={timezone}
            onSelect={onSelect}
            timelineStyle={timelineStyle}
            drive={block}
            drag={{ press: drag.press, clickSuppressed: drag.clickSuppressed,
              dragging: drag.draggingId === appointment.id || overlay?.movingId === appointment.id }}
          />
        );
      })}
      {overlay?.gaps.map(marker => {
        // Kept inside the cell, which clips its overflow, so a gap at the shift's start or end stays whole.
        const top = Math.min(Math.max(((marker.minute - timelineStart) / 60) * TIMELINE_HOUR_HEIGHT_PX, 12), timelineHeight - 12);
        return (
          <button key={marker.key} type="button" data-gap-key={marker.key} data-gap-fits={marker.fits ? "true" : "false"}
            className={`${styles.gapMarker} ${marker.fits ? "" : styles.gapMarkerMuted} ${marker.best ? styles.gapMarkerBest : ""} ${marker.chosen ? styles.gapMarkerChosen : ""} ${marker.hovered ? styles.gapMarkerHover : ""}`}
            style={{ top }} onClick={() => overlay.onChoose(marker.spot, marker.firstStart)}
            data-gap-best={marker.best ? "true" : undefined} aria-label={`${marker.label}, ${marker.added}, at ${technician.name}'s ${marker.position}`}>
            {marker.label}
          </button>
        );
      })}
      {overlay?.ghost != null && (() => {
        const ghost = overlay.ghost;
        const kinds = ghost.hover ? { "data-hover-ghost": ghost.kind } : { "data-placement-ghost": ghost.kind };
        const classes = `${styles.placementGhost} ${ghost.hover ? styles.hoverGhost : ""} ${ghost.ok ? "" : styles.placementGhostProblem}`;
        const detail = ghost.detail === null ? null : <span className={styles.ghostDetail}>{ghost.detail}</span>;
        if (ghost.toJob === null || ghost.fromJob === null) return (
          <div className={classes} {...kinds}
            style={{ top: px(Math.max(ghost.startMinute, timelineStart)) + 3, height: Math.max(40, (ghost.durationMinutes / 60) * TIMELINE_HOUR_HEIGHT_PX - 6) }}>
            {detail}{ghost.label}
          </div>
        );
        // With the gap's road legs, the preview is drawn like a booked visit: the drive in, the visit, the drive on.
        const top = px(Math.max(ghost.startMinute - ghost.toJob, timelineStart));
        const visitTop = px(ghost.startMinute), visitBottom = px(ghost.startMinute + ghost.durationMinutes);
        const bottom = px(Math.min(ghost.startMinute + ghost.durationMinutes + ghost.fromJob, timelineEnd));
        return (
          <div className={`${classes} ${styles.ghostBlock}`} {...kinds} style={{ top, height: Math.max(bottom - top, 0) }}>
            <span className={styles.ghostDrive} style={{ height: Math.max(0, visitTop - top) }}>
              <span className={styles.driveLabel}>{ghost.toJob} min there</span>
            </span>
            <span className={styles.ghostBody}>{detail}{ghost.label}</span>
            <span className={styles.ghostDrive} style={{ height: Math.max(0, bottom - visitBottom) }}>
              <span className={styles.driveLabel}>{ghost.fromJob} min on</span>
            </span>
          </div>
        );
      })()}
    </div>
  );
}

/** A gap of one technician-day shown on its timeline while something is being placed. */
interface GapMarker {
  key: string; spot: Spot; minute: number; label: string; fits: boolean; chosen: boolean; firstStart: string | null; position: string;
  /** Whether this gap is the recommendation, and the drive it adds in plain minutes for screen readers. */
  best: boolean; added: string;
  /** Whether a drop under the pointer would land in this gap. */
  hovered: boolean;
}
interface CellOverlay {
  gaps: GapMarker[];
  /**
   * The chosen start's preview, or while dragging, where a drop under the pointer would land (`hover`, with `detail`
   * saying the start and the drive). `toJob` and `fromJob` are the gap's buffered road legs, null with no road route.
   */
  ghost: { startMinute: number; durationMinutes: number; label: string; kind: string; ok: boolean; toJob: number | null; fromJob: number | null;
    hover: boolean; detail: string | null } | null;
  /** The appointment being moved, drawn faded at the place it is leaving. */
  movingId: string | null;
  onChoose: (spot: Spot, plannedStart: string | null) => void;
}

type CardDrag = { press: (appointment: ScheduleAppointment, event: ReactPointerEvent) => void; clickSuppressed: () => boolean; draggingId: string | null };

/** What a gap says against the recommendation; a gap with no road route or no start says so instead. */
function gapLabel(gap: InsertionGap, key: string, best: Fit | null): string {
  if (gap.addedBufferedMinutes === undefined) return addedDriveText(gap);
  if (gap.starts.length === 0) return "No start fits";
  if (best !== null && best.key === key) return `Best, ${addedDriveText(gap)}`;
  return best === null ? addedDriveText(gap) : relativeToBest(gap.addedBufferedMinutes, best);
}

/** Where a drop under the pointer would land, for the preview drawn while dragging. */
interface Hover {
  technicianId: string; date: string; who: string; gapKey: string | null; startMinute: number | null; durationMinutes: number;
  kind: string; ok: boolean; detail: string; toJob: number | null; fromJob: number | null;
}

/** What a drag carries: an appointment to move, or a job waiting for the calendar. */
type DragItem = { kind: "appointment"; appointment: ScheduleAppointment } | { kind: "job"; job: ScheduleJob };

/** What a technician's column header says about the day: the shift, or why there is none. */
function columnStatus(technician: ScheduleTechnician, date: string): { label: string; working: boolean } {
  const day = scheduleDay(technician, date);
  if (!day.member) return { label: "Assigned to another metro", working: false };
  if (!day.available || day.shiftStartMin == null || day.shiftEndMin == null) return { label: "Off", working: false };
  return { label: `${formatMinuteOfDay(day.shiftStartMin)} to ${formatMinuteOfDay(day.shiftEndMin)}`, working: true };
}

/**
 * One day with a column per technician and one time axis down the left, so appointments at the same time line up
 * across technicians. A technician who is off keeps a muted column, so columns do not shift from day to day.
 */
function DayGrid({
  date,
  technicians,
  byCell,
  absences,
  timelineStart,
  timelineEnd,
  timezone,
  onSelect,
  drag,
  dropState,
  overlay,
  drive,
}: {
  date: string;
  technicians: ScheduleTechnician[];
  byCell: Map<string, ScheduleAppointment[]>;
  absences: ScheduleAbsence[];
  timelineStart: number;
  timelineEnd: number;
  timezone: string;
  onSelect: (appointment: ScheduleAppointment) => void;
  drag: CardDrag;
  dropState: (technicianId: string, date: string) => "none" | "valid" | "over";
  overlay: (technicianId: string, date: string) => CellOverlay | undefined;
  drive: DriveState;
}) {
  const hours = Array.from({ length: Math.max(1, (timelineEnd - timelineStart) / 60) }, (_, index) => timelineStart + index * 60);
  const columns: CSSProperties = { gridTemplateColumns: `72px repeat(${Math.max(1, technicians.length)}, minmax(170px, 1fr))` };
  return (
    <div className={styles.dayGrid} style={columns} aria-label="Daily technician schedule" data-schedule-view="day">
      <div className={styles.dayCorner}>Time</div>
      {technicians.map((technician) => {
        const status = columnStatus(technician, date);
        return (
          <div className={`${styles.dayColumnHeader} ${status.working ? "" : styles.closedDayHeader}`}
            style={{ borderTopColor: technician.color }} key={`${technician.id}-header`} data-technician-column={technician.id}>
            <span className={styles.techName}>{technician.name}</span>
            <span className={styles.techShift}>{status.label}</span>
            {drive.status === "ready" && drive.minutes.has(technician.id) &&
              <span className={styles.techDrive} data-technician-drive={drive.minutes.get(technician.id)}>{drive.minutes.get(technician.id)} min driving</span>}
          </div>
        );
      })}
      <div className={styles.timeAxis} style={{ height: hours.length * TIMELINE_HOUR_HEIGHT_PX }} aria-hidden="true">
        {hours.map((hour, index) => (
          <span className={styles.timeLabel} style={{ top: index * TIMELINE_HOUR_HEIGHT_PX + TIMELINE_HOUR_HEIGHT_PX / 2 }} key={hour}>
            {formatMinuteOfDay(hour)}
          </span>
        ))}
      </div>
      {technicians.map((technician) => (
        <TimelineCell
          key={`${technician.id}:${date}`}
          day={date}
          technician={technician}
          entries={byCell.get(`${technician.id}:${date}`) ?? []}
          absences={absences.filter((absence) => absence.technicianId === technician.id && absence.date === date)}
          timelineStart={timelineStart}
          timelineEnd={timelineEnd}
          timezone={timezone}
          onSelect={onSelect}
          drag={drag}
          dropState={dropState(technician.id, date)}
          overlay={overlay(technician.id, date)}
          drives={drive.status === "ready" ? drive.visits : undefined}
        />
      ))}
    </div>
  );
}

function AppointmentPanel({
  appointment,
  timezone,
  onClose,
  onDeleted,
  moveTargets,
  onCheckMove,
  onMove,
}: {
  appointment: ScheduleAppointment;
  timezone: string;
  onClose: () => void;
  onDeleted: () => void;
  /** Technicians working that day who could take the appointment: the keyboard path to the same check as a drag. */
  moveTargets: Array<{ id: string; name: string }>;
  onCheckMove: (targetTechnicianId: string) => void;
  /** Starts moving the appointment on the calendar, with every place it could go. */
  onMove: () => void;
}) {
  const [moveTarget, setMoveTarget] = useState("");
  const [deleting, setDeleting] = useState(false);
  const [deleteError, setDeleteError] = useState<string | null>(null);

  useEffect(() => {
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === "Escape") onClose();
    };
    window.addEventListener("keydown", handleKeyDown);
    return () => window.removeEventListener("keydown", handleKeyDown);
  }, [onClose]);

  async function deleteAppointment() {
    const reason = window.prompt(`Reason for cancelling ${appointment.customerName}'s appointment:`);
    if (!reason?.trim()) return;

    setDeleting(true);
    setDeleteError(null);
    try {
      const response = await fetch("/api/schedule/appointments", {
        method: "DELETE",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ appointmentId: appointment.id, reason: reason.trim() }),
      });
      const result: unknown = await response.json().catch(() => undefined);
      if (!response.ok) throw new Error(errorMessage(result) || "Unable to delete appointment.");
      onDeleted();
    } catch (error) {
      setDeleteError(error instanceof Error ? error.message : "Unable to delete appointment.");
      setDeleting(false);
    }
  }

  return (
    <div className={styles.panelBackdrop} role="presentation" onMouseDown={onClose}>
      <aside
        className={styles.panel}
        role="dialog"
        aria-modal="true"
        aria-labelledby="schedule-detail-title"
        onMouseDown={(event) => event.stopPropagation()}
      >
        <div className={styles.panelHeader}>
          <h2 id="schedule-detail-title">Appointment details</h2>
          <button className={styles.close} type="button" aria-label="Close details" onClick={onClose}>
            ×
          </button>
        </div>
        <dl className={styles.detailList}>
          <dt>Customer</dt>
          <dd>{appointment.customerName}</dd>
          <dt>Service</dt>
          <dd>{appointment.serviceName}</dd>
          <dt>Technician</dt>
          <dd>{appointment.technicianName}</dd>
          <dt>Address</dt>
          <dd>{appointment.addressLine}</dd>
          <dt>Promised arrival window</dt>
          <dd>{formatWindow(appointment, timezone)}</dd>
          <dt>Planned visit</dt>
          <dd>
            {formatTime(appointment.plannedStart, timezone)} - {formatTime(appointment.plannedEnd, timezone)}
          </dd>
          <dt>Appointment ID</dt>
          <dd>{appointment.id}</dd>
        </dl>
        {moveTargets.length > 0 && (
          <form className={styles.moveCheck} onSubmit={(event) => { event.preventDefault(); if (moveTarget) onCheckMove(moveTarget); }}>
            <label>
              <span>Check a move to</span>
              <select className={styles.select} value={moveTarget} onChange={(event) => setMoveTarget(event.target.value)}>
                <option value="">Choose a technician</option>
                {moveTargets.map((target) => <option key={target.id} value={target.id}>{target.name}</option>)}
              </select>
            </label>
            <button className={styles.button} type="submit" disabled={!moveTarget}>Check move</button>
            <span className={styles.subtle}>Checking saves nothing.</span>
          </form>
        )}
        <div className={styles.panelActions}>
          <button className={styles.button} type="button" onClick={onMove}>Move on the calendar</button>
          <Link className={styles.dispatchLink} href={`/dispatch?date=${appointment.date}`}>
            View dispatch day
          </Link>
          <button className={styles.deleteButton} type="button" disabled={deleting} onClick={deleteAppointment}>
            {deleting ? "Deleting..." : "Delete appointment"}
          </button>
        </div>
        {deleteError && <p className={styles.deleteError} role="alert">{deleteError}</p>}
      </aside>
    </div>
  );
}

export default function ScheduleView({
  metroId,
  monday,
  view,
  date,
  timezone,
  technicians,
  appointments,
  absences,
  jobs,
  services,
  serviceLegend,
  placeJobId,
}: {
  metroId: string;
  monday: string;
  /** The day view (the default) or the weekly grid. */
  view: ScheduleViewMode;
  /** The day the day view shows; in the weekly view, the date the week was opened from. */
  date: string;
  timezone: string;
  technicians: ScheduleTechnician[];
  appointments: ScheduleAppointment[];
  absences: ScheduleAbsence[];
  /** Jobs waiting for the calendar, and the services a new job can be for. */
  jobs: ScheduleJob[];
  services: ScheduleService[];
  /** The services on screen and their colors. */
  serviceLegend: ScheduleServiceLegend[];
  /** A job to start placing when the page opens, from a "Place on calendar" link. */
  placeJobId: string | null;
}) {
  const router = useRouter();
  const [selected, setSelected] = useState<ScheduleAppointment | null>(null);
  const [technicianFilter, setTechnicianFilter] = useState("all");
  const days = weekDates(monday);
  // The days on screen: the day view's one day, or the week. The time axis covers only their shifts.
  const shown = view === "day" ? [date] : days;
  const visibleTechnicians = technicians.filter(
    (technician) => technicianFilter === "all" || technician.id === technicianFilter
  );
  const shiftHours = technicians.flatMap(technician => shown.flatMap(key => technician.days[key] ?? []).flatMap(day =>
    day.member && day.available && day.shiftStartMin != null && day.shiftEndMin != null ? [{ start: day.shiftStartMin, end: day.shiftEndMin }] : []));
  const timelineStart = shiftHours.length
    ? Math.floor(Math.min(...shiftHours.map(shift => shift.start)) / 60) * 60
    : 8 * 60;
  const timelineEnd = shiftHours.length
    ? Math.ceil(Math.max(...shiftHours.map(shift => shift.end)) / 60) * 60
    : 17 * 60;
  const timelineHours = Array.from(
    { length: Math.max(1, (timelineEnd - timelineStart) / 60) },
    (_, index) => timelineStart + index * 60
  );
  const byCell = useMemo(() => {
    const cells = new Map<string, ScheduleAppointment[]>();
    for (const appointment of appointments) {
      const key = `${appointment.technicianId}:${appointment.date}`;
      const entries = cells.get(key) ?? [];
      entries.push(appointment);
      cells.set(key, entries);
    }
    return cells;
  }, [appointments]);

  // The day view's drive times, asked again whenever the day's visits move.
  const driveVersion = useMemo(() => appointments.filter(item => item.date === date)
    .map(item => `${item.id}@${item.technicianId}@${item.plannedStart}@${item.plannedEnd}`).sort().join(","), [appointments, date]);
  const drive = useScheduleDrive(metroId, date, view === "day", driveVersion);

  const [check, setCheck] = useState<MoveCheck | null>(null);
  const checkCount = useRef(0);
  const inFlight = useRef<AbortController | null>(null);
  const recorded = useRef(0);

  const takes = useCallback((technicianId: string, date: string) => {
    const technician = technicians.find(item => item.id === technicianId);
    const day = technician?.days[date];
    return day !== undefined && day.member && day.available;
  }, [technicians]);

  const [saved, setSaved] = useState<string | null>(null);
  const onSaved = (result: PlacementSaved, subject: PlacementSubject) => {
    setSaved(result.kind === "NEW" ? `Saved: ${subject.label} is on the calendar.` : result.kind === "CHANGES_PROMISE"
      ? `Saved: ${subject.label} moved, and the changed promise was recorded. Let the customer know.` : `Saved: ${subject.label} moved.`);
    router.refresh();
  };
  const { placement, start: startPlacement, choose, drop, acknowledge, cancel: cancelPlacement, save } = usePlacement(metroId, monday, onSaved);
  const opened = useRef(false);

  // A "Place on calendar" link opens the page with that job being placed.
  useEffect(() => {
    if (opened.current || placeJobId === null) return;
    opened.current = true;
    const job = jobs.find(item => item.id === placeJobId);
    if (job?.located) startPlacement({ kind: "job", id: job.id, label: `${job.customerName}, ${job.serviceName}` });
  }, [jobs, placeJobId, startPlacement]);

  const subjectOf = useCallback((item: DragItem): PlacementSubject => item.kind === "job"
    ? { kind: "job", id: item.job.id, label: `${item.job.customerName}, ${item.job.serviceName}` }
    : { kind: "appointment", id: item.appointment.id, label: `${item.appointment.customerName}, ${item.appointment.serviceName}` }, []);

  /** Planned start minutes of the stops on screen, by appointment, for finding the gap under a drop. */
  const stopMinutes = useMemo(() => new Map(appointments.map(item => [item.id, minuteOfDay(item.plannedStart, timezone)])), [appointments, timezone]);

  /** Where a drop lands in an answer: the gap and the start nearest the pointer. */
  const landing = useCallback((answer: PlacementPreview["answer"], cell: DropCell) =>
    spotAt(answer, cell.technicianId, cell.date, cell.minute, id => stopMinutes.get(id), item => minuteOfDay(item.plannedStart, timezone)),
  [stopMinutes, timezone]);

  // Dragging and dropping is never refused: a place that cannot hold is explained and cannot be saved.
  const canDrop = useCallback((_item: DragItem, _cell: DropCell) => true, []);

  /** Asks whether the day still works with the move. A newer check replaces an older one still waiting. */
  const runCheck = useCallback((appointment: ScheduleAppointment, targetTechnicianId: string, started: number) => {
    inFlight.current?.abort();
    const controller = new AbortController();
    inFlight.current = controller;
    const id = ++checkCount.current;
    const targetName = technicians.find(item => item.id === targetTechnicianId)?.name ?? targetTechnicianId;
    const base = { id, appointment, targetTechnicianId, targetName, started };
    setCheck({ ...base, state: "checking" });
    requestMoveCheck({ metroId, serviceDate: appointment.date, appointmentId: appointment.id, targetTechnicianId }, controller.signal)
      .then(result => { if (!controller.signal.aborted) setCheck({ ...base, state: "done", result }); })
      .catch((error: unknown) => {
        if (controller.signal.aborted) return;
        setCheck({ ...base, state: "error", message: error instanceof Error ? error.message : "The move could not be checked. Try again." });
      });
  }, [metroId, technicians]);

  const { drag, press, clickSuppressed } = useAppointmentDrag<DragItem>({
    canDrop,
    onStart: item => { setSaved(null); startPlacement(subjectOf(item)); },
    // A drop before the answer arrives lands as soon as it does.
    onDrop: (_item, cell) => drop(preview => landing(preview.answer, cell)),
  });

  // The timing ends once the answer is on screen: two animation frames after React commits it, the frame is painted.
  useEffect(() => {
    if (check === null || check.state === "checking" || recorded.current === check.id) return;
    recorded.current = check.id;
    const detail = { outcome: check.state === "done" ? check.result.outcome : "ERROR" as const,
      appointmentId: check.appointment.id, targetTechnicianId: check.targetTechnicianId };
    let second = 0;
    const first = requestAnimationFrame(() => { second = requestAnimationFrame(() => recordMoveCheck(check.started, detail)); });
    return () => { cancelAnimationFrame(first); cancelAnimationFrame(second); };
  }, [check]);

  useEffect(() => () => inFlight.current?.abort(), []);

  const draggingId = drag === null ? null : drag.item.kind === "appointment" ? drag.item.appointment.id : null;
  const cardDrag: CardDrag = { press: (appointment, event) => press({ kind: "appointment", appointment }, event), clickSuppressed, draggingId };
  const dropState = (technicianId: string, date: string): "none" | "valid" | "over" => {
    if (drag === null) return "none";
    if (drag.over?.technicianId === technicianId && drag.over.date === date) return "over";
    return takes(technicianId, date) ? "valid" : "none";
  };

  const chosenVerdict = currentVerdict(placement);

  /**
   * Where a drop under the pointer would land: the gap and its nearest start, the drive there and on, and how it compares
   * with the recommendation, all from the answer already on the page, so moving the pointer asks for nothing. Before the
   * answer arrives the preview sits at the half hour under the pointer.
   */
  const hover: Hover | null = (() => {
    if (drag === null || drag.over === null || placement === null) return null;
    const over = drag.over;
    const who = technicians.find(item => item.id === over.technicianId)?.name ?? over.technicianId;
    const pointer = over.minute === null ? null : Math.round(over.minute / 30) * 30;
    const base = { technicianId: over.technicianId, date: over.date, who };
    if (placement.status !== "ready") {
      const item = drag.item;
      const durationMinutes = item.kind === "job" ? item.job.durationMin
        : minuteOfDay(item.appointment.plannedEnd, timezone) - minuteOfDay(item.appointment.plannedStart, timezone);
      return { ...base, gapKey: null, startMinute: pointer, durationMinutes, kind: "LOADING", ok: true,
        detail: placement.status === "error" ? "Places could not be found" : "Finding places...", toJob: null, fromJob: null };
    }
    const { answer, original, job } = placement.preview;
    const landed = landing(answer, over);
    const found = findGap(answer, landed.spot);
    const key = spotKey(landed.spot);
    const label = found === undefined ? "Cannot be placed here" : gapLabel(found.gap, key, recommendation(answer, original !== null));
    const decided = landed.plannedStart === null ? null : verdict(answer, original, landed.spot, landed.plannedStart);
    const note = decided?.kind === "CHANGES_PROMISE" ? ", changes the promised window"
      : decided?.kind === "KEEPS_PROMISE" && decided.samePlace ? ", where it is now" : "";
    return { ...base, gapKey: found === undefined ? null : key, durationMinutes: job.durationMinutes,
      startMinute: landed.plannedStart === null ? pointer : minuteOfDay(landed.plannedStart, timezone),
      kind: decided?.kind ?? "CANNOT_HOLD", ok: decided !== null && decided.kind !== "CANNOT_HOLD",
      detail: landed.plannedStart === null ? `${label} here` : `${formatTime(landed.plannedStart, timezone)}: ${label}${note}`,
      toJob: found?.gap.toJob?.bufferedMinutes ?? null, fromJob: found?.gap.fromJob?.bufferedMinutes ?? null };
  })();
  /** The gaps and the chosen start's preview for one technician-day, while something is being placed. */
  const overlay = (technicianId: string, date: string): CellOverlay | undefined => {
    if (placement === null) return undefined;
    const movingId = placement.subject.kind === "appointment" ? placement.subject.id : null;
    // While dragging, the only preview is where a drop under the pointer would land.
    const hoverGhost = hover === null || hover.technicianId !== technicianId || hover.date !== date || hover.startMinute === null ? null
      : { startMinute: hover.startMinute, durationMinutes: hover.durationMinutes, label: placement.subject.label, kind: hover.kind, ok: hover.ok,
        toJob: hover.toJob, fromJob: hover.fromJob, hover: true, detail: hover.detail };
    if (placement.status !== "ready") return { gaps: [], ghost: hoverGhost, movingId, onChoose: choose };
    const { answer, job } = placement.preview;
    const day = answer.technicianDays.find(item => item.technicianId === technicianId && item.serviceDate === date);
    const technician = technicians.find(item => item.id === technicianId);
    const shiftStart = technician?.days[date]?.shiftStartMin ?? timelineStart;
    const ends = new Map(appointments.map(item => [item.id, minuteOfDay(item.plannedEnd, timezone)]));
    const chosenKey = placement.chosen === null ? null : spotKey(placement.chosen.spot);
    const best = recommendation(answer, placement.preview.original !== null);
    const gaps: GapMarker[] = (day?.gaps ?? []).map(gap => {
      const spot = gapSpot(day ?? { technicianId, serviceDate: date, holdsAsGiven: false, qualified: false, gaps: [] }, gap);
      const key = spotKey(spot);
      const after = gap.afterAppointmentId === undefined ? undefined : ends.get(gap.afterAppointmentId);
      const before = gap.beforeAppointmentId === undefined ? undefined : appointments.find(item => item.id === gap.beforeAppointmentId);
      const fits = gap.starts.length > 0, isBest = best !== null && best.key === key;
      return { key, spot, minute: after ?? shiftStart, label: gapLabel(gap, key, best), fits, chosen: key === chosenKey, best: isBest,
        added: addedDriveText(gap), hovered: hover !== null && hover.gapKey === key,
        firstStart: gap.starts[0]?.plannedStart ?? null,
        position: before === undefined ? "end of the route" : `stop before ${before.customerName}` };
    });
    const chosen = placement.chosen;
    const here = chosen !== null && chosen.spot.technicianId === technicianId && chosen.spot.serviceDate === date;
    const legs = here ? findGap(answer, chosen.spot)?.gap : undefined;
    const ghost = drag !== null ? hoverGhost : !here || chosen.plannedStart === null ? null : { startMinute: minuteOfDay(chosen.plannedStart, timezone),
      durationMinutes: job.durationMinutes, label: placement.subject.label, kind: chosenVerdict?.kind ?? "CANNOT_HOLD",
      ok: chosenVerdict !== null && chosenVerdict.kind !== "CANNOT_HOLD",
      toJob: legs?.toJob?.bufferedMinutes ?? null, fromJob: legs?.fromJob?.bufferedMinutes ?? null, hover: false, detail: null };
    return { gaps, ghost, movingId, onChoose: choose };
  };

  /** What the drag label says about the place under the pointer: the technician, the start and how it compares. */
  const hoverText = hover === null ? "" : ` to ${hover.who}, ${hover.detail}`;

  function goToWeek(nextMonday: string) {
    router.push(`/schedule?metroId=${encodeURIComponent(metroId)}&week=${nextMonday}`);
  }

  // The day a click asked for. Navigation is asynchronous, so a second click before the page arrives counts from
  // the first click's day rather than from the one still on screen; quick clicks add up instead of repeating.
  const targetDate = useRef(date);
  useEffect(() => { targetDate.current = date; }, [date]);

  function goToDay(nextDate: string) {
    targetDate.current = nextDate;
    router.push(`/schedule?metroId=${encodeURIComponent(metroId)}&date=${nextDate}`);
  }

  const viewSwitch = (
    <nav className={styles.viewSwitch} aria-label="Schedule view">
      <Link href={`/schedule?metroId=${encodeURIComponent(metroId)}&date=${days.includes(date) ? date : monday}`}
        aria-current={view === "day" ? "page" : undefined}>Day</Link>
      <Link href={`/schedule?metroId=${encodeURIComponent(metroId)}&week=${monday}`} aria-current={view === "week" ? "page" : undefined}>Week</Link>
    </nav>
  );

  return (
    <main className={styles.wrap}>
      <div className={styles.toolbar}>
        {view === "day" ? <>
          <h1 className={styles.title}>Daily schedule</h1>
          <span className={styles.subtle} data-schedule-date={date}>
            {localDate(date).toLocaleDateString(undefined, { weekday: "long", month: "long", day: "numeric", year: "numeric" })}
          </span>
          <button className={styles.button} type="button" onClick={() => goToDay(addCalendarDays(targetDate.current, -1))}>
            Previous day
          </button>
          <button className={styles.button} type="button" onClick={() => goToDay(addCalendarDays(targetDate.current, 1))}>
            Next day
          </button>
          <button className={styles.button} type="button" onClick={() => goToDay(todayInTz(timezone))}>
            Today
          </button>
          <label>
            <span className={styles.srOnly}>Go to date</span>
            <input className={styles.select} type="date" value={date}
              onChange={(event) => { if (DATE_KEY.test(event.target.value)) goToDay(event.target.value); }} />
          </label>
        </> : <>
          <h1 className={styles.title}>Weekly schedule</h1>
          <span className={styles.subtle}>Week of {formatWeekLabel(monday)}</span>
          <button className={styles.button} type="button" onClick={() => goToWeek(addCalendarDays(monday, -7))}>
            Previous
          </button>
          <button className={styles.button} type="button" onClick={() => goToWeek(addCalendarDays(monday, 7))}>
            Next
          </button>
          <button className={styles.button} type="button" onClick={() => goToWeek(mondayOfToday(timezone))}>
            This week
          </button>
        </>}
        {viewSwitch}
        <label>
          <span className={styles.srOnly}>Filter technician</span>
          <select
            className={styles.select}
            value={technicianFilter}
            onChange={(event) => setTechnicianFilter(event.target.value)}
          >
            <option value="all">All technicians</option>
            {technicians.map((technician) => (
              <option key={technician.id} value={technician.id}>
                {technician.name}
              </option>
            ))}
          </select>
        </label>
      </div>

      {check !== null && <MoveCheckBanner check={check} onDismiss={() => { inFlight.current?.abort(); setCheck(null); }} />}
      {saved !== null && <div className={`${styles.moveBanner} ${styles.moveFeasible}`} role="status" data-placement-saved="true">
        <span>{saved}</span>
        <button className={styles.button} type="button" onClick={() => setSaved(null)}>Dismiss</button>
      </div>}

      <div className={styles.scheduleBody}>
      <div className={styles.scheduleMain}>
      {technicians.length === 0 ? (
        <div className={styles.empty}>No active technicians are configured.</div>
      ) : (
        <>
          <div className={styles.legend} aria-label="Schedule legend">
            <span>
              <i className={styles.openSwatch} />Unbooked shift time
            </span>
            {serviceLegend.length === 0
              ? <span><i className={styles.scheduledSwatch} />Scheduled visit</span>
              : serviceLegend.map(item => <span key={item.name}>
                <i className={styles.serviceSwatch} style={{ background: item.color.tint, borderColor: item.color.border, borderLeftColor: item.color.color }} />
                {item.name}
              </span>)}
            {view === "day" && <span><i className={styles.driveSwatch} />Drive, the lighter end of each visit</span>}
            <span>
              <i className={styles.closedSwatch} />Off shift or no service
            </span>
            <span><i className={styles.absenceSwatch} />Approved time off splits the working shift</span>
          </div>
          {view === "day" && <DriveNotice drive={drive} technicians={technicians} />}
          {view === "day" ? (
            <DayGrid date={date} technicians={visibleTechnicians} byCell={byCell} absences={absences} timelineStart={timelineStart}
              timelineEnd={timelineEnd} timezone={timezone} onSelect={setSelected} drag={cardDrag} dropState={dropState} overlay={overlay} drive={drive} />
          ) : (
          <div className={styles.grid} aria-label="Weekly technician schedule" data-schedule-view="week">
            <div className={styles.corner}>Technician</div>
            {days.map((day) => (
              <div className={`${styles.dayHeader} ${technicians.every(technician => !scheduleDay(technician, day).member || !scheduleDay(technician, day).available) ? styles.closedDayHeader : ""}`} key={day}>
                <span className={styles.dayName}>{DAY_FORMAT.format(localDate(day))}</span>
                <span className={styles.dayDate}>{DATE_FORMAT.format(localDate(day))}</span>
                {technicians.every(technician => !scheduleDay(technician, day).member || !scheduleDay(technician, day).available) && <span className={styles.dayStatus}>Off</span>}
              </div>
            ))}
            {visibleTechnicians.flatMap((technician) =>
              [
                <div
                  className={styles.techHeader}
                  style={{ height: timelineHours.length * TIMELINE_HOUR_HEIGHT_PX, borderLeftColor: technician.color }}
                  key={`${technician.id}-header`}
                >
                  <div className={styles.techIdentity}>
                    <span className={styles.techName}>{technician.name}</span>
                    <span className={styles.techShift}>
                      Hours shown by day
                    </span>
                  </div>
                  {timelineHours.map((hour, index) => (
                    <span
                      className={styles.timeLabel}
                      style={{ top: index * TIMELINE_HOUR_HEIGHT_PX + TIMELINE_HOUR_HEIGHT_PX / 2 }}
                      key={hour}
                    >
                      {formatMinuteOfDay(hour)}
                    </span>
                  ))}
                </div>,
                ...days.map((day) => {
                  const entries = byCell.get(`${technician.id}:${day}`) ?? [];
                  return (
                    <TimelineCell
                      key={`${technician.id}:${day}`}
                      day={day}
                      technician={technician}
                      entries={entries}
                      absences={absences.filter((absence) => absence.technicianId === technician.id && absence.date === day)}
                      timelineStart={timelineStart}
                      timelineEnd={timelineEnd}
                      timezone={timezone}
                      onSelect={setSelected}
                      drag={cardDrag}
                      dropState={dropState(technician.id, day)}
                      overlay={overlay(technician.id, day)}
                    />
                  );
                }),
              ]
            )}
          </div>
          )}
          <div className={styles.agenda} aria-label={view === "day" ? "Daily schedule agenda" : "Weekly schedule agenda"}>
            {shown.map((day) => {
              const entries = visibleTechnicians.flatMap(
                (technician) => byCell.get(`${technician.id}:${day}`) ?? []
              );
              const dayAbsences = absences.filter((absence) => absence.date === day && visibleTechnicians.some((tech) => tech.id === absence.technicianId && scheduleDay(tech, day).member));
              return (
                <section className={styles.agendaDay} key={day}>
                  <h2>{localDate(day).toLocaleDateString(undefined, { weekday: "long", month: "long", day: "numeric" })}</h2>
                  {dayAbsences.map((absence) => <div key={`${absence.technicianId}-${absence.startMin}`} className={styles.subtle}>
                    {technicians.find((tech) => tech.id === absence.technicianId)?.name}: approved time off {formatMinuteOfDay(absence.startMin)} to {formatMinuteOfDay(absence.endMin)}. Working intervals are split around this block.
                  </div>)}
                  {entries.length === 0 ? (
                    <div className={styles.subtle}>{visibleTechnicians.every(technician => !scheduleDay(technician, day).member) ? "No technicians assigned to this metro." : visibleTechnicians.every(technician => !scheduleDay(technician, day).available) ? "Off shift." : "No visits scheduled."}</div>
                  ) : (
                    entries.map((appointment) => (
                      <AppointmentCard
                        key={appointment.id}
                        appointment={appointment}
                        timezone={timezone}
                        onSelect={setSelected}
                      />
                    ))
                  )}
                </section>
              );
            })}
          </div>
        </>
      )}
      </div>
      <PlacementPanel jobs={jobs} services={services} placement={placement} technicians={technicians} timezone={timezone}
        onPlaceJob={subject => { setSaved(null); startPlacement(subject); }}
        onPressJob={(job, event) => press({ kind: "job", job }, event)} clickSuppressed={clickSuppressed}
        onCreated={(jobId, label) => { setSaved(null); startPlacement({ kind: "job", id: jobId, label }); router.refresh(); }}
        onChoose={(spot, plannedStart) => {
          choose(spot, plannedStart);
          // A fit on another day of the week opens that day in the day view.
          if (view === "day" && spot.serviceDate !== date) goToDay(spot.serviceDate);
        }}
        onAcknowledge={acknowledge} onSave={save} onCancel={cancelPlacement} />
      </div>
      {selected && (
        <AppointmentPanel
          appointment={selected}
          timezone={timezone}
          onClose={() => setSelected(null)}
          onDeleted={() => {
            setSelected(null);
            router.refresh();
          }}
          moveTargets={technicians.filter(item => item.id !== selected.technicianId && takes(item.id, selected.date))
            .map(item => ({ id: item.id, name: item.name }))}
          onCheckMove={(targetTechnicianId) => {
            const appointment = selected;
            setSelected(null);
            runCheck(appointment, targetTechnicianId, moveCheckStart());
          }}
          onMove={() => {
            const appointment = selected;
            setSelected(null);
            setSaved(null);
            startPlacement({ kind: "appointment", id: appointment.id, label: `${appointment.customerName}, ${appointment.serviceName}` });
          }}
        />
      )}
      {drag !== null && (
        <div className={styles.dragLabel} style={{ left: drag.x + 12, top: drag.y + 12 }} aria-hidden="true">
          {drag.item.kind === "job" ? drag.item.job.customerName : drag.item.appointment.customerName}
          {hoverText}
        </div>
      )}
    </main>
  );
}

/** Says when the day's drive times are still loading, could not be loaded, or are missing for a technician. */
function DriveNotice({ drive, technicians }: { drive: DriveState; technicians: ScheduleTechnician[] }) {
  if (drive.status === "loading") return <p className={styles.driveNotice} role="status">Loading drive times...</p>;
  if (drive.status === "error")
    return <p className={styles.driveNotice} role="status" data-drive-unavailable="true">Drive times are unavailable: {drive.message} Visits are shown without them.</p>;
  if (drive.status !== "ready" || drive.drive.skipped.length === 0) return null;
  return <p className={styles.driveNotice} role="status">
    {drive.drive.skipped.map(day => `Drive times for ${technicians.find(item => item.id === day.technicianId)?.name ?? day.technicianId} could not be worked out: ${day.message}`).join(" ")}
  </p>;
}

type MoveCheck = { id: number; appointment: ScheduleAppointment; targetTechnicianId: string; targetName: string; started: number }
  & ({ state: "checking" } | { state: "done"; result: MoveCheckResult } | { state: "error"; message: string });

const OUTCOME_LABEL: Record<MoveCheckResult["outcome"], string> = { FEASIBLE: "Feasible", NOT_FEASIBLE: "Not feasible", NOT_CHECKED: "Not checked" };

/** The answer to the last move check. The card stays where it was: a check never saves the move. */
function MoveCheckBanner({ check, onDismiss }: { check: MoveCheck; onDismiss: () => void }) {
  const who = `${check.appointment.customerName} on ${check.targetName}'s route`;
  const outcomeClass = check.state === "done"
    ? check.result.outcome === "FEASIBLE" ? styles.moveFeasible : styles.moveNotFeasible
    : check.state === "error" ? styles.moveNotFeasible : "";
  return (
    <div className={`${styles.moveBanner} ${outcomeClass}`} data-move-check={check.state === "done" ? check.result.outcome : check.state.toUpperCase()}
      role={check.state === "error" ? "alert" : "status"} aria-live="polite">
      <span>
        {check.state === "checking" && `Checking ${who}...`}
        {check.state === "done" && `${OUTCOME_LABEL[check.result.outcome]}: ${who}. ${check.result.message} Nothing was saved.`}
        {check.state === "error" && `Could not check ${who}: ${check.message}`}
      </span>
      <button className={styles.button} type="button" onClick={onDismiss}>Dismiss</button>
    </div>
  );
}

function mondayOfToday(timezone: string): string {
  const parts = new Intl.DateTimeFormat("en-CA", {
    timeZone: timezone,
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
  }).formatToParts(new Date());
  const get = (type: string) => Number(parts.find((part) => part.type === type)?.value ?? 1);
  const value = new Date(Date.UTC(get("year"), get("month") - 1, get("day")));
  value.setUTCDate(value.getUTCDate() - ((value.getUTCDay() + 6) % 7));
  return value.toISOString().slice(0, 10);
}
