// Synthetic but complete snapshots, built the way a host builds them: every required field present, coordinates on
// every location the kit means to be located, sequence and plannedStart in the same order, and one lastModified per
// technician-day. The rates and policy are the kit's own test values, sent like a client's settings.
import { localInstant } from "./time.ts";
import type { Point } from "./points.ts";

export interface Address { line1: string; city: string; state: string; postalCode: string }
export type Location = Point | { address: Address };
export interface Window { start: string; end: string }

export interface TechnicianDay {
  technicianId: string; serviceDate: string; lastModified: string; shift: Window; absences: Window[];
  start: Location; end: Location; maxPaidMinutes: number;
}

export interface Appointment {
  id: string; technicianId: string; serviceDate: string; serviceId: string; durationMinutes: number; window: Window;
  location: Location; sequence: number; plannedStart: string;
}

export interface Snapshot {
  metroId: string; timeZone: string;
  rates: { regularHourly: string; overtimeHourly: string; mileagePerMile: string; travelBufferPct: string; travelBufferMinutes: number };
  policy: { fairnessBudget: string; dailySearch: "TABU" | "TABU_KOPT"; costSearchSeconds: number; fairnessSearchSeconds: number;
    bookingDeadlineMs: number; regularWindowThreshold: number };
  technicians: { id: string; qualifications: string[] }[];
  technicianDays: TechnicianDay[];
  appointments: Appointment[];
}

/** The kit's client settings: the shortest daily search the contract allows, and the longest booking deadline. */
export const KIT_RATES: Snapshot["rates"] = { regularHourly: "30", overtimeHourly: "45", mileagePerMile: "0.67", travelBufferPct: "0.1", travelBufferMinutes: 2 };
export const KIT_POLICY: Snapshot["policy"] = { fairnessBudget: "0", dailySearch: "TABU", costSearchSeconds: 5, fairnessSearchSeconds: 0,
  bookingDeadlineMs: 5000, regularWindowThreshold: 2 };

/** An address no map holds, for the technician-day that must be skipped as unlocatable. */
export const UNLOCATABLE: Location = { address: { line1: "1 Conformance Kit Way", city: "Nowhere", state: "ZZ", postalCode: "00000" } };

/** Shifts run 08:00 to 17:00 local time; appointment windows span the whole shift unless a case narrows them. */
export const SHIFT_START = 480;
export const SHIFT_END = 1020;

export class SnapshotBuilder {
  readonly #technicians = new Map<string, Set<string>>();
  readonly #days: TechnicianDay[] = [];
  readonly #appointments: Appointment[] = [];

  constructor(readonly metroId: string, readonly timeZone: string, readonly serviceId: string, readonly lastModified: string) { }

  at(date: string, minute: number): string {
    return localInstant(date, minute, this.timeZone);
  }

  /** A technician working 08:00 to 17:00 on `date` from `home` and back, qualified for the kit's service. */
  day(technicianId: string, date: string, home: Location, overrides: Partial<TechnicianDay> = {}): this {
    const qualifications = this.#technicians.get(technicianId) ?? new Set<string>();
    qualifications.add(this.serviceId);
    this.#technicians.set(technicianId, qualifications);
    this.#days.push({ technicianId, serviceDate: date, lastModified: this.lastModified,
      shift: { start: this.at(date, SHIFT_START), end: this.at(date, SHIFT_END) }, absences: [], start: home, end: home, maxPaidMinutes: 540,
      ...overrides });
    return this;
  }

  /** A 45-minute visit, `sequence` on its technician's day, planned to start at `plannedMinute` local time. */
  visit(id: string, technicianId: string, date: string, location: Location, sequence: number, plannedMinute: number): this {
    this.#appointments.push({ id, technicianId, serviceDate: date, serviceId: this.serviceId, durationMinutes: 45,
      window: { start: this.at(date, SHIFT_START), end: this.at(date, SHIFT_END) }, location, sequence, plannedStart: this.at(date, plannedMinute) });
    return this;
  }

  build(): Snapshot {
    return {
      metroId: this.metroId, timeZone: this.timeZone, rates: { ...KIT_RATES }, policy: { ...KIT_POLICY },
      technicians: [...this.#technicians].map(([id, qualifications]) => ({ id, qualifications: [...qualifications] })),
      technicianDays: this.#days.map(day => structuredClone(day)),
      appointments: this.#appointments.map(appointment => structuredClone(appointment)),
    };
  }
}

/** The technician-day versions a commit sends: one per technician-day of the snapshot, as the host currently holds it. */
export function versions(snapshot: Snapshot): { technicianId: string; serviceDate: string; lastModified: string }[] {
  return snapshot.technicianDays.map(day => ({ technicianId: day.technicianId, serviceDate: day.serviceDate, lastModified: day.lastModified }));
}
