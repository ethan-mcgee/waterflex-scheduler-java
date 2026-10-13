import styles from "@/app/dispatch/dispatch.module.css";
import type { BoardAppointment } from "@/app/dispatch/types";
import { clock, clockRange } from "@/app/dispatch/format";

/** Where a visit sits in the plan the map shows: its route, place and planned times. */
export type StopPlacement = { technicianName: string; technicianColor: string; stopNumber: number; stopCount: number; plannedStart: string; plannedEnd: string;
  previous: { customerName: string; plannedEnd: string } | null; plan: "current" | "proposal" };

const PRECISION: Record<string, string> = { ROOFTOP: "Rooftop match", RANGE_INTERPOLATED: "Street range match", GEOMETRIC_CENTER: "Area center",
  APPROXIMATE: "Approximate", SYNTHETIC: "Generated test location", MANUALLY_CONFIRMED: "Pin confirmed" };
const STATUS: Record<BoardAppointment["jobStatus"], string> = { PENDING: "Pending", SCHEDULED: "Scheduled", COMPLETED: "Completed", CANCELLED: "Cancelled" };

function pin(appointment: BoardAppointment): { label: string; tone: "good" | "info" | "warn" | "bad" } {
  if (appointment.lat === null || appointment.lng === null) return { label: "No location", tone: "bad" };
  if (appointment.pinConfirmedAt !== null) return { label: "Pin confirmed", tone: "good" };
  if (appointment.geocodePrecision === null) return { label: "Location source not recorded", tone: "warn" };
  const label = PRECISION[appointment.geocodePrecision] ?? appointment.geocodePrecision.toLowerCase().replaceAll("_", " ");
  return { label, tone: appointment.geocodePrecision === "ROOFTOP" || appointment.geocodePrecision === "MANUALLY_CONFIRMED" ? "good" : "warn" };
}

/** Hours around the window and the planned visit, for the small timeline. */
function timeline(appointment: BoardAppointment, placement: StopPlacement) {
  const instants = [appointment.windowStart, appointment.windowEnd, placement.plannedStart, placement.plannedEnd].map(value => Date.parse(value));
  const hour = 3600000;
  const from = Math.floor(Math.min(...instants) / hour) * hour, to = Math.ceil(Math.max(...instants) / hour) * hour;
  const span = Math.max(hour, to - from);
  const at = (value: number) => `${((value - from) / span) * 100}%`;
  const width = (start: number, end: number) => `${((end - start) / span) * 100}%`;
  const ticks = Array.from({ length: Math.round(span / hour) + 1 }, (_, index) => from + index * hour);
  return { at, width, ticks, window: [Date.parse(appointment.windowStart), Date.parse(appointment.windowEnd)] as const,
    planned: [Date.parse(placement.plannedStart), Date.parse(placement.plannedEnd)] as const };
}

/** One visit on the map: its route place, promise window against the plan, address pin and job status. */
export default function StopDetail({ appointment, placement, timezone, onClose }: { appointment: BoardAppointment; placement: StopPlacement; timezone: string; onClose: () => void }) {
  const location = pin(appointment);
  const line = timeline(appointment, placement);
  const hourLabel = (value: number) => new Intl.DateTimeFormat("en-US", { timeZone: timezone, hour: "numeric" }).format(new Date(value));
  return <aside className={styles.stopDetail} aria-labelledby="stop-detail-heading">
    <div className={styles.stopDetailHeader}>
      <div className={styles.stack}>
        <span className={styles.eyebrow}>Stop {placement.stopNumber} of {placement.stopCount} · {placement.technicianName}{placement.plan === "proposal" ? " · Optimized plan" : ""}</span>
        <h2 id="stop-detail-heading" className={styles.stopDetailTitle}>{appointment.customerName}</h2>
        <span className={styles.inkSoft}>{appointment.serviceName} · {appointment.durationMin} min</span>
      </div>
      <button type="button" aria-label="Close stop details" className={styles.iconButton} onClick={onClose}>
        <svg aria-hidden="true" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="M18 6 6 18M6 6l12 12" /></svg>
      </button>
    </div>
    <div className={styles.windowBox}>
      <span className={styles.strong}>Arrival vs. promise</span>
      <div role="img" className={styles.windowTrack}
        aria-label={`Customer window ${clockRange(appointment.windowStart, appointment.windowEnd, timezone)}; planned ${clockRange(placement.plannedStart, placement.plannedEnd, timezone)}`}>
        <div className={styles.windowBar} style={{ left: line.at(line.window[0]), width: line.width(line.window[0], line.window[1]) }} />
        <div className={styles.plannedBar} style={{ left: line.at(line.planned[0]), width: line.width(line.planned[0], line.planned[1]), borderColor: placement.technicianColor }} />
        <div className={styles.windowAxis} />
        {line.ticks.map((tick, index) => <span key={tick} className={styles.windowTick}
          style={{ left: line.at(tick), transform: index === 0 ? "none" : index === line.ticks.length - 1 ? "translateX(-100%)" : undefined }}>{hourLabel(tick)}</span>)}
      </div>
      <div className={styles.windowKey}><span><span className={styles.keyWindow} />Window</span><span><span className={styles.keyPlanned} style={{ borderColor: placement.technicianColor }} />Planned</span></div>
    </div>
    <dl className={styles.details}>
      <dt>Arrival window</dt><dd className={styles.strong}>{clockRange(appointment.windowStart, appointment.windowEnd, timezone)}</dd>
      <dt>Planned</dt><dd className={styles.strong}>{clockRange(placement.plannedStart, placement.plannedEnd, timezone)}</dd>
      <dt>Address</dt>
      <dd className={styles.stack}><span className={styles.strong}>{appointment.addressLine}</span><span className={styles[`pill_${location.tone}`]}>{location.label}</span></dd>
      <dt>Before this</dt>
      <dd>{placement.previous === null ? "First stop of the route" : `${placement.previous.customerName}, ends ${clock(placement.previous.plannedEnd, timezone)}`}</dd>
      <dt>Job status</dt><dd><span className={styles.pill_info}>{STATUS[appointment.jobStatus]}</span></dd>
    </dl>
  </aside>;
}
