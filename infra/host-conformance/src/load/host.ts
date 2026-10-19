// A small stand-in for the host's records, so load runs behave as WaterFlex Software would: every snapshot is built
// from the current records, every receipt is written with a compare-and-set on its technician-day timestamps, and a
// written day gets a new lastModified. JavaScript runs one callback at a time, so each method is atomic.
import type { CommitReceipt } from "../api.ts";
import type { Point } from "../points.ts";
import type { Appointment, Location, Snapshot, TechnicianDay, Window } from "../snapshot.ts";
import { localInstant } from "../time.ts";

export interface MetroShape {
  readonly metroId: string;
  readonly timeZone: string;
  readonly serviceId: string;
  /** Technician IDs are `${prefix}-tech-<n>`. */
  readonly prefix: string;
  readonly technicians: number;
  /** Visits already booked on each technician-day, from 09:00 two hours apart. */
  readonly appointmentsPerDay: number;
  /**
   * Dates booked out before the run: each technician-day there has `bookedAppointments` visits instead, each held to the
   * hour it starts, so a booking added there cannot re-time the day earlier and leave it reading less full.
   */
  readonly bookedDates?: ReadonlySet<string>;
  readonly bookedAppointments?: number;
  readonly dates: readonly string[];
  readonly rates: Snapshot["rates"];
  readonly policy: Snapshot["policy"];
}

export interface Job { readonly id: string; readonly serviceId: string; readonly durationMinutes: number; readonly location: Point }

export type WriteOutcome = "WRITTEN" | "CONFLICT";

const SHIFT_START = 480, SHIFT_END = 1020, FIRST_VISIT = 540, SPACING = 120, VISIT_MINUTES = 45;

const key = (technicianId: string, serviceDate: string) => `${technicianId}|${serviceDate}`;

function required<T>(value: T | undefined, what: string): T {
  if (value === undefined) throw new Error(`Missing ${what}`);
  return value;
}

export class HostStore {
  readonly #days = new Map<string, TechnicianDay>();
  readonly #appointments = new Map<string, Appointment>();
  readonly #technicianIds: string[] = [];
  #clock: number;
  /** Receipts whose listed days dropped an appointment without placing it elsewhere: a contract breach to report. */
  anomalies: string[] = [];

  constructor(readonly shape: MetroShape, home: () => Point, visit: () => Point, now: number = Date.now()) {
    this.#clock = now;
    const at = (date: string, minute: number) => localInstant(date, minute, shape.timeZone);
    for (let n = 1; n <= shape.technicians; n++) {
      const technicianId = `${shape.prefix}-tech-${n}`;
      this.#technicianIds.push(technicianId);
      const start: Location = home();
      for (const date of shape.dates) {
        this.#days.set(key(technicianId, date), { technicianId, serviceDate: date, lastModified: this.#stamp(),
          shift: { start: at(date, SHIFT_START), end: at(date, SHIFT_END) }, absences: [], start, end: start, maxPaidMinutes: 540 });
        const booked = shape.bookedDates?.has(date) === true;
        const visits = booked ? required(shape.bookedAppointments, "bookedAppointments with bookedDates") : shape.appointmentsPerDay;
        for (let sequence = 0; sequence < visits; sequence++) {
          const planned = FIRST_VISIT + sequence * SPACING;
          if (planned + VISIT_MINUTES > SHIFT_END) throw new Error("Too many appointments per day for one shift");
          const id = `${shape.prefix}-appt-${n}-${date}-${sequence}`;
          this.#appointments.set(id, { id, technicianId, serviceDate: date, serviceId: shape.serviceId, durationMinutes: VISIT_MINUTES,
            window: booked ? { start: at(date, planned), end: at(date, planned + 60) }
              : { start: at(date, Math.max(SHIFT_START, planned - 60)), end: at(date, Math.min(SHIFT_END, planned + 180)) },
            location: visit(), sequence, plannedStart: at(date, planned) });
        }
      }
    }
  }

  /** A new lastModified, unique and increasing, as a host database would stamp a write. */
  #stamp(): string {
    this.#clock += 1;
    return new Date(this.#clock).toISOString();
  }

  get appointmentCount(): number {
    return this.#appointments.size;
  }

  /** The current records of the given dates, as the host would send them. */
  snapshot(dates: readonly string[]): Snapshot {
    const wanted = new Set(dates);
    const days = [...this.#days.values()].filter(day => wanted.has(day.serviceDate));
    const covered = new Set(days.map(day => key(day.technicianId, day.serviceDate)));
    return {
      metroId: this.shape.metroId, timeZone: this.shape.timeZone, rates: { ...this.shape.rates }, policy: { ...this.shape.policy },
      technicians: this.#technicianIds.map(id => ({ id, qualifications: [this.shape.serviceId] })),
      technicianDays: days.map(day => structuredClone(day)),
      appointments: [...this.#appointments.values()].filter(item => covered.has(key(item.technicianId, item.serviceDate)))
        .sort((a, b) => a.technicianId.localeCompare(b.technicianId) || a.serviceDate.localeCompare(b.serviceDate) || a.sequence - b.sequence)
        .map(item => structuredClone(item)),
    };
  }

  /**
   * Writes a receipt with a compare-and-set: when every listed technician-day still has the receipt's timestamp, every
   * listed assignment is written (a new job's appointment created from `job` and its offered window) and each written
   * day gets a new timestamp; otherwise nothing is written.
   */
  write(receipt: CommitReceipt, job?: { job: Job; window: Window }): WriteOutcome {
    for (const version of receipt.technicianDays) {
      const day = this.#days.get(key(version.technicianId, version.serviceDate));
      if (day === undefined || Date.parse(day.lastModified) !== Date.parse(version.lastModified)) return "CONFLICT";
    }
    for (const assignment of receipt.assignments)
      if (!this.#days.has(key(assignment.technicianId, assignment.serviceDate)))
        throw new Error(`Receipt assigns ${assignment.appointmentId} to an unknown technician-day`);
    const listed = new Set(receipt.technicianDays.map(version => key(version.technicianId, version.serviceDate)));
    const assigned = new Set(receipt.assignments.map(assignment => assignment.appointmentId));
    for (const appointment of this.#appointments.values())
      if (listed.has(key(appointment.technicianId, appointment.serviceDate)) && !assigned.has(appointment.id))
        this.anomalies.push(`Receipt ${receipt.receiptId} drops ${appointment.id} from a listed day`);
    const touched = new Set(listed);
    for (const assignment of receipt.assignments) {
      const existing = this.#appointments.get(assignment.appointmentId);
      let base: Appointment;
      if (existing !== undefined) {
        const from = key(existing.technicianId, existing.serviceDate);
        if (!listed.has(from)) this.anomalies.push(`Receipt ${receipt.receiptId} moves ${existing.id} off a day it does not list`);
        touched.add(from);
        base = existing;
      } else if (job !== undefined && assignment.appointmentId === job.job.id) {
        base = { id: job.job.id, technicianId: assignment.technicianId, serviceDate: assignment.serviceDate, serviceId: job.job.serviceId,
          durationMinutes: job.job.durationMinutes, window: job.window, location: job.job.location, sequence: assignment.sequence, plannedStart: assignment.plannedStart };
      } else {
        throw new Error(`Receipt ${receipt.receiptId} assigns unknown appointment ${assignment.appointmentId}`);
      }
      this.#appointments.set(base.id, { ...base, technicianId: assignment.technicianId, serviceDate: assignment.serviceDate,
        sequence: assignment.sequence, plannedStart: assignment.plannedStart });
    }
    for (const touchedKey of touched) {
      const day = this.#days.get(touchedKey);
      if (day !== undefined) day.lastModified = this.#stamp();
    }
    return "WRITTEN";
  }
}
