import type { Prisma } from "@prisma/client";
import { prisma } from "./prisma";
import { STORED_MINUTES_TIME_ZONE } from "./clientSnapshot";
import { frozen } from "./apiTimeOffCore";
import { retimeAndWrite } from "./apiRetime";
import { ChangeRefused } from "./apiMasterDataCore";

/**
 * Removing generated jobs (fake data, booking test runs) when the portal schedules through the public API. The jobs,
 * their appointments, customers and addresses are deleted together, and each open day where other visits remain is
 * re-timed without them by the scheduler (POST /api/v1/routes/evaluate) in the same write. A frozen day keeps its
 * planned times. A day the remaining visits could not hold refuses the purge and deletes nothing.
 */

const ZONE = STORED_MINUTES_TIME_ZONE;
const dateKey = (value: Date) => value.toISOString().slice(0, 10);

/** The open booked days the purge re-times: dates whose technicians keep other live visits there, by date. */
async function survivingDays(db: Prisma.TransactionClient, jobIds: readonly string[]): Promise<Map<string, string[]>> {
  const purged = await db.appointment.findMany({ where: { jobId: { in: [...jobIds] }, cancelledAt: null }, select: { technicianId: true, serviceDate: true },
    distinct: ["technicianId", "serviceDate"] });
  const now = new Date();
  const byDate = new Map<string, string[]>();
  for (const day of purged) {
    const date = dateKey(day.serviceDate);
    if (frozen(date, now, ZONE)) continue;
    const others = await db.appointment.count({ where: { technicianId: day.technicianId, serviceDate: day.serviceDate, cancelledAt: null, jobId: { notIn: [...jobIds] } } });
    if (others > 0) byDate.set(date, [...(byDate.get(date) ?? []), day.technicianId].sort());
  }
  return byDate;
}

/** Deletes the client's jobs and everything generated for them, re-timing the open days they leave. Returns the appointments removed. */
export async function purgeApiJobs(clientId: string, metroId: string, jobIds: readonly string[]): Promise<number> {
  if (jobIds.length === 0) return 0;
  const jobs = await prisma.job.findMany({ where: { id: { in: [...jobIds] } }, select: { id: true, customerId: true, addressId: true, customer: { select: { clientId: true } } } });
  if (jobs.length !== jobIds.length || jobs.some(job => job.customer.clientId !== clientId)) throw new ChangeRefused(404, "A job to remove is not this client's");
  const purgedAppointments = new Set((await prisma.appointment.findMany({ where: { jobId: { in: [...jobIds] } }, select: { id: true } })).map(item => item.id));
  const ids = jobs.map(job => job.id);
  const customerIds = [...new Set(jobs.map(job => job.customerId))], addressIds = [...new Set(jobs.map(job => job.addressId))];
  await retimeAndWrite(clientId, metroId, await survivingDays(prisma, ids),
    facts => ({ ...facts, appointments: facts.appointments.filter(item => !purgedAppointments.has(item.id)) }),
    tx => survivingDays(tx, ids),
    async tx => {
      const appointmentIds = (await tx.appointment.findMany({ where: { jobId: { in: ids } }, select: { id: true } })).map(item => item.id);
      await tx.outboundEvent.deleteMany({ where: { aggregateId: { in: [...ids, ...appointmentIds, ...customerIds] } } });
      await tx.appointment.deleteMany({ where: { jobId: { in: ids } } });
      // The scheduler's own booking records hold nothing for API-mode jobs; they are cleared only in case a job predates API mode.
      await tx.slotHold.deleteMany({ where: { jobId: { in: ids } } });
      await tx.bookingOffer.deleteMany({ where: { jobId: { in: ids } } });
      await tx.bookingOfferSet.deleteMany({ where: { jobId: { in: ids } } });
      await tx.bookingOptimization.deleteMany({ where: { jobId: { in: ids } } });
      await tx.job.deleteMany({ where: { id: { in: ids } } });
      await tx.address.deleteMany({ where: { id: { in: addressIds }, jobs: { none: {} } } });
      await tx.customer.deleteMany({ where: { id: { in: customerIds }, jobs: { none: {} } } });
    });
  return purgedAppointments.size;
}
