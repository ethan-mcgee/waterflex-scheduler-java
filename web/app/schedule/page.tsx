import { date as dateContract } from "@/lib/contracts";
import { prisma } from "@/lib/prisma";
import { activeClient } from "@/lib/activeClient";
import { addCalendarDays, mondayOfWeek, tomorrowInTz } from "@/lib/date";
import ScheduleView from "./ScheduleView";
import type { ScheduleAbsence, ScheduleAppointment, ScheduleTechnician } from "./types";
import { resolveWeeklyDay, validateVersions } from "@/lib/technicianAvailability";
import { weekDates } from "@/lib/date";
import Link from "next/link";

export const dynamic = "force-dynamic";

function validDateKey(value: string | undefined): value is string {
  return value !== undefined && dateContract.safeParse(value).success;
}

export default async function SchedulePage({
  searchParams,
}: {
  searchParams: { week?: string; metroId?: string };
}) {
  const clientId = (await activeClient()).id;
  const metros = await prisma.metro.findMany({ where: { depots: { some: { dealership: { clientId } } } }, orderBy: { name: "asc" } });
  const metro = searchParams.metroId ? metros.find(item => item.id === searchParams.metroId) : metros[0];
  if (!metro) {
    return (
      <main>
        <p>{searchParams.metroId ? "Selected metro is unavailable." : "No metro configured yet."}</p>
      </main>
    );
  }

  const requestedDate = validDateKey(searchParams.week)
    ? searchParams.week
    : tomorrowInTz(metro.timezone);
  const monday = mondayOfWeek(requestedDate);
  const nextMonday = addCalendarDays(monday, 7);
  const weekStart = new Date(`${monday}T00:00:00.000Z`);
  const weekEnd = new Date(`${nextMonday}T00:00:00.000Z`);

  const technicians = await prisma.technician.findMany({
    where: { clientId, active: true },
    orderBy: { name: "asc" },
    include: { availabilityVersions: { include: { days: true }, orderBy: { effectiveDate: "asc" } },
      depotAssignments: { where: { effectiveDate: { lt: weekEnd } }, include: { depot: { select: { metroId: true } } }, orderBy: { effectiveDate: "asc" } },
      shiftOverrides: { where: { serviceDate: { gte: weekStart, lt: weekEnd } } } },
  });

  const metroTechnicians = technicians.filter(technician => weekDates(monday).some(day =>
    technician.depotAssignments.filter(assignment => assignment.effectiveDate.toISOString().slice(0, 10) <= day).at(-1)?.depot.metroId === metro.id));
  const technicianIds = metroTechnicians.map((technician) => technician.id);
  const appointments = await prisma.appointment.findMany({
    where: {
      technicianId: { in: technicianIds },
      serviceDate: { gte: weekStart, lt: weekEnd },
      cancelledAt: null,
    },
    include: {
      technician: { select: { name: true } },
      job: {
        include: {
          customer: true,
          service: true,
          address: true,
        },
      },
    },
    orderBy: [{ technicianId: "asc" }, { serviceDate: "asc" }, { plannedStart: "asc" }, { sequence: "asc" }],
  });
  const absenceIntervals = await prisma.timeOffInterval.findMany({
    where: { serviceDate: { gte: weekStart, lt: weekEnd }, request: { status: "APPROVED", technicianId: { in: technicianIds } } },
    include: { request: { select: { technicianId: true } } },
  });
  const absences: ScheduleAbsence[] = absenceIntervals.map((interval) => ({
    technicianId: interval.request.technicianId, date: interval.serviceDate.toISOString().slice(0, 10),
    startMin: interval.startMin, endMin: interval.endMin,
  }));

  const scheduleAppointments: ScheduleAppointment[] = appointments.filter(appointment =>
    metroTechnicians.find(tech => tech.id === appointment.technicianId)?.depotAssignments
      .filter(assignment => assignment.effectiveDate <= appointment.serviceDate).at(-1)?.depot.metroId === metro.id).map((appointment) => ({
    id: appointment.id,
    technicianId: appointment.technicianId,
    technicianName: appointment.technician.name,
    date: appointment.serviceDate.toISOString().slice(0, 10),
    customerName: `${appointment.job.customer.firstName} ${appointment.job.customer.lastName}`,
    serviceName: appointment.job.service.name,
    addressLine: [
      appointment.job.address.line1,
      appointment.job.address.line2,
      appointment.job.address.city,
      appointment.job.address.state,
      appointment.job.address.postalCode,
    ]
      .filter(Boolean)
      .join(", "),
    windowStart: appointment.windowStart.toISOString(),
    windowEnd: appointment.windowEnd.toISOString(),
    plannedStart: appointment.plannedStart.toISOString(),
    plannedEnd: appointment.plannedEnd.toISOString(),
  }));

  return <>
    {metros.length > 1 && <nav aria-label="Schedule metro" style={{ display: "flex", gap: 12, padding: 16 }}>
      {metros.map(item => <Link key={item.id} href={`/schedule?metroId=${encodeURIComponent(item.id)}&week=${monday}`} aria-current={item.id === metro.id ? "page" : undefined}>{item.name}</Link>)}
    </nav>}
    <ScheduleView
      metroId={metro.id}
      monday={monday}
      timezone={metro.timezone}
      technicians={metroTechnicians.map(technician => {
        const versions = validateVersions(technician.availabilityVersions.map(version => ({ effectiveDate: version.effectiveDate, days: version.days })));
        const days = Object.fromEntries(weekDates(monday).map(date => {
          const override = technician.shiftOverrides.find(item => item.serviceDate.toISOString().slice(0, 10) === date);
          const standard = resolveWeeklyDay(versions, date);
          const member = technician.depotAssignments.filter(assignment => assignment.effectiveDate.toISOString().slice(0, 10) <= date)
            .at(-1)?.depot.metroId === metro.id;
          return [date, override ? { member, available: override.available, shiftStartMin: override.shiftStartMin, shiftEndMin: override.shiftEndMin }
            : { member, available: standard.available, shiftStartMin: standard.shiftStartMin, shiftEndMin: standard.shiftEndMin }];
        }));
        return { id: technician.id, name: technician.name, color: technician.color,
          shiftStartMin: technician.shiftStartMin, shiftEndMin: technician.shiftEndMin, days } satisfies ScheduleTechnician;
      })}
      appointments={scheduleAppointments}
      absences={absences}
    />
  </>;
}
