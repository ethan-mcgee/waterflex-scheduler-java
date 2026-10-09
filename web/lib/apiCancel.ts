import { randomUUID } from "node:crypto";
import type { Prisma } from "@prisma/client";
import { prisma } from "./prisma";
import { STORED_MINUTES_TIME_ZONE } from "./clientSnapshot";
import { frozen } from "./apiTimeOffCore";
import { StaleReceipt } from "./apiReceipt";
import { retimeAndWrite } from "./apiRetime";
import { ChangeRefused, RouteRefused } from "./apiMasterDataCore";

/**
 * Cancelling an appointment when the portal schedules through the public API, as the scheduler's own path does: the
 * appointment and its job are cancelled, and before the day's 6 a.m. cutoff the technician's remaining visits that day
 * are re-timed by the scheduler (POST /api/v1/routes/evaluate) and renumbered, in the same write. A frozen day keeps
 * its planned times. A cancellation the remaining route cannot absorb is refused and nothing is written.
 */

const ZONE = STORED_MINUTES_TIME_ZONE;
const day = (value: string) => new Date(`${value}T00:00:00Z`);
const dateKey = (value: Date) => value.toISOString().slice(0, 10);

export const MAX_REASON_LENGTH = 500;

export interface Cancelled { success: true; appointmentId: string; alreadyCancelled: boolean }

/** Locks the technician-day the way receipts do, so a booking or re-timing of the same day waits for the cancellation. */
async function lockDay(tx: Prisma.TransactionClient, technicianId: string, serviceDate: string): Promise<void> {
  await tx.$executeRaw`INSERT INTO schedule_day (id, "technicianId", "serviceDate", version) VALUES (${randomUUID()}, ${technicianId}, ${day(serviceDate)}, 0)
    ON CONFLICT ("technicianId", "serviceDate") DO NOTHING`;
  await tx.$queryRaw`SELECT version FROM schedule_day WHERE "technicianId" = ${technicianId} AND "serviceDate" = ${day(serviceDate)} FOR UPDATE`;
}

/** The technician's other live appointments that day, as the one booked day the cancellation re-times, or none. */
async function remainingDay(db: Prisma.TransactionClient, appointmentId: string, technicianId: string, serviceDate: string): Promise<Map<string, string[]>> {
  const others = await db.appointment.count({ where: { technicianId, serviceDate: day(serviceDate), cancelledAt: null, id: { not: appointmentId } } });
  return others === 0 ? new Map() : new Map([[serviceDate, [technicianId]]]);
}

/** The metro the technician works in on the date, from their depot assignment effective then. */
async function metroOn(technicianId: string, serviceDate: string): Promise<string> {
  const assignment = await prisma.technicianDepotAssignment.findFirst({ where: { technicianId, effectiveDate: { lte: day(serviceDate) } },
    orderBy: { effectiveDate: "desc" }, select: { depot: { select: { metroId: true } } } });
  if (assignment === null) throw new ChangeRefused(409, `Technician ${technicianId} has no depot assignment on ${serviceDate}`);
  return assignment.depot.metroId;
}

export async function cancelApiAppointment(clientId: string, appointmentId: string, reason: string): Promise<Cancelled> {
  const trimmed = reason.trim();
  if (trimmed.length === 0 || trimmed.length > MAX_REASON_LENGTH) throw new ChangeRefused(400, "Cancellation reason required");
  const appointment = await prisma.appointment.findFirst({ where: { id: appointmentId, job: { customer: { clientId } } },
    select: { id: true, jobId: true, technicianId: true, serviceDate: true, cancelledAt: true } });
  if (appointment === null) throw new ChangeRefused(404, "Appointment not found");
  if (appointment.cancelledAt !== null) return { success: true, appointmentId, alreadyCancelled: true };
  const { jobId, technicianId } = appointment;
  const serviceDate = dateKey(appointment.serviceDate);

  let alreadyCancelled = false;
  const cancel = async (tx: Prisma.TransactionClient, retimed: boolean) => {
    // A cancellation that landed meanwhile is the same request answered; nothing else is written.
    const current = await tx.appointment.findUniqueOrThrow({ where: { id: appointmentId }, select: { cancelledAt: true } });
    if (current.cancelledAt !== null) {
      if (retimed) throw new StaleReceipt("The appointment was cancelled meanwhile");
      alreadyCancelled = true;
      return;
    }
    // Re-timed planned times are written only while the day is still open; once frozen it keeps them.
    if (retimed && frozen(serviceDate, new Date(), ZONE)) throw new ChangeRefused(409, "Today's route passed the 6 a.m. cutoff while the cancellation was checked. Try again.");
    await tx.appointment.update({ where: { id: appointmentId }, data: { cancelledAt: new Date(), cancellationReason: trimmed } });
    await tx.job.update({ where: { id: jobId }, data: { status: "CANCELLED" } });
  };

  if (frozen(serviceDate, new Date(), ZONE)) {
    await prisma.$transaction(async tx => {
      await lockDay(tx, technicianId, serviceDate);
      await cancel(tx, false);
      if (!alreadyCancelled)
        await tx.$executeRaw`UPDATE schedule_day SET version = version + 1 WHERE "technicianId" = ${technicianId} AND "serviceDate" = ${day(serviceDate)}`;
    });
    return { success: true, appointmentId, alreadyCancelled };
  }

  const affected = await remainingDay(prisma, appointmentId, technicianId, serviceDate);
  const recheck = async (tx: Prisma.TransactionClient) => {
    await lockDay(tx, technicianId, serviceDate);
    return remainingDay(tx, appointmentId, technicianId, serviceDate);
  };
  try {
    await retimeAndWrite(clientId, await metroOn(technicianId, serviceDate), affected,
      facts => ({ ...facts, appointments: facts.appointments.filter(item => item.id !== appointmentId) }),
      recheck, async tx => {
        await cancel(tx, affected.size > 0);
        if (affected.size === 0 && !alreadyCancelled)
          await tx.$executeRaw`UPDATE schedule_day SET version = version + 1 WHERE "technicianId" = ${technicianId} AND "serviceDate" = ${day(serviceDate)}`;
      });
  } catch (error) {
    // The scheduler's own path names this refusal for what it is: the rest of the day needs a repair, not a re-timing.
    if (error instanceof RouteRefused)
      throw new ChangeRefused(409, "Cancellation requires repair of remaining appointments or reservations");
    throw error;
  }
  return { success: true, appointmentId, alreadyCancelled };
}
