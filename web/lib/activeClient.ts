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
  const remembered = (request ? request.cookies : cookies()).get(CLIENT_COOKIE)?.value;
  const chosen = chooseClient(remembered, await listClients());
  if (chosen == null) throw new Error("No clients exist; create one with web/scripts/create-client.ts");
  return chosen;
}
