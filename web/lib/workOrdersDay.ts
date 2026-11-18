import { prisma } from "./prisma";
import { resolveWeeklyDay, validateVersions } from "./technicianAvailability";
import {
  initials, minuteOfDay, pastelOf, placeLine, typePastels, workOrderStatus,
  type WaitingWorkOrder, type WorkOrderCard, type WorkOrderTechnician, type WorkOrdersDay,
} from "./workOrdersDayCore";

export type { WorkOrdersDay } from "./workOrdersDayCore";

/** A working day's shift, or null on a day off. A working day without shift times is bad data, never a guessed shift. */
function dayShift(name: string, date: string, day: { available: boolean; shiftStartMin: number | null; shiftEndMin: number | null }) {
  if (!day.available) return null;
  if (day.shiftStartMin === null || day.shiftEndMin === null) throw new Error(`${name} works on ${date} but has no shift times`);
  return { startMin: day.shiftStartMin, endMin: day.shiftEndMin };
}

/** The metros the client has a depot in, by name. */
export async function clientMetros(clientId: string) {
  return prisma.metro.findMany({ where: { depots: { some: { dealership: { clientId } } } }, orderBy: { name: "asc" },
    select: { id: true, name: true, timezone: true } });
}

/**
 * One metro day for the work orders section, read only: the technicians whose depot is in the metro that day, their
 * booked visits, and the client's jobs still waiting for a visit. It reads the same rows as the classic schedule and
 * dispatch pages and writes nothing.
 */
export async function loadWorkOrdersDay(clientId: string, metro: { id: string; name: string; timezone: string }, date: string): Promise<WorkOrdersDay> {
  const dayStart = new Date(`${date}T00:00:00.000Z`);
  const dayEnd = new Date(dayStart.getTime() + 24 * 60 * 60 * 1000);

  const candidates = await prisma.technician.findMany({
    where: { clientId, active: true },
    orderBy: { name: "asc" },
    include: {
      availabilityVersions: { include: { days: true }, orderBy: { effectiveDate: "asc" } },
      depotAssignments: { where: { effectiveDate: { lte: dayStart } }, include: { depot: { select: { metroId: true } } }, orderBy: { effectiveDate: "desc" }, take: 1 },
      shiftOverrides: { where: { serviceDate: dayStart } },
    },
  });
  const members = candidates.filter(tech => tech.depotAssignments[0]?.depot.metroId === metro.id);

  const technicians: WorkOrderTechnician[] = members.map(tech => {
    const override = tech.shiftOverrides[0];
    const day = override ?? resolveWeeklyDay(validateVersions(tech.availabilityVersions.map(version => ({ effectiveDate: version.effectiveDate, days: version.days }))), date);
    return { id: tech.id, name: tech.name, color: tech.color, initials: initials(tech.name), homeLat: tech.homeLat, homeLng: tech.homeLng,
      shift: dayShift(tech.name, date, day),
      usualShift: { startMin: tech.shiftStartMin, endMin: tech.shiftEndMin } };
  });

  const [appointments, pendingJobs, catalog] = await Promise.all([
    prisma.appointment.findMany({
      where: { technicianId: { in: members.map(tech => tech.id) }, serviceDate: { gte: dayStart, lt: dayEnd }, cancelledAt: null },
      include: { job: { include: { customer: { select: { firstName: true, lastName: true } }, service: { select: { code: true, name: true } }, address: true } } },
      orderBy: [{ technicianId: "asc" }, { sequence: "asc" }, { plannedStart: "asc" }],
    }),
    // The same waiting jobs as the classic schedule's list: the client's pending jobs with no visit, newest first.
    prisma.job.findMany({ where: { status: "PENDING", appointment: null, customer: { clientId } }, orderBy: { createdAt: "desc" }, take: 50,
      include: { customer: { select: { firstName: true, lastName: true } }, service: { select: { code: true, name: true } },
        address: { select: { city: true, state: true, postalCode: true, lat: true, lng: true } } } }),
    prisma.serviceCatalog.findMany({ select: { code: true } }),
  ]);
  const pastels = typePastels(catalog.map(item => item.code));

  const cards: WorkOrderCard[] = appointments.map(appointment => {
    const { job } = appointment;
    const plannedStart = appointment.plannedStart.toISOString(), plannedEnd = appointment.plannedEnd.toISOString();
    return {
      appointmentId: appointment.id, jobId: job.id, technicianId: appointment.technicianId,
      status: workOrderStatus(job.status), jobStatus: job.status,
      typeCode: job.service.code, typeName: job.service.name,
      customerName: `${job.customer.firstName} ${job.customer.lastName}`,
      street: [job.address.line1, job.address.line2].filter(Boolean).join(", "),
      place: placeLine(job.address.city, job.address.state, job.address.postalCode),
      startMin: minuteOfDay(plannedStart, metro.timezone), endMin: minuteOfDay(plannedEnd, metro.timezone) || 1440,
      windowStart: appointment.windowStart.toISOString(), windowEnd: appointment.windowEnd.toISOString(), plannedStart, plannedEnd,
      sequence: appointment.sequence, durationMin: job.durationMin,
      lat: job.address.lat, lng: job.address.lng, geocodePrecision: job.address.geocodePrecision,
      pinConfirmedAt: job.address.pinConfirmedAt?.toISOString() ?? null,
    };
  });

  const waiting: WaitingWorkOrder[] = pendingJobs.map(job => ({
    jobId: job.id, typeCode: job.service.code, typeName: job.service.name, customerName: `${job.customer.firstName} ${job.customer.lastName}`,
    place: placeLine(job.address.city, job.address.state, job.address.postalCode), durationMin: job.durationMin,
    located: job.address.lat !== null && job.address.lng !== null,
  }));

  const shownTypes = new Map<string, string>();
  for (const item of [...cards, ...waiting]) shownTypes.set(item.typeCode, item.typeName);
  const types = [...shownTypes.entries()].sort(([, a], [, b]) => a.localeCompare(b))
    .map(([code, name]) => ({ code, name, background: pastelOf(pastels, code) }));

  return { metro, date, technicians, cards, waiting, types };
}
