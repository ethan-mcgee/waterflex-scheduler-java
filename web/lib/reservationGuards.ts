import { Prisma } from "@prisma/client";
import { z } from "zod";

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
  for (const day of days) {
    await tx.scheduleDay.upsert({ where: { technicianId_serviceDate: day }, update: {}, create: day });
    await tx.$queryRaw`SELECT version FROM schedule_day WHERE "technicianId"=${day.technicianId} AND "serviceDate"=${day.serviceDate} FOR UPDATE`;
  }
  return days;
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
