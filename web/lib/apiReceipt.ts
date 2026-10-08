import { randomUUID } from "node:crypto";
import { Prisma } from "@prisma/client";
import { prisma } from "./prisma";
import { sameInstant, type TechnicianDayKey } from "./apiBookingCore";
import type { CommitReceipt } from "./schedulerApi";

/**
 * Writing a scheduler receipt into the portal's tables, shared by booking confirms and daily commits: the portal, as
 * the host, writes the assignments only while every listed technician-day still has the lastModified the scheduler
 * computed from.
 */

export class StaleReceipt extends Error {
  constructor(message: string) { super(message); this.name = "StaleReceipt"; }
}

const day = (value: string) => new Date(`${value}T00:00:00Z`);
const key = (item: TechnicianDayKey) => `${item.technicianId}|${item.serviceDate}`;

/** The current lastModified of technician-days, as exact text. A technician that does not exist is absent from the map. */
export async function currentLastModified(db: Prisma.TransactionClient, days: readonly TechnicianDayKey[]): Promise<Map<string, string>> {
  const rows = await db.$queryRaw<Array<{ technicianId: string; serviceDate: string; lastModified: string }>>(Prisma.sql`
    SELECT t.id AS "technicianId", to_char(d.day, 'YYYY-MM-DD') AS "serviceDate",
      to_char(GREATEST(t."factsChangedAt", c."changedAt") AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"') AS "lastModified"
    FROM unnest(${days.map(item => item.technicianId)}::text[], ${days.map(item => item.serviceDate)}::date[]) AS d(technician, day)
    JOIN technician t ON t.id = d.technician
    LEFT JOIN technician_day_change c ON c."technicianId" = t.id AND c."serviceDate" = d.day::timestamp`);
  return new Map(rows.map(row => [`${row.technicianId}|${row.serviceDate}`, row.lastModified]));
}

export interface ReceiptWrite {
  /** Technician-days whose every live appointment the receipt places; after writing, any other one there is an error. */
  complete: readonly TechnicianDayKey[];
  /** A booking's new appointment, created under the job's ID with the offered window; the job becomes SCHEDULED. */
  create?: { jobId: string; windowStart: Date; windowEnd: Date };
  /** Records the receipt in the same transaction, so the writes and the record of them land together or not at all. */
  record?: (tx: Prisma.TransactionClient) => Promise<void>;
}

/**
 * Locks the receipt's technician-days the way the scheduler does (schedule_day lock rows, then technicians, each in
 * sorted order), checks every lastModified still equals the receipt's, and only then writes every assignment. Any
 * difference throws StaleReceipt and writes nothing. `days` are the receipt's technician-days, sorted.
 */
export async function writeReceipt(clientId: string, receipt: CommitReceipt, days: readonly TechnicianDayKey[], write: ReceiptWrite): Promise<void> {
  await prisma.$transaction(async tx => {
    for (const item of days) {
      await tx.$executeRaw`INSERT INTO schedule_day (id, "technicianId", "serviceDate", version) VALUES (${randomUUID()}, ${item.technicianId}, ${day(item.serviceDate)}, 0)
        ON CONFLICT ("technicianId", "serviceDate") DO NOTHING`;
    }
    for (const item of days)
      await tx.$queryRaw`SELECT version FROM schedule_day WHERE "technicianId" = ${item.technicianId} AND "serviceDate" = ${day(item.serviceDate)} FOR UPDATE`;
    const technicianIds = [...new Set(days.map(item => item.technicianId))].sort();
    const owned = await tx.$queryRaw<Array<{ id: string }>>`SELECT id FROM technician WHERE id IN (${Prisma.join(technicianIds)}) AND "clientId" = ${clientId} ORDER BY id FOR UPDATE`;
    if (owned.length !== technicianIds.length) throw new StaleReceipt("The receipt names a technician that is not this client's");
    const current = await currentLastModified(tx, days);
    for (const listed of receipt.technicianDays) {
      const now = current.get(key(listed));
      if (now === undefined || !sameInstant(now, listed.lastModified))
        throw new StaleReceipt(`Technician-day ${listed.technicianId} ${listed.serviceDate} changed since the snapshot`);
    }
    const created = write.create;
    for (const assignment of receipt.assignments) {
      const placement = { technicianId: assignment.technicianId, serviceDate: day(assignment.serviceDate), sequence: assignment.sequence,
        plannedStart: new Date(assignment.plannedStart), plannedEnd: new Date(assignment.plannedEnd) };
      if (created !== undefined && assignment.appointmentId === created.jobId) {
        const existing = await tx.appointment.findUnique({ where: { jobId: created.jobId }, select: { id: true } });
        if (existing !== null) throw new StaleReceipt(`Job ${created.jobId} already has an appointment`);
        await tx.appointment.create({ data: { id: created.jobId, jobId: created.jobId, windowStart: created.windowStart, windowEnd: created.windowEnd, ...placement } });
        continue;
      }
      const moved = await tx.appointment.updateMany({ where: { id: assignment.appointmentId, cancelledAt: null,
        job: { customer: { clientId } }, serviceDate: day(assignment.serviceDate) }, data: placement });
      if (moved.count !== 1) throw new StaleReceipt(`Appointment ${assignment.appointmentId} is not a current appointment of this client on ${assignment.serviceDate}`);
    }
    if (write.complete.length > 0) {
      const placed = new Set(receipt.assignments.map(item => item.appointmentId));
      const left = await tx.appointment.findMany({ where: { cancelledAt: null, id: { notIn: [...placed] },
        OR: write.complete.map(item => ({ technicianId: item.technicianId, serviceDate: day(item.serviceDate) })) }, select: { id: true }, take: 1 });
      const stray = left[0];
      // Unchanged lastModified means the day's appointments are the snapshot's, so this is a receipt that breaks the contract.
      if (stray !== undefined) throw new Error(`The receipt does not place appointment ${stray.id}, which is on one of its technician-days`);
    }
    for (const item of days)
      await tx.$executeRaw`UPDATE schedule_day SET version = version + 1 WHERE "technicianId" = ${item.technicianId} AND "serviceDate" = ${day(item.serviceDate)}`;
    if (created !== undefined) {
      const scheduled = await tx.job.updateMany({ where: { id: created.jobId, status: "PENDING" }, data: { status: "SCHEDULED" } });
      if (scheduled.count !== 1) throw new StaleReceipt(`Job ${created.jobId} is no longer pending`);
    }
    await write.record?.(tx);
  });
}
