"use client";

import Link from "next/link";
import { errorMessage } from "@/lib/contracts";
import { useRouter } from "next/navigation";
import { useEffect, useMemo, useState, type CSSProperties } from "react";
import styles from "./schedule.module.css";
import { addCalendarDays, weekDates } from "@/lib/date";
import type { ScheduleAbsence, ScheduleAppointment, ScheduleTechnician } from "./types";

const DAY_FORMAT = new Intl.DateTimeFormat(undefined, { weekday: "short" });
const DATE_FORMAT = new Intl.DateTimeFormat(undefined, { month: "short", day: "numeric" });
const TIMELINE_HOUR_HEIGHT_PX = 64;

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

function isWeekend(dateKey: string): boolean {
  const day = localDate(dateKey).getDay();
  return day === 0 || day === 6;
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

function AppointmentCard({
  appointment,
  timezone,
  onSelect,
  timelineStyle,
}: {
  appointment: ScheduleAppointment;
  timezone: string;
  onSelect: (appointment: ScheduleAppointment) => void;
  timelineStyle?: CSSProperties;
}) {
  return (
    <button
      className={`${styles.card} ${timelineStyle ? styles.timelineCard : ""}`}
      style={timelineStyle}
      type="button"
      onClick={() => onSelect(appointment)}
    >
      <span className={styles.cardTime}>
        {formatTime(appointment.plannedStart, timezone)}
        {timelineStyle && ` - ${formatTime(appointment.plannedEnd, timezone)}`}
      </span>
      <span className={styles.cardCustomer}>{appointment.customerName}</span>
      <span className={styles.cardService}>{appointment.serviceName}</span>
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
}: {
  day: string;
  technician: ScheduleTechnician;
  entries: ScheduleAppointment[];
  absences: ScheduleAbsence[];
  timelineStart: number;
  timelineEnd: number;
  timezone: string;
  onSelect: (appointment: ScheduleAppointment) => void;
}) {
  const closed = isWeekend(day);
  const timelineHeight = ((timelineEnd - timelineStart) / 60) * TIMELINE_HOUR_HEIGHT_PX;
  const shiftStart = Math.max(technician.shiftStartMin, timelineStart);
  const shiftEnd = Math.min(technician.shiftEndMin, timelineEnd);
  const shiftTop = ((shiftStart - timelineStart) / 60) * TIMELINE_HOUR_HEIGHT_PX;
  const shiftHeight = Math.max(0, ((shiftEnd - shiftStart) / 60) * TIMELINE_HOUR_HEIGHT_PX);
  const hourCount = (timelineEnd - timelineStart) / 60;

  return (
    <div className={`${styles.cell} ${closed ? styles.closedCell : ""}`} style={{ height: timelineHeight }}>
      {!closed && (
        <div
          className={styles.shiftBand}
          style={{ top: shiftTop, height: shiftHeight }}
          aria-label={`${technician.name} shift ${formatMinuteOfDay(technician.shiftStartMin)} to ${formatMinuteOfDay(
            technician.shiftEndMin
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
        const visibleStart = Math.max(plannedStart, timelineStart);
        const visibleEnd = Math.min(plannedEnd, timelineEnd);
        const top = ((visibleStart - timelineStart) / 60) * TIMELINE_HOUR_HEIGHT_PX + 3;
        const height = Math.max(40, ((visibleEnd - visibleStart) / 60) * TIMELINE_HOUR_HEIGHT_PX - 6);
        return (
          <AppointmentCard
            key={appointment.id}
            appointment={appointment}
            timezone={timezone}
            onSelect={onSelect}
            timelineStyle={{ top, height }}
          />
        );
      })}
    </div>
  );
}

function AppointmentPanel({
  appointment,
  timezone,
  onClose,
  onDeleted,
}: {
  appointment: ScheduleAppointment;
  timezone: string;
  onClose: () => void;
  onDeleted: () => void;
}) {
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
        <div className={styles.panelActions}>
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
  monday,
  timezone,
  technicians,
  appointments,
  absences,
}: {
  monday: string;
  timezone: string;
  technicians: ScheduleTechnician[];
  appointments: ScheduleAppointment[];
  absences: ScheduleAbsence[];
}) {
  const router = useRouter();
  const [selected, setSelected] = useState<ScheduleAppointment | null>(null);
  const [technicianFilter, setTechnicianFilter] = useState("all");
  const days = weekDates(monday);
  const visibleTechnicians = technicians.filter(
    (technician) => technicianFilter === "all" || technician.id === technicianFilter
  );
  const timelineStart = technicians.length
    ? Math.floor(Math.min(...technicians.map((technician) => technician.shiftStartMin)) / 60) * 60
    : 8 * 60;
  const timelineEnd = technicians.length
    ? Math.ceil(Math.max(...technicians.map((technician) => technician.shiftEndMin)) / 60) * 60
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

  function goToWeek(nextMonday: string) {
    router.push(`/schedule?week=${nextMonday}`);
  }

  return (
    <main className={styles.wrap}>
      <div className={styles.toolbar}>
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

      {technicians.length === 0 ? (
        <div className={styles.empty}>No active technicians are configured.</div>
      ) : (
        <>
          <div className={styles.legend} aria-label="Schedule legend">
            <span>
              <i className={styles.openSwatch} />Unbooked shift time
            </span>
            <span>
              <i className={styles.scheduledSwatch} />Scheduled visit
            </span>
            <span>
              <i className={styles.closedSwatch} />Off shift or no service
            </span>
            <span><i className={styles.absenceSwatch} />Approved time off splits the working shift</span>
          </div>
          <div className={styles.grid} aria-label="Weekly technician schedule">
            <div className={styles.corner}>Technician</div>
            {days.map((day) => (
              <div className={`${styles.dayHeader} ${isWeekend(day) ? styles.closedDayHeader : ""}`} key={day}>
                <span className={styles.dayName}>{DAY_FORMAT.format(localDate(day))}</span>
                <span className={styles.dayDate}>{DATE_FORMAT.format(localDate(day))}</span>
                {isWeekend(day) && <span className={styles.dayStatus}>No service</span>}
              </div>
            ))}
            {visibleTechnicians.flatMap((technician) =>
              [
                <div
                  className={styles.techHeader}
                  style={{ height: timelineHours.length * TIMELINE_HOUR_HEIGHT_PX }}
                  key={`${technician.id}-header`}
                >
                  <div className={styles.techIdentity}>
                    <span className={styles.techName}>{technician.name}</span>
                    <span className={styles.techShift}>
                      {formatMinuteOfDay(technician.shiftStartMin)} - {formatMinuteOfDay(technician.shiftEndMin)}
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
                    />
                  );
                }),
              ]
            )}
          </div>
          <div className={styles.agenda} aria-label="Weekly schedule agenda">
            {days.map((day) => {
              const entries = visibleTechnicians.flatMap(
                (technician) => byCell.get(`${technician.id}:${day}`) ?? []
              );
              const dayAbsences = absences.filter((absence) => absence.date === day && visibleTechnicians.some((tech) => tech.id === absence.technicianId));
              return (
                <section className={styles.agendaDay} key={day}>
                  <h2>{localDate(day).toLocaleDateString(undefined, { weekday: "long", month: "long", day: "numeric" })}</h2>
                  {dayAbsences.map((absence) => <div key={`${absence.technicianId}-${absence.startMin}`} className={styles.subtle}>
                    {technicians.find((tech) => tech.id === absence.technicianId)?.name}: approved time off {formatMinuteOfDay(absence.startMin)} to {formatMinuteOfDay(absence.endMin)}. Working intervals are split around this block.
                  </div>)}
                  {entries.length === 0 ? (
                    <div className={styles.subtle}>{isWeekend(day) ? "No service." : "No visits scheduled."}</div>
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
      {selected && (
        <AppointmentPanel
          appointment={selected}
          timezone={timezone}
          onClose={() => setSelected(null)}
          onDeleted={() => {
            setSelected(null);
            router.refresh();
          }}
        />
      )}
    </main>
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
