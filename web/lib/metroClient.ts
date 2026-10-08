import type { Prisma } from "@prisma/client";

/**
 * The one client whose depots serve a metro: the owner of anything created for the metro outside a client request
 * (fake data, booking test runs). Throws when the metro has no depot or is shared by several clients, rather than
 * guessing an owner.
 */
export async function metroClientId(db: Prisma.TransactionClient, metroId: string): Promise<string> {
  const owners = await db.dealership.findMany({ where: { depots: { some: { metroId } } }, select: { clientId: true }, distinct: ["clientId"], take: 2 });
  const [owner, other] = owners;
  if (owner === undefined) throw new Error(`Metro ${metroId} has no depot, so it has no client.`);
  if (other !== undefined) throw new Error(`Metro ${metroId} is served by more than one client, so it has no single owner.`);
  return owner.clientId;
}
