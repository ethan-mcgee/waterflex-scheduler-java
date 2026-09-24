import { Prisma } from "@prisma/client";
import { z } from "zod";
import { validatePurgeRoutes } from "./engineClient";
import { currentRouteTiming } from "./currentRouteTiming";

export type TechnicianDay = { technicianId: string; serviceDate: Date };

/** Hold the same job/day locks as booking before checking pending arrangement dependencies. */
export async function lockPurgeDays(tx: Prisma.TransactionClient, jobIds: string[]): Promise<TechnicianDay[]> {
  if (jobIds.length === 0) return [];
  await tx.$queryRaw(Prisma.sql`SELECT id FROM job WHERE id IN (${Prisma.join(jobIds)}) ORDER BY id FOR UPDATE`);
  const [appointments, holds] = await Promise.all([
    tx.appointment.findMany({ where: { jobId: { in: jobIds } }, select: { technicianId: true, serviceDate: true } }),
    tx.slotHold.findMany({ where: { jobId: { in: jobIds } }, select: { technicianId: true, serviceDate: true } }),
  ]);
  const days = [...new Map([...appointments, ...holds].map(day => [`${day.technicianId}|${day.serviceDate.toISOString()}`, day])).values()]
    .sort((left, right) => left.technicianId.localeCompare(right.technicianId) || left.serviceDate.getTime() - right.serviceDate.getTime());
  const technicians = [...new Set(days.map(day => day.technicianId))];
  if (technicians.length) {
    await tx.$queryRaw(Prisma.sql`SELECT id FROM technician WHERE id IN (${Prisma.join(technicians)}) ORDER BY id FOR SHARE`);
    await tx.$queryRaw`SELECT key FROM omaha_setting ORDER BY key FOR SHARE`;
  }
  for (const day of days) {
    await tx.scheduleDay.upsert({ where: { technicianId_serviceDate: day }, update: {}, create: day });
    await tx.$queryRaw`SELECT version FROM schedule_day WHERE "technicianId"=${day.technicianId} AND "serviceDate"=${day.serviceDate} FOR UPDATE`;
  }
  return days;
}

/** Validate before deletion while locks keep the read-only scheduler proposal current. */
export async function preparePurgeRoutes(tx: Prisma.TransactionClient, jobIds: string[], days: TechnicianDay[]) {
  if (!days.length) return [];
  const prepared = await validatePurgeRoutes(jobIds, days.map(day => ({ ...day, serviceDate: day.serviceDate.toISOString().slice(0, 10) })));
  if (prepared.length !== days.length) throw new Error("Purge route coverage changed.");
  const seen = new Set<string>();
  for (const route of prepared) {
    const day = days.find(day => day.technicianId === route.technicianId && day.serviceDate.toISOString().slice(0, 10) === route.serviceDate);
    const key = `${route.technicianId}|${route.serviceDate}`;
    if (!day || seen.has(key)) throw new Error("Invalid purge route coverage.");
    seen.add(key);
    const current = await tx.scheduleDay.findUniqueOrThrow({ where: { technicianId_serviceDate: day } });
    const survivors = await tx.appointment.findMany({ where: { ...day, cancelledAt: null, jobId: { notIn: jobIds } }, select: { id: true, windowStart: true, windowEnd: true, job: { select: { durationMin: true } } } });
    if (current.version !== route.version || survivors.length !== route.stops.length || new Set(route.stops.map(stop => stop.id)).size !== survivors.length)
      throw new Error("Purge schedule changed.");
    for (const stop of route.stops) {
      const survivor = survivors.find(item => item.id === stop.id);
      const start = new Date(stop.plannedStart), end = new Date(stop.plannedEnd);
      if (!survivor || start < survivor.windowStart || start >= survivor.windowEnd || end.getTime() - start.getTime() !== survivor.job.durationMin * 60000)
        throw new Error("Invalid surviving appointment timing.");
    }
    const timing = { format: 1, scheduleVersion: route.version, routingIdentity: route.routingIdentity, segments: route.segments };
    if (currentRouteTiming(timing, route.version, route.stops.map(stop => ({ id: stop.id, plannedStart: new Date(stop.plannedStart), plannedEnd: new Date(stop.plannedEnd) }))).status !== "AVAILABLE")
      throw new Error("Invalid surviving route segments.");
  }
  return prepared;
}

export async function applyPurgeRoutes(tx: Prisma.TransactionClient, routes: Awaited<ReturnType<typeof preparePurgeRoutes>>) {
  for (const route of routes) {
    for (const [sequence, stop] of route.stops.entries()) await tx.appointment.update({ where: { id: stop.id },
      data: { sequence, plannedStart: new Date(stop.plannedStart), plannedEnd: new Date(stop.plannedEnd) } });
    const updated = await tx.scheduleDay.updateMany({ where: { technicianId: route.technicianId, serviceDate: new Date(`${route.serviceDate}T00:00:00Z`), version: route.version },
      data: { version: { increment: 1 }, routeTiming: { format: 1, scheduleVersion: route.version + 1, routingIdentity: route.routingIdentity, segments: route.segments } } });
    if (updated.count !== 1) throw new Error("Purge schedule changed before apply.");
  }
}

export async function purgeHasReservations(tx: Prisma.TransactionClient, jobIds: string[], days: TechnicianDay[]): Promise<boolean> {
  if (jobIds.length === 0) return false;
  const conditions = [Prisma.sql`"jobId" IN (${Prisma.join(jobIds)})`, ...days.map(day =>
    Prisma.sql`("technicianId"=${day.technicianId} AND "serviceDate"=${day.serviceDate})`)];
  const rows = z.array(z.object({ present: z.boolean() })).length(1).parse(await tx.$queryRaw(Prisma.sql`
    SELECT EXISTS(SELECT 1 FROM reservation_obligation WHERE "releasedAt" IS NULL AND "expiresAt">clock_timestamp()
      AND (${Prisma.join(conditions, " OR ")})) AS present
  `));
  return rows.some(row => row.present);
}
