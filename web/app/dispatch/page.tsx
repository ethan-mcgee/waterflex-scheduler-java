import { date as dateContract } from "@/lib/contracts";
import { prisma } from "@/lib/prisma";
import { activeClient } from "@/lib/activeClient";
import { tomorrowInTz } from "@/lib/date";
import { loadSolverSettings } from "@/lib/clientSettingsStore";
import DispatchBoard from "@/app/dispatch/DispatchBoard";

export const dynamic = "force-dynamic";

export default async function DispatchPage({
  searchParams,
}: {
  searchParams: { date?: string; metroId?: string };
}) {
  const clientId = (await activeClient()).id;
  const metros = await prisma.metro.findMany({ where: { depots: { some: { dealership: { clientId } } } }, orderBy: { name: "asc" } });
  // The board shows one metro: the client's first, or `?metroId=`. There is no metro selector.
  // Revise when a client has depots across multiple metros.
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
    where: { clientId, active: true },
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

  // The optimize progress bar fills over the configured search time; without settings it has no length to fill.
  const settings = await loadSolverSettings(clientId);
  const searchSeconds = settings === null ? null : settings.costSearchSeconds + settings.fairnessSearchSeconds;

  const boardTechnicians = technicians.map((t) => ({
    id: t.id,
    name: t.name,
    color: t.color,
    homeLat: t.homeLat,
    homeLng: t.homeLng,
    shiftStartMin: t.shiftStartMin,
    shiftEndMin: t.shiftEndMin,
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
      durationMin: a.job.durationMin,
      geocodePrecision: a.job.address.geocodePrecision,
      pinConfirmedAt: a.job.address.pinConfirmedAt?.toISOString() ?? null,
      jobStatus: a.job.status,
    }));

  // Keyed by metro day, so moving to another day starts the board fresh.
  return <DispatchBoard
    key={`${metro.id}|${date}`}
    metroId={metro.id}
    metroName={metro.name}
    searchSeconds={searchSeconds}
    timezone={metro.timezone}
    date={date}
    technicians={boardTechnicians}
    appointments={boardAppointments}
  />;
}
