import { NextResponse } from "next/server";
import { prisma } from "./prisma";

/**
 * Whether a row belongs to a client. A route checks every ID it receives before reading, changing or forwarding it, so
 * another client's row is a 404, exactly like a row that does not exist.
 */
export async function ownsDealership(clientId: string, id: string): Promise<boolean> {
  return (await prisma.dealership.count({ where: { id, clientId } })) === 1;
}

export async function ownsDepot(clientId: string, id: string): Promise<boolean> {
  return (await prisma.depot.count({ where: { id, dealership: { clientId } } })) === 1;
}

export async function ownsTechnician(clientId: string, id: string): Promise<boolean> {
  return (await prisma.technician.count({ where: { id, clientId } })) === 1;
}

export async function ownsJob(clientId: string, id: string): Promise<boolean> {
  return (await prisma.job.count({ where: { id, customer: { clientId } } })) === 1;
}

export async function ownsHold(clientId: string, id: string): Promise<boolean> {
  return (await prisma.slotHold.count({ where: { id, job: { customer: { clientId } } } })) === 1;
}

export async function ownsAppointment(clientId: string, id: string): Promise<boolean> {
  return (await prisma.appointment.count({ where: { id, job: { customer: { clientId } } } })) === 1;
}

export async function ownsTimeOffRequest(clientId: string, id: string): Promise<boolean> {
  return (await prisma.timeOffRequest.count({ where: { id, technician: { clientId } } })) === 1;
}

/** A metro is the client's when one of the client's depots is in it; other clients may serve the same metro. */
export async function servesMetro(clientId: string, metroId: string): Promise<boolean> {
  return (await prisma.depot.count({ where: { metroId, dealership: { clientId } } })) > 0;
}

/**
 * The client serves the metro and no other client does. The scheduler's own engine runs cover a whole metro, so a
 * client sees and applies them only in a metro of its own.
 */
export async function servesMetroAlone(clientId: string, metroId: string): Promise<boolean> {
  const [own, others] = await Promise.all([prisma.depot.count({ where: { metroId, dealership: { clientId } } }),
    prisma.depot.count({ where: { metroId, dealership: { clientId: { not: clientId } } } })]);
  return own > 0 && others === 0;
}

export async function ownsOptimizationRun(clientId: string, id: string): Promise<boolean> {
  const run = await prisma.optimizationRun.findUnique({ where: { id }, select: { metroId: true } });
  return run != null && servesMetroAlone(clientId, run.metroId);
}

export function notFound(what: string): NextResponse {
  return NextResponse.json({ error: `${what} not found` }, { status: 404 });
}
