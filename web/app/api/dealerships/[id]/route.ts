import { NextRequest, NextResponse } from "next/server";
import { dealershipDetails, readBody } from "@/lib/contracts";
import { activeClient } from "@/lib/activeClient";
import { prisma } from "@/lib/prisma";

export async function PATCH(request: NextRequest, { params }: { params: Promise<{ id: string }> }) {
  const parsed = await readBody(request, dealershipDetails);
  if (!parsed.success) return NextResponse.json({ error: "Invalid dealership details" }, { status: 400 });
  const { id } = await params;
  const client = await activeClient(request);
  const result = await prisma.dealership.updateMany({ where: { id, clientId: client.id }, data: { name: parsed.data.name } });
  if (result.count !== 1) return NextResponse.json({ error: "Dealership not found" }, { status: 404 });
  return NextResponse.json({ success: true });
}
