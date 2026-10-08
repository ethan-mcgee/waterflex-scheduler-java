import type { Prisma } from "@prisma/client";

/**
 * The client whose depots serve a metro. A database trigger keeps each metro to one client until scheduling runs
 * through the public API, so this is the owner of anything created for the metro outside a client request
 * (fake data, booking test runs). Throws when the metro has no depot, rather than guessing an owner.
 */
export async function metroClientId(db: Prisma.TransactionClient, metroId: string): Promise<string> {
  const depot = await db.depot.findFirst({ where: { metroId }, select: { dealership: { select: { clientId: true } } } });
  if (depot == null) throw new Error(`Metro ${metroId} has no depot, so it has no client.`);
  return depot.dealership.clientId;
}
