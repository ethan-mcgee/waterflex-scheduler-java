import Link from "next/link";
import { prisma } from "@/lib/prisma";
import { addCalendarDays, calendarDateInTz, localMidnightUtc, mondayOfWeek, tomorrowInTz } from "@/lib/date";
import ScheduleView from "./ScheduleView";
import type { ScheduleAppointment, ScheduleTechnician } from "./types";

export const dynamic = "force-dynamic";

function validDateKey(value: string | undefined): value is string {
  return value !== undefined && /^\d{4}-\d{2}-\d{2}$/.test(value);
}

export default async function SchedulePage({
  searchParams,
}: {
  searchParams: { week?: string };
}) {
  const metro = await prisma.metro.findFirst();
  if (!metro) {
    return (
      <main style={{ padding: "2rem", fontFamily: "system-ui, sans-serif" }}>
        <p>No metro configured yet.</p>
        <Link href="/">Return home</Link>
      </main>
    );
  }

  const requestedDate = validDateKey(searchParams.week)
    ? searchParams.week
    : tomorrowInTz(metro.timezone);
  const monday = mondayOfWeek(requestedDate);
  const nextMonday = addCalendarDays(monday, 7);
  const weekStart = localMidnightUtc(monday, metro.timezone);
  const weekEnd = localMidnightUtc(nextMonday, metro.timezone);

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

  const scheduleAppointments: ScheduleAppointment[] = appointments.map((appointment) => ({
    id: appointment.id,
    technicianId: appointment.technicianId,
    technicianName: appointment.technician.name,
    date: calendarDateInTz(appointment.serviceDate, metro.timezone),
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
    />
  );
}
