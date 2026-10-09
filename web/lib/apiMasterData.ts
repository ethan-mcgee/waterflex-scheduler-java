import type { Prisma } from "@prisma/client";
import { prisma } from "./prisma";
import { STORED_MINUTES_TIME_ZONE, type DepotFacts } from "./clientSnapshot";
import { frozen } from "./apiTimeOffCore";
import { bookingHorizon } from "./apiBookingCore";
import { StaleReceipt } from "./apiReceipt";
import { bookedDays, retimeAndWrite } from "./apiRetime";
import { addCalendarDays, todayInTz } from "./date";
import { ChangeRefused, policyEffectiveDate, withAssignment, withDepotLocation, withPolicy, type Anchor } from "./apiMasterDataCore";

/**
 * Changing a client's depots and technician depot assignments when the portal schedules through the public API. Booked
 * days that the change affects are timed by the scheduler as they would be after it (POST /api/v1/routes/evaluate), and
 * the change is written together with the new planned times, compared against the technician-days it was timed from. A
 * change that would make a booked day infeasible is refused and nothing is written, as the scheduler's own path does.
 */

export { ChangeRefused } from "./apiMasterDataCore";

const ZONE = STORED_MINUTES_TIME_ZONE;
const day = (value: string) => new Date(`${value}T00:00:00Z`);
const dateKey = (value: Date) => value.toISOString().slice(0, 10);

/** The technicians whose effective depot on each booked date is `depotId`, among the depot's technicians. */
async function depotBookedDays(db: Prisma.TransactionClient, depotId: string, from: string): Promise<Map<string, string[]>> {
  const assigned = await db.technicianDepotAssignment.findMany({ where: { depotId }, select: { technicianId: true }, distinct: ["technicianId"] });
  const candidates = await bookedDays(db, assigned.map(item => item.technicianId), from);
  const result = new Map<string, string[]>();
  for (const [date, technicianIds] of candidates) {
    const working: string[] = [];
    for (const technicianId of technicianIds) {
      const effective = await db.technicianDepotAssignment.findFirst({ where: { technicianId, effectiveDate: { lte: day(date) } },
        orderBy: { effectiveDate: "desc" }, select: { depotId: true } });
      if (effective?.depotId === depotId) working.push(technicianId);
    }
    if (working.length > 0) result.set(date, working);
  }
  return result;
}

function todayAndFrozen(): { today: string; todayFrozen: boolean } {
  const now = new Date();
  const today = todayInTz(ZONE, now);
  return { today, todayFrozen: frozen(today, now, ZONE) };
}

/** Checked again as the change is written: today's routes freeze at 6 a.m. even while a change is being checked. */
function refuseFrozen(date: string, touchesDate: boolean): void {
  if (touchesDate && frozen(date, new Date(), ZONE)) throw new ChangeRefused(409, "Today's route passed the 6 a.m. cutoff");
}

async function depotOf(clientId: string, depotId: string) {
  const depot = await prisma.depot.findFirst({ where: { id: depotId, dealership: { clientId } }, select: { id: true, metroId: true, dealershipId: true, lat: true, lng: true } });
  if (depot === null) throw new ChangeRefused(404, "Depot not found");
  return depot;
}

/** Changes a depot's route endpoints from the scheduler's effective date on, re-timing the booked days that use them. */
export async function setApiDepotPolicy(clientId: string, depotId: string, departure: Anchor, returnTo: Anchor): Promise<{ success: true; effectiveDate: string }> {
  const depot = await depotOf(clientId, depotId);
  const { today, todayFrozen } = todayAndFrozen();
  const next = await prisma.depotEndpointPolicy.findFirst({ where: { depotId, effectiveDate: { gt: day(today) } }, orderBy: { effectiveDate: "asc" }, select: { effectiveDate: true } });
  const effectiveDate = policyEffectiveDate(today, todayFrozen, next === null ? null : dateKey(next.effectiveDate));
  const affected = await depotBookedDays(prisma, depotId, effectiveDate);
  await retimeAndWrite(clientId, depot.metroId, affected, facts => withPolicy(facts, depotId, { effectiveDate, departure, returnTo }),
    tx => depotBookedDays(tx, depotId, effectiveDate),
    async tx => {
      refuseFrozen(effectiveDate, effectiveDate === today);
      await tx.depotEndpointPolicy.upsert({ where: { depotId_effectiveDate: { depotId, effectiveDate: day(effectiveDate) } },
        create: { depotId, effectiveDate: day(effectiveDate), departure, returnTo }, update: { departure, returnTo } });
    });
  return { success: true, effectiveDate };
}

export interface DepotLocation { address: { line1: string; city: string; state: string; postalCode: string }; confirmedPin: { lat: number; lng: number };
  candidate: { lat: number; lng: number; precision: string } }

/** Renames a depot and, with a confirmed new location, moves it, re-timing every booked day from today that starts or ends there. */
export async function setApiDepotDetails(clientId: string, depotId: string, name: string, location: DepotLocation | null): Promise<{ success: true }> {
  const depot = await depotOf(clientId, depotId);
  if (location === null) {
    await prisma.depot.update({ where: { id: depotId }, data: { name } });
    return { success: true };
  }
  const { today, todayFrozen } = todayAndFrozen();
  const affected = await depotBookedDays(prisma, depotId, today);
  if (todayFrozen && affected.has(today)) throw new ChangeRefused(409, "Today's route passed the 6 a.m. cutoff");
  await retimeAndWrite(clientId, depot.metroId, affected, facts => withDepotLocation(facts, depotId, location.confirmedPin),
    tx => depotBookedDays(tx, depotId, today),
    async tx => {
      refuseFrozen(today, (await depotBookedDays(tx, depotId, today)).has(today));
      await tx.depot.update({ where: { id: depotId }, data: { name, lat: location.confirmedPin.lat, lng: location.confirmedPin.lng,
        addressLine1: location.address.line1, addressCity: location.address.city, addressState: location.address.state, addressPostalCode: location.address.postalCode,
        geocodeLat: location.candidate.lat, geocodeLng: location.candidate.lng, geocodePrecision: location.candidate.precision, pinConfirmedAt: new Date() } });
    });
  return { success: true };
}

/**
 * Moves a technician to another depot of the same dealership from `effectiveDate` on. Within a metro the booked days
 * from then on are re-timed from the new depot; a move to another metro must start after the client's booking horizon
 * and after the technician's last booked day, as the scheduler requires.
 */
export async function assignApiTechnicianDepot(clientId: string, technicianId: string, depotId: string, effectiveDate: string): Promise<{ success: true }> {
  const { today, todayFrozen } = todayAndFrozen();
  if (effectiveDate < (todayFrozen ? addCalendarDays(today, 1) : today)) throw new ChangeRefused(409, "Assignment date is frozen");
  const technician = await prisma.technician.findFirst({ where: { id: technicianId, clientId }, select: { id: true } });
  if (technician === null) throw new ChangeRefused(404, "Technician not found");
  const target = await depotOf(clientId, depotId);
  const prior = await prisma.technicianDepotAssignment.findFirst({ where: { technicianId, effectiveDate: { lte: day(effectiveDate) } }, orderBy: { effectiveDate: "desc" },
    select: { depot: { select: { id: true, metroId: true, dealershipId: true } } } });
  if (prior === null) throw new ChangeRefused(409, "Current depot assignment is missing");
  if (prior.depot.dealershipId !== target.dealershipId) throw new ChangeRefused(409, "Depot must belong to the technician's dealership");
  if (prior.depot.id === target.id) return { success: true };
  if (await prisma.technicianDepotAssignment.count({ where: { technicianId, effectiveDate: { gte: day(effectiveDate) } } }) > 0)
    throw new ChangeRefused(409, "A later depot assignment already exists");
  const crossMetro = prior.depot.metroId !== target.metroId;
  const insert = async (tx: Prisma.TransactionClient) => {
    if (effectiveDate === today && frozen(today, new Date(), ZONE)) throw new ChangeRefused(409, "Assignment date is frozen");
    if (await tx.technicianDepotAssignment.count({ where: { technicianId, effectiveDate: { gte: day(effectiveDate) } } }) > 0)
      throw new StaleReceipt("A later depot assignment was added meanwhile");
    await tx.technicianDepotAssignment.create({ data: { technicianId, depotId, effectiveDate: day(effectiveDate) } });
  };
  const ownDays = (db: Prisma.TransactionClient) => bookedDays(db, [technicianId], effectiveDate);
  if (crossMetro) {
    const settings = await prisma.clientSolverSettings.findUnique({ where: { clientId }, select: { bookingHorizonWeekdays: true } });
    // Without settings the client has never been offered a time through the API, so no offer can depend on this depot.
    const horizonEnd = settings === null ? null : bookingHorizon(today, settings.bookingHorizonWeekdays).at(-1);
    if (horizonEnd !== undefined && horizonEnd !== null && effectiveDate <= horizonEnd)
      throw new ChangeRefused(409, "Cross-metro moves must start after the current booking horizon");
    if ((await ownDays(prisma)).size > 0) throw new ChangeRefused(409, "Booked appointments conflict with the metro move");
    await retimeAndWrite(clientId, target.metroId, new Map(), facts => facts, ownDays, insert);
    return { success: true };
  }
  const targetFacts = await prisma.depot.findUniqueOrThrow({ where: { id: depotId }, select: { id: true, metroId: true, lat: true, lng: true,
    endpointPolicies: { select: { effectiveDate: true, departure: true, returnTo: true } } } });
  const moved: DepotFacts = { id: targetFacts.id, metroId: targetFacts.metroId, lat: targetFacts.lat, lng: targetFacts.lng,
    policies: targetFacts.endpointPolicies.map(policy => ({ effectiveDate: dateKey(policy.effectiveDate), departure: policy.departure, returnTo: policy.returnTo })) };
  await retimeAndWrite(clientId, target.metroId, await ownDays(prisma), facts => withAssignment(facts, technicianId, effectiveDate, moved), ownDays, insert);
  return { success: true };
}
