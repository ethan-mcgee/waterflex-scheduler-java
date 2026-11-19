import { prisma } from "@/lib/prisma";
import { scheduleVersion } from "@/lib/scheduleVersion";
import { addCalendarDays, mondayOfWeek, weekDates } from "@/lib/date";
import { resolveWeeklyDay, validateVersions } from "@/lib/technicianAvailability";
import { colorOf, serviceColors } from "@/lib/serviceColors";
import { placeLine, workOrderStatus } from "@/lib/workOrdersDayCore";
import { WORK_ORDER_INCLUDE, workOrderFields } from "@/lib/workOrderFields";
import type { ScheduleAbsence, ScheduleAppointment, ScheduleJob, ScheduleService, ScheduleServiceLegend, ScheduleTechnician } from "./types";

export interface CalendarMetro { id: string; name: string; timezone: string }

/** Everything the work orders calendar draws for one metro week, and what it needs to place and move work. */
export interface CalendarData {
  monday: string;
  technicians: ScheduleTechnician[];
  appointments: ScheduleAppointment[];
  absences: ScheduleAbsence[];
  jobs: ScheduleJob[];
  services: ScheduleService[];
  /** Whether the client has WaterFlex accounts, so the new work order form can find a customer by one. */
  accountsAvailable: boolean;
  serviceLegend: ScheduleServiceLegend[];
  version: string;
}

/**
 * The metro week containing `date`: the technicians whose depot is in the metro on some day of it, their visits and
 * approved time off, and the client's jobs waiting for the calendar. Reads only.
 */
export async function loadCalendar(clientId: string, metro: CalendarMetro, date: string): Promise<CalendarData> {
  const monday = mondayOfWeek(date);
  const nextMonday = addCalendarDays(monday, 7);
  const weekStart = new Date(`${monday}T00:00:00.000Z`);
  const weekEnd = new Date(`${nextMonday}T00:00:00.000Z`);

  // Read before the schedule itself, so a change that lands while the page loads is seen as newer, never missed.
  const version = await scheduleVersion(clientId, monday);
  const technicians = await prisma.technician.findMany({
    where: { clientId, active: true },
    orderBy: { name: "asc" },
    include: { availabilityVersions: { include: { days: true }, orderBy: { effectiveDate: "asc" } },
      depotAssignments: { where: { effectiveDate: { lt: weekEnd } }, include: { depot: { select: { metroId: true } } }, orderBy: { effectiveDate: "asc" } },
      shiftOverrides: { where: { serviceDate: { gte: weekStart, lt: weekEnd } } } },
  });

  const metroTechnicians = technicians.filter(technician => weekDates(monday).some(day =>
    technician.depotAssignments.filter(assignment => assignment.effectiveDate.toISOString().slice(0, 10) <= day).at(-1)?.depot.metroId === metro.id));
  const technicianIds = metroTechnicians.map(technician => technician.id);
  const appointments = await prisma.appointment.findMany({
    where: { technicianId: { in: technicianIds }, serviceDate: { gte: weekStart, lt: weekEnd }, cancelledAt: null },
    include: { technician: { select: { name: true } }, job: { include: { customer: true, address: true, ...WORK_ORDER_INCLUDE } } },
    orderBy: [{ technicianId: "asc" }, { serviceDate: "asc" }, { plannedStart: "asc" }, { sequence: "asc" }],
  });
  const absenceIntervals = await prisma.timeOffInterval.findMany({
    where: { serviceDate: { gte: weekStart, lt: weekEnd }, request: { status: "APPROVED", technicianId: { in: technicianIds } } },
    include: { request: { select: { technicianId: true } } },
  });
  const absences: ScheduleAbsence[] = absenceIntervals.map(interval => ({
    technicianId: interval.request.technicianId, date: interval.serviceDate.toISOString().slice(0, 10),
    startMin: interval.startMin, endMin: interval.endMin,
  }));

  // Jobs waiting to be placed, newest first, and the services a new job can be for.
  // Every service, retired ones too, is given its color from the whole catalog, so a service's color never shifts.
  const [pendingJobs, services, catalog, accountCount] = await Promise.all([
    prisma.job.findMany({ where: { status: "PENDING", appointment: null, customer: { clientId } }, orderBy: { createdAt: "desc" }, take: 50,
      include: { customer: { select: { firstName: true, lastName: true } }, ...WORK_ORDER_INCLUDE,
        address: { select: { line1: true, city: true, state: true, postalCode: true, lat: true, lng: true } } } }),
    prisma.serviceCatalog.findMany({ where: { active: true }, orderBy: [{ sortOrder: "asc" }, { name: "asc" }], select: { code: true, name: true, estDurationMin: true, bufferPct: true } }),
    prisma.serviceCatalog.findMany({ select: { code: true } }),
    prisma.account.count({ where: { clientId } }),
  ]);
  const colors = serviceColors(catalog.map(item => item.code));
  const jobs: ScheduleJob[] = pendingJobs.map(job => ({ id: job.id, customerName: `${job.customer.firstName} ${job.customer.lastName}`,
    serviceCode: job.service.code, serviceName: job.service.name, serviceColor: colorOf(colors, job.service.code), durationMin: job.durationMin,
    workOrder: workOrderFields(job),
    addressLine: `${job.address.line1}, ${job.address.city}, ${job.address.state}`, place: placeLine(job.address.city, job.address.state, job.address.postalCode),
    located: job.address.lat !== null && job.address.lng !== null, followUp: job.manualFollowUpStatus }));

  const scheduleAppointments: ScheduleAppointment[] = appointments.filter(appointment =>
    metroTechnicians.find(tech => tech.id === appointment.technicianId)?.depotAssignments
      .filter(assignment => assignment.effectiveDate <= appointment.serviceDate).at(-1)?.depot.metroId === metro.id).map(appointment => {
    const address = appointment.job.address;
    return {
      id: appointment.id,
      jobId: appointment.jobId,
      technicianId: appointment.technicianId,
      technicianName: appointment.technician.name,
      date: appointment.serviceDate.toISOString().slice(0, 10),
      customerName: `${appointment.job.customer.firstName} ${appointment.job.customer.lastName}`,
      serviceCode: appointment.job.service.code,
      serviceName: appointment.job.service.name,
      serviceColor: colorOf(colors, appointment.job.service.code),
      status: workOrderStatus(appointment.job.status),
      workOrder: workOrderFields(appointment.job),
      addressLine: [address.line1, address.line2, address.city, address.state, address.postalCode].filter(Boolean).join(", "),
      place: placeLine(address.city, address.state, address.postalCode),
      windowStart: appointment.windowStart.toISOString(),
      windowEnd: appointment.windowEnd.toISOString(),
      plannedStart: appointment.plannedStart.toISOString(),
      plannedEnd: appointment.plannedEnd.toISOString(),
    };
  });

  // The legend names the services on screen this week: booked visits and jobs waiting for the calendar.
  const legendCodes = new Map<string, string>();
  for (const appointment of appointments) legendCodes.set(appointment.job.service.code, appointment.job.service.name);
  for (const job of pendingJobs) legendCodes.set(job.service.code, job.service.name);
  const serviceLegend: ScheduleServiceLegend[] = [...legendCodes.entries()].sort(([, a], [, b]) => a.localeCompare(b))
    .map(([code, name]) => ({ code, name, color: colorOf(colors, code) }));

  const calendarTechnicians: ScheduleTechnician[] = metroTechnicians.map(technician => {
    const versions = validateVersions(technician.availabilityVersions.map(version => ({ effectiveDate: version.effectiveDate, days: version.days })));
    const days = Object.fromEntries(weekDates(monday).map(day => {
      const override = technician.shiftOverrides.find(item => item.serviceDate.toISOString().slice(0, 10) === day);
      const standard = resolveWeeklyDay(versions, day);
      const member = technician.depotAssignments.filter(assignment => assignment.effectiveDate.toISOString().slice(0, 10) <= day)
        .at(-1)?.depot.metroId === metro.id;
      return [day, override ? { member, available: override.available, shiftStartMin: override.shiftStartMin, shiftEndMin: override.shiftEndMin }
        : { member, available: standard.available, shiftStartMin: standard.shiftStartMin, shiftEndMin: standard.shiftEndMin }];
    }));
    return { id: technician.id, name: technician.name, color: technician.color,
      shiftStartMin: technician.shiftStartMin, shiftEndMin: technician.shiftEndMin, days };
  });

  return { monday, technicians: calendarTechnicians, appointments: scheduleAppointments, absences, jobs, services, accountsAvailable: accountCount > 0, serviceLegend, version };
}
