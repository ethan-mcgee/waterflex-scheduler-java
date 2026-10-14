import { cookies } from "next/headers";
import type { NextRequest } from "next/server";
import { CLIENT_COOKIE, chooseClient, type ClientSummary } from "./clients";
import { prisma } from "./prisma";

export async function listClients(): Promise<ClientSummary[]> {
  return prisma.client.findMany({ select: { id: true, name: true }, orderBy: [{ name: "asc" }, { id: "asc" }] });
}

/**
 * The client this request acts for (see chooseClient). Every portal read and write is scoped to it. Route handlers pass
 * their request; pages and layouts read the cookie from the request being rendered.
 */
export async function activeClient(request?: NextRequest): Promise<ClientSummary> {
  return (await activeClientAndList(request)).active;
}

/**
 * The active client and every client, from one lookup. The cookie is read before the query so a build-time render
 * stops at cookies() without querying a database it does not have.
 */
export async function activeClientAndList(request?: NextRequest): Promise<{ active: ClientSummary; clients: ClientSummary[] }> {
  const remembered = (request ? request.cookies : await cookies()).get(CLIENT_COOKIE)?.value;
  const clients = await listClients();
  const active = chooseClient(remembered, clients);
  if (active == null) throw new Error("No clients exist; create one with web/scripts/create-client.ts");
  return { active, clients };
}
