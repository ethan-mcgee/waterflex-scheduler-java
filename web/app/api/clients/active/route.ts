import { z } from "zod";
import { NextRequest, NextResponse } from "next/server";
import { readBody } from "@/lib/contracts";
import { CLIENT_COOKIE, CLIENT_ID } from "@/lib/clients";
import { prisma } from "@/lib/prisma";
import { notFound } from "@/lib/clientScope";

/** Remembers which client the portal acts for. Only an existing client can be chosen. */
export async function POST(request: NextRequest) {
  const parsed = await readBody(request, z.object({ clientId: z.string().regex(CLIENT_ID) }).strict());
  if (!parsed.success) return NextResponse.json({ error: "Invalid client" }, { status: 400 });
  const client = await prisma.client.findUnique({ where: { id: parsed.data.clientId }, select: { id: true, name: true } });
  if (client == null) return notFound("Client");
  const response = NextResponse.json(client);
  response.cookies.set(CLIENT_COOKIE, client.id, { httpOnly: true, sameSite: "lax", path: "/", maxAge: 60 * 60 * 24 * 365 });
  return response;
}
