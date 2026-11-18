import { Prisma } from "@prisma/client";
import { prisma } from "./prisma";
import { addCalendarDays } from "./date";

/**
 * The version of what the schedule page shows for a client's week: an opaque text that changes whenever a visit, a
 * shift, time off, a technician or a waiting job changes, whoever changed it. It is built from the stamps the
 * database triggers already keep for the public API's `lastModified` (the later of a technician's facts stamp and
 * each of its days' change stamps), so the portal's, the bookings' and the scheduler's writes all move it. The page
 * compares it with the one it was rendered with and reloads when they differ.
 *
 * The counts are part of it because a stamp alone cannot show a technician or a waiting job that is gone.
 */
export async function scheduleVersion(clientId: string, monday: string): Promise<string> {
  const from = new Date(`${monday}T00:00:00Z`), to = new Date(`${addCalendarDays(monday, 7)}T00:00:00Z`);
  const [rows, jobs] = await Promise.all([
    prisma.$queryRaw<Array<{ technicians: number; changed: string | null }>>(Prisma.sql`
      SELECT count(*)::int AS technicians,
        to_char(max(GREATEST(t."factsChangedAt", c.changed)) AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"') AS changed
      FROM technician t
      LEFT JOIN (SELECT "technicianId", max("changedAt") AS changed FROM technician_day_change
        WHERE "serviceDate" >= ${from} AND "serviceDate" < ${to} GROUP BY "technicianId") c ON c."technicianId" = t.id
      WHERE t."clientId" = ${clientId} AND t.active`),
    prisma.job.aggregate({ where: { status: "PENDING", appointment: null, customer: { clientId } }, _count: true, _max: { updatedAt: true } }),
  ]);
  const row = rows[0];
  if (row === undefined) throw new Error("The schedule version query returned no row");
  // "-" marks a week with no technicians, or no waiting jobs: there is no stamp to show, and none is made up.
  return [row.technicians, row.changed ?? "-", jobs._count, jobs._max.updatedAt?.toISOString() ?? "-"].join("|");
}
