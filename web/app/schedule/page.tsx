import { date as dateContract } from "@/lib/contracts";
import { prisma } from "@/lib/prisma";
import { addCalendarDays, mondayOfWeek, tomorrowInTz } from "@/lib/date";
import ScheduleView from "./ScheduleView";
import type { ScheduleAbsence, ScheduleAppointment, ScheduleTechnician } from "./types";

export const dynamic = "force-dynamic";

function validDateKey(value: string | undefined): value is string {
  return value !== undefined && dateContract.safeParse(value).success;
}

export default async function SchedulePage({
  searchParams,
}: {
  searchParams: { week?: string };
}) {
  const metro = await prisma.metro.findFirst();
  if (!metro) {
    return (
      <main>
        <p>No metro configured yet.</p>
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
    where: { metroId: metro.id, active: true },
    orderBy: { name: "asc" },
    select: { id: true, name: true, shiftStartMin: true, shiftEndMin: true },
  });

  const technicianIds = technicians.map((technician) => technician.id);
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

  const scheduleAppointments: ScheduleAppointment[] = appointments.map((appointment) => ({
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

  return (
    <ScheduleView
      monday={monday}
      timezone={metro.timezone}
      technicians={technicians satisfies ScheduleTechnician[]}
      appointments={scheduleAppointments}
      absences={absences}
    />
  );
}
