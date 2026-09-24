import { date as dateContract } from "@/lib/contracts";
import { prisma } from "@/lib/prisma";
import { tomorrowInTz } from "@/lib/date";
import DispatchBoard from "@/app/dispatch/DispatchBoard";
import Link from "next/link";
import { currentRouteTiming } from "@/lib/currentRouteTiming";

export const dynamic = "force-dynamic";

export default async function DispatchPage({
  searchParams,
}: {
  searchParams: { date?: string; run?: string; metroId?: string };
}) {
  const metros = await prisma.metro.findMany({ orderBy: { name: "asc" } });
  const metro = searchParams.metroId ? metros.find(item => item.id === searchParams.metroId) : metros[0];
  if (!metro) {
    return (
      <main style={{ padding: "2rem", fontFamily: "system-ui, sans-serif" }}>
        <p>{searchParams.metroId ? "Selected metro is unavailable." : "No metro configured yet."}</p>
      </main>
    );
  }

  const date = searchParams.date ?? tomorrowInTz(metro.timezone);
  if (!dateContract.safeParse(date).success) return <main><p role="alert">Invalid dispatch date.</p></main>;
  const dayStart = new Date(`${date}T00:00:00.000Z`);
  const dayEnd = new Date(dayStart.getTime() + 24 * 60 * 60 * 1000);

  const candidates = await prisma.technician.findMany({
    where: { active: true },
    include: { depotAssignments: { where: { effectiveDate: { lte: dayStart } }, include: { depot: { select: { metroId: true } } }, orderBy: { effectiveDate: "desc" }, take: 1 } },
    orderBy: { name: "asc" },
  });
  const technicians = candidates.filter(tech => tech.depotAssignments[0]?.depot.metroId === metro.id);

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

  const scheduleDays = await prisma.scheduleDay.findMany({ where: { technicianId: { in: technicians.map(tech => tech.id) }, serviceDate: dayStart } });
  const timingByTechnician = new Map(scheduleDays.map(day => [day.technicianId, day]));
  const boardTechnicians = technicians.map((t) => ({
    id: t.id,
    name: t.name,
    color: t.color,
    homeLat: t.homeLat,
    homeLng: t.homeLng,
    shiftStartMin: t.shiftStartMin,
    shiftEndMin: t.shiftEndMin,
    routeTiming: currentRouteTiming(timingByTechnician.get(t.id)?.routeTiming ?? null, timingByTechnician.get(t.id)?.version ?? null,
      appointments.filter(appointment => appointment.technicianId === t.id)),
  }));

  const boardAppointments = appointments
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
      lat: a.job.address.lat,
      lng: a.job.address.lng,
    }));

  return <>
    {metros.length > 1 && <nav aria-label="Dispatch metro" style={{ display: "flex", gap: 12, padding: 16 }}>
      {metros.map(item => <Link key={item.id} href={`/dispatch?metroId=${encodeURIComponent(item.id)}&date=${date}`} aria-current={item.id === metro.id ? "page" : undefined}>{item.name}</Link>)}
    </nav>}
    <DispatchBoard
      metroId={metro.id}
      timezone={metro.timezone}
      date={date}
      technicians={boardTechnicians}
      appointments={boardAppointments}
      initialRunId={searchParams.run}
    />
  </>;
}
