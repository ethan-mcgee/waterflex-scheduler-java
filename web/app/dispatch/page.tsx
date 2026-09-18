import { prisma } from "@/lib/prisma";
import { tomorrowInTz } from "@/lib/date";
import DispatchBoard from "@/app/dispatch/DispatchBoard";

export const dynamic = "force-dynamic";

export default async function DispatchPage({
  searchParams,
}: {
  searchParams: { date?: string };
}) {
  const metro = await prisma.metro.findFirst();
  if (!metro) {
    return (
      <main style={{ padding: "2rem", fontFamily: "system-ui, sans-serif" }}>
        <p>No metro configured yet.</p>
      </main>
    );
  }

  const date = searchParams.date ?? tomorrowInTz(metro.timezone);
  const dayStart = new Date(`${date}T00:00:00.000Z`);
  const dayEnd = new Date(dayStart.getTime() + 24 * 60 * 60 * 1000);

  const technicians = await prisma.technician.findMany({
    where: { metroId: metro.id, active: true },
    orderBy: { name: "asc" },
  });

  const appointments = await prisma.appointment.findMany({
    where: {
      technicianId: { in: technicians.map((t) => t.id) },
      serviceDate: { gte: dayStart, lt: dayEnd },
      cancelledAt: null,
    },
    include: {
      job: {
        include: {
          customer: true,
          service: true,
          address: true,
        },
      },
    },
    orderBy: { plannedStart: "asc" },
  });

  const boardTechnicians = technicians.map((t) => ({
    id: t.id,
    name: t.name,
    homeLat: t.homeLat,
    homeLng: t.homeLng,
    shiftStartMin: t.shiftStartMin,
    shiftEndMin: t.shiftEndMin,
  }));

  // address.lat/lng are nullable at the schema level (ungeocoded
  // addresses), but booking always geocodes before creating a job — so an
  // appointment with a null coordinate here would indicate a data
  // integrity problem upstream. Drop it from map rendering rather than
  // crash the board over it.
  const boardAppointments = appointments
    .filter((a) => a.job.address.lat !== null && a.job.address.lng !== null)
    .map((a) => ({
      id: a.id,
      technicianId: a.technicianId,
      sequence: a.sequence,
      windowStart: a.windowStart.toISOString(),
      windowEnd: a.windowEnd.toISOString(),
      plannedStart: a.plannedStart.toISOString(),
      plannedEnd: a.plannedEnd.toISOString(),
      customerName: `${a.job.customer.firstName} ${a.job.customer.lastName}`,
      serviceName: a.job.service.name,
      addressLine: [a.job.address.line1, a.job.address.line2, a.job.address.city].filter(Boolean).join(", "),
      lat: a.job.address.lat as number,
      lng: a.job.address.lng as number,
    }));

  return (
    <><nav style={{ padding: "0.75rem 2rem", display: "flex", gap: "1rem" }}>
      <a href="/dispatch/availability">Shifts and qualifications</a>
      <a href="/dispatch/follow-up">Manual follow-up</a>
      <a href="/time-off">Technician time off</a>
    </nav><DispatchBoard
      metroId={metro.id}
      timezone={metro.timezone}
      date={date}
      technicians={boardTechnicians}
      appointments={boardAppointments}
    /></>
  );
}
