import { Prisma } from "@prisma/client";
import { prisma } from "./prisma";
import { date as dateContract, required } from "./contracts";
import { publicPolicy, publicRates, type StoredSolverSettings } from "./clientSettings";
import { publicSnapshot, type PublicLocation, type PublicSnapshot } from "./publicApi";
import { resolveWeeklyDay, type AvailabilityVersion } from "./technicianAvailability";
import { localMinute } from "./zonedTime";

/**
 * Builds one client's public API snapshot for a metro and a set of service dates from the portal's tables, the way
 * WaterFlex Software would. It contains only that client's technicians and appointments, even when other clients
 * work in the same metro, and mirrors how the scheduler's database path reads the same rows: the effective depot
 * assignment and endpoint policy, the weekly availability with its date exception, approved time off, and the daily
 * paid limit (no overtime is assigned). Anything missing or inconsistent stops the build; nothing is assumed.
 */

/** Shift and absence minutes are stored as America/Chicago wall-clock minutes throughout the portal and scheduler. */
export const STORED_MINUTES_TIME_ZONE = "America/Chicago";

export type SnapshotErrorCode = "NOT_CONFIGURED" | "METRO_NOT_FOUND" | "METRO_NOT_SERVED" | "UNSUPPORTED_TIME_ZONE" | "INCONSISTENT";

export class SnapshotError extends Error {
  constructor(readonly code: SnapshotErrorCode, message: string) { super(message); this.name = "SnapshotError"; }
}

type Anchor = "HOME" | "DEPOT";
export interface DepotFacts { id: string; metroId: string; lat: number; lng: number; policies: Array<{ effectiveDate: string; departure: Anchor; returnTo: Anchor }> }
export interface TechnicianFacts {
  id: string; active: boolean; homeLat: number; homeLng: number; maxDailyMinutes: number; qualifications: string[];
  assignments: Array<{ effectiveDate: string; depot: DepotFacts }>;
  versions: AvailabilityVersion[];
  overrides: Array<{ date: string; available: boolean; shiftStartMin: number | null; shiftEndMin: number | null }>;
  absences: Array<{ date: string; startMin: number; endMin: number }>;
}
export interface AppointmentFacts {
  id: string; technicianId: string; serviceDate: string; serviceId: string; durationMin: number;
  windowStart: Date; windowEnd: Date; sequence: number; plannedStart: Date;
  address: { line1: string; line2: string | null; city: string; state: string; postalCode: string; lat: number | null; lng: number | null };
}
export interface SnapshotFacts {
  metro: { id: string; timezone: string };
  settings: StoredSolverSettings;
  dates: string[];
  technicians: TechnicianFacts[];
  appointments: AppointmentFacts[];
  /** Exact ISO-8601 UTC text per `${technicianId}|${date}`, read from the database so microseconds survive. */
  lastModified: ReadonlyMap<string, string>;
}

const key = (technicianId: string, date: string) => `${technicianId}|${date}`;
const inconsistent = (message: string) => new SnapshotError("INCONSISTENT", message);

/** Valid working hours, or null when they are missing or out of order. */
function hours(start: number | null, end: number | null): { startMin: number; endMin: number } | null {
  return start != null && end != null && Number.isInteger(start) && Number.isInteger(end) && start >= 0 && end <= 1440 && start < end
    ? { startMin: start, endMin: end } : null;
}

/** The working shift on a date, or null for a day off; a date exception replaces the weekly day, as in the scheduler. */
export function shiftOn(technician: TechnicianFacts, date: string): { startMin: number; endMin: number } | null {
  let weekly;
  try { weekly = resolveWeeklyDay(technician.versions, date); }
  catch (error) { throw inconsistent(`Technician ${technician.id}: ${error instanceof Error ? error.message : "weekly availability is invalid"}`); }
  const exceptions = technician.overrides.filter(item => item.date === date);
  if (exceptions.length > 1) throw inconsistent(`Technician ${technician.id} has more than one date exception on ${date}`);
  const exception = exceptions[0];
  if (exception !== undefined) {
    if (!exception.available) {
      if (exception.shiftStartMin != null || exception.shiftEndMin != null) throw inconsistent(`Technician ${technician.id} has an invalid day-off exception on ${date}`);
      return null;
    }
    const exceptionHours = hours(exception.shiftStartMin, exception.shiftEndMin);
    if (exceptionHours === null) throw inconsistent(`Technician ${technician.id} has invalid exception hours on ${date}`);
    return exceptionHours;
  }
  if (!weekly.available) return null;
  const weeklyHours = hours(weekly.shiftStartMin, weekly.shiftEndMin);
  if (weeklyHours === null) throw inconsistent(`Technician ${technician.id} has invalid weekly hours on ${date}`);
  return weeklyHours;
}

/** The latest row effective on or before a date. */
function effective<T extends { effectiveDate: string }>(rows: readonly T[], date: string): T | undefined {
  return rows.filter(row => row.effectiveDate <= date).sort((a, b) => a.effectiveDate.localeCompare(b.effectiveDate)).at(-1);
}

function appointmentLocation(address: AppointmentFacts["address"]): PublicLocation {
  if ((address.lat == null) !== (address.lng == null)) throw inconsistent("An appointment address has only one coordinate");
  const postal = { line1: address.line1, ...(address.line2 ? { line2: address.line2 } : {}), city: address.city,
    state: address.state, postalCode: address.postalCode };
  // Coordinates are authoritative when present; an address alone is located by the scheduler or reported as skipped.
  return address.lat != null && address.lng != null ? { lat: address.lat, lng: address.lng } : { address: postal };
}

/** Applies the scheduler's rules to the loaded rows. Pure, so the rules are tested without a database. */
export function assembleSnapshot(facts: SnapshotFacts): PublicSnapshot {
  if (facts.metro.timezone !== STORED_MINUTES_TIME_ZONE)
    throw new SnapshotError("UNSUPPORTED_TIME_ZONE", `Metro ${facts.metro.id} uses ${facts.metro.timezone}; stored shift minutes are ${STORED_MINUTES_TIME_ZONE} times`);
  const zone = facts.metro.timezone;
  const dates = [...new Set(facts.dates)].sort();
  for (const date of dates) dateContract.parse(date);
  const technicianDays: PublicSnapshot["technicianDays"] = [];
  const working = new Set<string>();
  const included = new Map<string, TechnicianFacts>();
  const inMetro = new Set<string>();
  for (const date of dates) {
    for (const technician of [...facts.technicians].sort((a, b) => a.id.localeCompare(b.id))) {
      const assignment = effective(technician.assignments, date);
      if (assignment === undefined || assignment.depot.metroId !== facts.metro.id) continue;
      inMetro.add(key(technician.id, date));
      if (!technician.active) continue;
      const shift = shiftOn(technician, date);
      if (shift === null) continue;
      const policy = effective(assignment.depot.policies, date);
      if (policy === undefined) throw inconsistent(`Depot ${assignment.depot.id} has no route endpoint policy on ${date}`);
      if (!Number.isInteger(technician.maxDailyMinutes) || technician.maxDailyMinutes < 1 || technician.maxDailyMinutes > 1440)
        throw inconsistent(`Technician ${technician.id} has an invalid daily paid limit`);
      const lastModified = facts.lastModified.get(key(technician.id, date));
      if (lastModified === undefined) throw inconsistent(`Technician ${technician.id} has no change time for ${date}`);
      const home = { lat: technician.homeLat, lng: technician.homeLng };
      const depot = { lat: assignment.depot.lat, lng: assignment.depot.lng };
      technicianDays.push({
        technicianId: technician.id, serviceDate: date, lastModified,
        shift: { start: localMinute(date, shift.startMin, false, zone).toISOString(), end: localMinute(date, shift.endMin, true, zone).toISOString() },
        absences: technician.absences.filter(absence => absence.date === date)
          .sort((a, b) => a.startMin - b.startMin || a.endMin - b.endMin)
          .map(absence => ({ start: localMinute(date, absence.startMin, false, zone).toISOString(), end: localMinute(date, absence.endMin, true, zone).toISOString() })),
        start: policy.departure === "HOME" ? home : depot,
        end: policy.returnTo === "HOME" ? home : depot,
        maxPaidMinutes: technician.maxDailyMinutes,
      });
      working.add(key(technician.id, date));
      included.set(technician.id, technician);
    }
  }
  const appointments: PublicSnapshot["appointments"] = [];
  for (const appointment of facts.appointments) {
    const at = key(appointment.technicianId, appointment.serviceDate);
    // An appointment of a technician working elsewhere that day belongs to another metro's snapshot.
    if (!inMetro.has(at)) continue;
    if (!working.has(at)) throw inconsistent(`Appointment ${appointment.id} is on ${appointment.serviceDate} for technician ${appointment.technicianId}, who is not working that day`);
    appointments.push({ id: appointment.id, technicianId: appointment.technicianId, serviceDate: appointment.serviceDate,
      serviceId: appointment.serviceId, durationMinutes: appointment.durationMin,
      window: { start: appointment.windowStart.toISOString(), end: appointment.windowEnd.toISOString() },
      location: appointmentLocation(appointment.address), sequence: appointment.sequence, plannedStart: appointment.plannedStart.toISOString() });
  }
  appointments.sort((a, b) => a.serviceDate.localeCompare(b.serviceDate) || a.technicianId.localeCompare(b.technicianId) || a.sequence - b.sequence);
  const parsed = publicSnapshot.safeParse({
    metroId: facts.metro.id, timeZone: zone, rates: publicRates(facts.settings), policy: publicPolicy(facts.settings),
    technicians: [...included.values()].sort((a, b) => a.id.localeCompare(b.id))
      .map(technician => ({ id: technician.id, qualifications: [...new Set(technician.qualifications)].sort() })),
    technicianDays, appointments,
  });
  if (!parsed.success) throw inconsistent(`The snapshot does not match the public API: ${parsed.error.message}`);
  return parsed.data;
}

const day = (value: string) => new Date(`${value}T00:00:00Z`);
const dateKey = (value: Date) => value.toISOString().slice(0, 10);

/** Loads one client's rows for a metro and dates in a single repeatable-read transaction, then assembles the snapshot. */
export async function buildClientSnapshot(clientId: string, metroId: string, dates: readonly string[]): Promise<PublicSnapshot> {
  return assembleSnapshot(await loadSnapshotFacts(clientId, metroId, dates));
}

/**
 * The rows a snapshot is assembled from, read in one repeatable-read transaction. A change to the client's own facts
 * can be applied to them before assembling, to see the snapshot as it would be after the change.
 */
export async function loadSnapshotFacts(clientId: string, metroId: string, dates: readonly string[]): Promise<SnapshotFacts> {
  if (dates.length === 0) throw new Error("A snapshot covers at least one date");
  for (const date of dates) dateContract.parse(date);
  const days = [...new Set(dates)].sort();
  const stamps = days.map(day);
  const facts = await prisma.$transaction(async tx => {
    const [settings, metro, served] = await Promise.all([
      tx.clientSolverSettings.findUnique({ where: { clientId } }),
      tx.metro.findUnique({ where: { id: metroId }, select: { id: true, timezone: true } }),
      tx.depot.count({ where: { metroId, dealership: { clientId } } }),
    ]);
    if (settings === null) throw new SnapshotError("NOT_CONFIGURED", `Client ${clientId} has no solver settings yet`);
    if (metro === null) throw new SnapshotError("METRO_NOT_FOUND", `Metro ${metroId} does not exist`);
    if (served === 0) throw new SnapshotError("METRO_NOT_SERVED", `Client ${clientId} has no depot in metro ${metroId}`);
    const lastDate = day(required(days.at(-1), "last snapshot date"));
    const technicians = await tx.technician.findMany({ where: { clientId }, orderBy: { id: "asc" }, select: {
      id: true, active: true, homeLat: true, homeLng: true, maxDailyMinutes: true,
      qualifications: { select: { serviceId: true } },
      depotAssignments: { where: { effectiveDate: { lte: lastDate } }, select: { effectiveDate: true, depot: { select: {
        id: true, metroId: true, lat: true, lng: true, endpointPolicies: { where: { effectiveDate: { lte: lastDate } },
          select: { effectiveDate: true, departure: true, returnTo: true } } } } } },
      availabilityVersions: { select: { effectiveDate: true, days: { select: { dayOfWeek: true, available: true, shiftStartMin: true, shiftEndMin: true } } } },
      shiftOverrides: { where: { serviceDate: { in: stamps } }, select: { serviceDate: true, available: true, shiftStartMin: true, shiftEndMin: true } },
    } });
    const ids = technicians.map(technician => technician.id);
    const [intervals, appointments, changes] = await Promise.all([
      tx.timeOffInterval.findMany({ where: { serviceDate: { in: stamps }, request: { status: "APPROVED", technicianId: { in: ids } } },
        select: { serviceDate: true, startMin: true, endMin: true, request: { select: { technicianId: true } } } }),
      tx.appointment.findMany({ where: { technicianId: { in: ids }, serviceDate: { in: stamps }, cancelledAt: null }, select: {
        id: true, technicianId: true, serviceDate: true, windowStart: true, windowEnd: true, sequence: true, plannedStart: true,
        job: { select: { serviceId: true, durationMin: true, address: { select: { line1: true, line2: true, city: true, state: true, postalCode: true, lat: true, lng: true } } } },
      } }),
      ids.length === 0 ? Promise.resolve([]) : tx.$queryRaw<Array<{ technicianId: string; serviceDate: string; lastModified: string }>>(Prisma.sql`
        SELECT t.id AS "technicianId", to_char(d.day, 'YYYY-MM-DD') AS "serviceDate",
          to_char(GREATEST(t."factsChangedAt", sd."changedAt") AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"') AS "lastModified"
        FROM technician t CROSS JOIN unnest(${days}::date[]) AS d(day)
        LEFT JOIN technician_day_change sd ON sd."technicianId" = t.id AND sd."serviceDate" = d.day::timestamp
        WHERE t.id IN (${Prisma.join(ids)})`),
    ]);
    return { settings, metro, technicians, intervals, appointments, changes };
  }, { isolationLevel: Prisma.TransactionIsolationLevel.RepeatableRead });

  return {
    metro: facts.metro, settings: facts.settings, dates: [...dates],
    technicians: facts.technicians.map(technician => ({
      id: technician.id, active: technician.active, homeLat: technician.homeLat, homeLng: technician.homeLng,
      maxDailyMinutes: technician.maxDailyMinutes, qualifications: technician.qualifications.map(item => item.serviceId),
      assignments: technician.depotAssignments.map(assignment => ({ effectiveDate: dateKey(assignment.effectiveDate), depot: {
        id: assignment.depot.id, metroId: assignment.depot.metroId, lat: assignment.depot.lat, lng: assignment.depot.lng,
        policies: assignment.depot.endpointPolicies.map(policy => ({ effectiveDate: dateKey(policy.effectiveDate), departure: policy.departure, returnTo: policy.returnTo })),
      } })),
      versions: technician.availabilityVersions,
      overrides: technician.shiftOverrides.map(item => ({ date: dateKey(item.serviceDate), available: item.available, shiftStartMin: item.shiftStartMin, shiftEndMin: item.shiftEndMin })),
      absences: facts.intervals.filter(item => item.request.technicianId === technician.id)
        .map(item => ({ date: dateKey(item.serviceDate), startMin: item.startMin, endMin: item.endMin })),
    })),
    appointments: facts.appointments.map(appointment => ({
      id: appointment.id, technicianId: appointment.technicianId, serviceDate: dateKey(appointment.serviceDate),
      serviceId: appointment.job.serviceId, durationMin: appointment.job.durationMin, windowStart: appointment.windowStart,
      windowEnd: appointment.windowEnd, sequence: appointment.sequence, plannedStart: appointment.plannedStart, address: appointment.job.address,
    })),
    lastModified: new Map(facts.changes.map(change => [key(change.technicianId, change.serviceDate), change.lastModified])),
  };
}
