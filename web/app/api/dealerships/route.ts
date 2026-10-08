import { NextRequest, NextResponse } from "next/server";
import { dealershipSetup, readBody } from "@/lib/contracts";
import { activeClient } from "@/lib/activeClient";
import { prisma } from "@/lib/prisma";

export async function POST(request: NextRequest) {
  const parsed = await readBody(request, dealershipSetup);
  if (!parsed.success) return NextResponse.json({ error: "Invalid dealership setup" }, { status: 400 });
  const input = parsed.data;
  const client = await activeClient(request);
  const dealership = await prisma.dealership.create({ data: { ...input, clientId: client.id }, select: { id: true } });
  return NextResponse.json({ success: true, id: dealership.id }, { status: 201 });
}
