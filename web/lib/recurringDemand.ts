import type { Prisma } from "@prisma/client";
import { prisma } from "./prisma";
import { addCalendarDays, todayInTz } from "./date";
import { shiftOn, SnapshotError } from "./clientSnapshot";
import { validateVersions } from "./technicianAvailability";
import { suggestedMinutes } from "./reasonCodesCore";
import { minuteOfDay, placeLine, type ReasonCodeRef } from "./workOrdersDayCore";
import {
  demandWeeks, freeIntervals, nearestDepot, nextDue, totalMinutes, weekOfDue, type Interval, type RecurrenceUnit,
} from "./recurringDemandCore";

type Db = Prisma.TransactionClient | typeof prisma;
const day = (value: Date) => value.toISOString().slice(0, 10);
const utcDay = (value: string) => new Date(`${value}T00:00:00.000Z`);

export interface DemandBranch { id: string; name: string; metroId: string; metroName: string; timezone: string }

/** A reservation as the page shows it. A BOOKED one has its work order (jobId). */
export interface DemandReservation {
  id: string; seriesId: string; customerName: string; technicianId: string; technicianName: string; date: string;
  startMin: number; endMin: number; status: "RESERVED" | "BOOKED"; jobId: string | null; placed: boolean;
}

export interface DemandSeries {
  id: string;
  accountNumber: string;
  /** The account's name; null when WaterFlex's export has none. */
  customerName: string | null;
  phone: string | null;
  email: string | null;
  addressLine: string;
  place: string;
  location: { lat: number; lng: number } | null;
  /** The branch whose service area reaches the account; null without coordinates or outside every area. */
  branchId: string | null;
  workOrderType: string | null;
  codes: ReasonCodeRef[];
  /** The time the codes suggest; null when a code's minutes are unusable, so the series cannot be reserved. */
  durationMin: number | null;
  intervalUnit: RecurrenceUnit;
  intervalCount: number;
  lastVisitDate: string | null;
  dueDate: string;
  /** The grid week the due date falls in (an overdue one in the first); null beyond the grid. */
  week: string | null;
  overdue: boolean;
  /** The live reservation for this due date, if any. */
  reservation: DemandReservation | null;
}

export interface DemandVisit { id: string; customerName: string; startMin: number; endMin: number; location: { lat: number; lng: number } | null }

/** One technician's working weekday at a branch: the shift, what fills it, and the free time left. */
export interface DemandDay {
  branchId: string; technicianId: string; technicianName: string; date: string;
  shift: Interval | null; visits: DemandVisit[]; reservations: DemandReservation[]; free: Interval[]; freeMin: number;
}

export interface DemandData { today: string; weeks: string[]; branches: DemandBranch[]; series: DemandSeries[]; days: DemandDay[] }

async function branchesOf(db: Db, clientId: string) {
  return db.depot.findMany({ where: { dealership: { clientId } }, orderBy: { name: "asc" },
    select: { id: true, name: true, lat: true, lng: true, metro: { select: { id: true, name: true, timezone: true, serviceRadiusMi: true } } } });
}

/**
 * The working weekdays from `from` to `to` (inclusive) of every technician assigned to one of `branchIds`: each day's
 * shift (date exceptions over the weekly hours), booked visits, approved time off and live reservations not yet placed
 * on the calendar, and the free time they leave. A booked reservation whose work order is placed counts once, as its
 * visit.
 */
export async function technicianDays(db: Db, clientId: string, branches: readonly DemandBranch[], from: string, to: string): Promise<DemandDay[]> {
  const start = utcDay(from), end = utcDay(addCalendarDays(to, 1));
  const branchIds = new Set(branches.map(branch => branch.id));
  const technicians = await db.technician.findMany({
    where: { clientId, active: true }, orderBy: { name: "asc" },
    include: { availabilityVersions: { include: { days: true }, orderBy: { effectiveDate: "asc" } },
      depotAssignments: { where: { effectiveDate: { lt: end } }, orderBy: { effectiveDate: "asc" } },
      shiftOverrides: { where: { serviceDate: { gte: start, lt: end } } } },
  });
  const ids = technicians.map(technician => technician.id);
  const [appointments, absences, reservations] = await Promise.all([
    db.appointment.findMany({ where: { technicianId: { in: ids }, serviceDate: { gte: start, lt: end }, cancelledAt: null },
      include: { job: { select: { customer: { select: { firstName: true, lastName: true } }, address: { select: { lat: true, lng: true } } } } } }),
    db.timeOffInterval.findMany({ where: { serviceDate: { gte: start, lt: end }, request: { status: "APPROVED", technicianId: { in: ids } } },
      include: { request: { select: { technicianId: true } } } }),
    db.reservation.findMany({ where: { technicianId: { in: ids }, serviceDate: { gte: start, lt: end }, status: { not: "RELEASED" } },
      include: { technician: { select: { name: true } }, job: { select: { appointment: { select: { id: true } } } },
        series: { select: { account: { select: { name: true, accountNumber: true } } } } } }),
  ]);

  const dates: string[] = [];
  for (let date = from; date <= to; date = addCalendarDays(date, 1)) {
    const weekday = utcDay(date).getUTCDay();
    if (weekday !== 0 && weekday !== 6) dates.push(date);
  }
  const result: DemandDay[] = [];
  for (const technician of technicians) {
    const facts = { id: technician.id,
      versions: validateVersions(technician.availabilityVersions.map(version => ({ effectiveDate: version.effectiveDate, days: version.days }))),
      overrides: technician.shiftOverrides.map(item => ({ date: day(item.serviceDate), available: item.available,
        shiftStartMin: item.shiftStartMin, shiftEndMin: item.shiftEndMin })) };
    for (const date of dates) {
      const branchId = technician.depotAssignments.filter(assignment => day(assignment.effectiveDate) <= date).at(-1)?.depotId;
      if (branchId === undefined || !branchIds.has(branchId)) continue;
      const branch = branches.find(item => item.id === branchId);
      if (branch === undefined) continue;
      const hours = shiftOn(facts, date);
      const shift = hours === null ? null : { start: hours.startMin, end: hours.endMin };
      const visits: DemandVisit[] = appointments.filter(item => item.technicianId === technician.id && day(item.serviceDate) === date).map(item => {
        const startMin = minuteOfDay(item.plannedStart.toISOString(), branch.timezone);
        const endMin = minuteOfDay(item.plannedEnd.toISOString(), branch.timezone) || 1440;
        const { lat, lng } = item.job.address;
        return { id: item.id, customerName: `${item.job.customer.firstName} ${item.job.customer.lastName}`, startMin, endMin,
          location: lat !== null && lng !== null ? { lat, lng } : null };
      }).sort((a, b) => a.startMin - b.startMin);
      const held: DemandReservation[] = reservations.filter(item => item.technicianId === technician.id && day(item.serviceDate) === date)
        .map(item => reservationView(item)).sort((a, b) => a.startMin - b.startMin);
      const busy = [
        ...visits.map(visit => ({ start: visit.startMin, end: visit.endMin })),
        ...absences.filter(item => item.request.technicianId === technician.id && day(item.serviceDate) === date).map(item => ({ start: item.startMin, end: item.endMin })),
        ...held.filter(item => !item.placed).map(item => ({ start: item.startMin, end: item.endMin })),
      ];
      const free = freeIntervals(shift, busy);
      result.push({ branchId, technicianId: technician.id, technicianName: technician.name, date, shift, visits, reservations: held, free, freeMin: totalMinutes(free) });
    }
  }
  return result.sort((a, b) => a.date.localeCompare(b.date) || a.technicianName.localeCompare(b.technicianName));
}

type ReservationRow = {
  id: string; seriesId: string; technicianId: string; serviceDate: Date; startMin: number; endMin: number; status: "RESERVED" | "BOOKED" | "RELEASED";
  jobId: string | null; technician: { name: string }; job: { appointment: { id: string } | null } | null;
  series: { account: { name: string | null; accountNumber: string } };
};

function reservationView(row: ReservationRow): DemandReservation {
  if (row.status === "RELEASED") throw new SnapshotError("INCONSISTENT", `Reservation ${row.id} is released but was read as live`);
  return { id: row.id, seriesId: row.seriesId, customerName: row.series.account.name ?? `Account ${row.series.account.accountNumber}`,
    technicianId: row.technicianId, technicianName: row.technician.name, date: day(row.serviceDate), startMin: row.startMin, endMin: row.endMin,
    status: row.status, jobId: row.jobId, placed: row.job?.appointment != null };
}

/** The branches of a client, for the page and the reservation routes. */
export async function demandBranches(db: Db, clientId: string): Promise<Array<DemandBranch & { lat: number; lng: number; radiusMi: number }>> {
  return (await branchesOf(db, clientId)).map(branch => ({ id: branch.id, name: branch.name, metroId: branch.metro.id, metroName: branch.metro.name, timezone: branch.metro.timezone,
    lat: branch.lat, lng: branch.lng, radiusMi: branch.metro.serviceRadiusMi }));
}

/** A client's active series as the page shows them, each with its due date, branch and live reservation. */
export async function demandSeries(db: Db, clientId: string, branches: ReadonlyArray<{ id: string; lat: number; lng: number; radiusMi: number }>,
  weeks: readonly string[], today: string, seriesIds?: readonly string[]): Promise<DemandSeries[]> {
  const rows = await db.recurringSeries.findMany({
    where: { clientId, active: true, ...(seriesIds === undefined ? {} : { id: { in: [...seriesIds] } }) },
    include: { account: true, service: true, reasonCodes: { include: { reasonCode: true }, orderBy: { position: "asc" } },
      reservations: { where: { status: { not: "RELEASED" } },
        include: { technician: { select: { name: true } }, job: { select: { appointment: { select: { id: true } } } },
          series: { select: { account: { select: { name: true, accountNumber: true } } } } } } },
  });
  return rows.map(row => {
    const codes = [row.service, ...row.reasonCodes.map(item => item.reasonCode)];
    const dueDate = nextDue({ intervalUnit: row.intervalUnit, intervalCount: row.intervalCount, startDate: day(row.startDate),
      lastVisitDate: row.lastVisitDate === null ? null : day(row.lastVisitDate) });
    const { lat, lng } = row.account;
    const location = lat !== null && lng !== null ? { lat, lng } : null;
    const live = row.reservations.find(item => day(item.dueDate) === dueDate);
    return { id: row.id, accountNumber: row.account.accountNumber, customerName: row.account.name, phone: row.account.phone, email: row.account.email,
      addressLine: [row.account.line1, row.account.line2].filter(Boolean).join(", "),
      place: placeLine(row.account.city, row.account.state, row.account.postalCode), location,
      branchId: location === null ? null : nearestDepot(location, branches),
      workOrderType: row.workOrderType, codes: codes.map(code => ({ code: code.code, name: code.name })),
      durationMin: suggestedMinutes(codes), intervalUnit: row.intervalUnit, intervalCount: row.intervalCount,
      lastVisitDate: row.lastVisitDate === null ? null : day(row.lastVisitDate), dueDate, week: weekOfDue(dueDate, weeks), overdue: dueDate < today,
      reservation: live === undefined ? null : reservationView(live) };
  }).sort((a, b) => a.dueDate.localeCompare(b.dueDate) || a.accountNumber.localeCompare(b.accountNumber));
}

/**
 * Everything the recurring demand page draws for a client: its branches, the four weeks from today in the first
 * branch's time zone, its active series and every branch technician's working weekday in those weeks. Reads only.
 * Null when the client has no branch yet.
 */
export async function loadDemand(clientId: string): Promise<DemandData | null> {
  const branches = await demandBranches(prisma, clientId);
  const first = branches[0];
  if (first === undefined) return null;
  const today = todayInTz(first.timezone);
  const weeks = demandWeeks(today);
  const lastWeek = weeks.at(-1);
  if (lastWeek === undefined) return null;
  const [series, days] = await Promise.all([
    demandSeries(prisma, clientId, branches, weeks, today),
    technicianDays(prisma, clientId, branches, weeks[0] ?? lastWeek, addCalendarDays(lastWeek, 4)),
  ]);
  return { today, weeks, branches: branches.map(({ id, name, metroId, metroName, timezone }) => ({ id, name, metroId, metroName, timezone })), series, days };
}

