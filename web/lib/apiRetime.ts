import type { Prisma } from "@prisma/client";
import { prisma } from "./prisma";
import { assembleSnapshot, loadSnapshotFacts, type SnapshotFacts } from "./clientSnapshot";
import { DispatchRefused, refusedFor } from "./apiDispatch";
import { StaleReceipt, writeReceipts } from "./apiReceipt";
import { evaluateRoutes } from "./schedulerApi";
import { ChangeRefused, retimedWrite, type RetimedWrite } from "./apiMasterDataCore";

/**
 * Changing a client's own facts when the portal schedules through the public API: the booked days a change affects
 * are timed by the scheduler as they would be after it (POST /api/v1/routes/evaluate), and the change is written
 * together with the new planned times, compared against the technician-days they were timed from.
 */

const CHANGED = "The schedule changed while this change was checked. Try again.";
const day = (value: string) => new Date(`${value}T00:00:00Z`);
const dateKey = (value: Date) => value.toISOString().slice(0, 10);

/** Booked technician-days (live appointments) on or after `from`, by date. */
export async function bookedDays(db: Prisma.TransactionClient, technicianIds: readonly string[], from: string): Promise<Map<string, string[]>> {
  const rows = technicianIds.length === 0 ? [] : await db.appointment.findMany({ where: { technicianId: { in: [...technicianIds] }, cancelledAt: null,
    serviceDate: { gte: day(from) } }, select: { technicianId: true, serviceDate: true }, distinct: ["technicianId", "serviceDate"] });
  const byDate = new Map<string, string[]>();
  for (const row of rows) byDate.set(dateKey(row.serviceDate), [...(byDate.get(dateKey(row.serviceDate)) ?? []), row.technicianId].sort());
  return byDate;
}

function sameDays(left: ReadonlyMap<string, readonly string[]>, right: ReadonlyMap<string, readonly string[]>): boolean {
  if (left.size !== right.size) return false;
  for (const [date, ids] of left) {
    const other = right.get(date);
    if (other === undefined || other.length !== ids.length || other.some((id, index) => id !== ids[index])) return false;
  }
  return true;
}

/**
 * Writes `change` with the affected booked days re-timed to the changed facts. Inside the write, the affected days are
 * found again: a booking made meanwhile, or any change to a timed technician-day, refuses the change as stale.
 */
export async function retimeAndWrite(clientId: string, metroId: string, affected: Map<string, string[]>, transform: (facts: SnapshotFacts) => SnapshotFacts,
  recheck: (tx: Prisma.TransactionClient) => Promise<Map<string, string[]>>, change: (tx: Prisma.TransactionClient) => Promise<void>): Promise<void> {
  if (affected.size === 0) {
    try {
      await prisma.$transaction(async tx => {
        if ((await recheck(tx)).size > 0) throw new StaleReceipt("A day the change affects was booked meanwhile");
        await change(tx);
      });
    } catch (error) {
      if (error instanceof StaleReceipt) throw new ChangeRefused(409, CHANGED);
      throw error;
    }
    return;
  }
  const dates = [...affected.keys()].sort();
  const writes: RetimedWrite[] = [];
  try {
    const facts = await loadSnapshotFacts(clientId, metroId, dates);
    const changed = transform(facts);
    for (const date of dates) {
      const snapshot = assembleSnapshot({ ...changed, dates: [date] });
      const evaluation = await evaluateRoutes(clientId, { serviceDate: date, snapshot });
      writes.push(retimedWrite(date, affected.get(date) ?? [], snapshot, evaluation));
    }
  } catch (error) {
    if (error instanceof ChangeRefused) throw error;
    try { refusedFor(error); }
    catch (refused) { if (refused instanceof DispatchRefused) throw new ChangeRefused(refused.status, refused.message); throw refused; }
  }
  try {
    // The change lands before the re-timed appointments are written, so a cancelled one is no longer live on its day.
    await writeReceipts(clientId, writes, { before: async tx => {
      if (!sameDays(await recheck(tx), affected)) throw new StaleReceipt("The booked days affected by the change moved");
      await change(tx);
    } });
  } catch (error) {
    if (error instanceof StaleReceipt) throw new ChangeRefused(409, CHANGED);
    throw error;
  }
}
