import { NextRequest, NextResponse } from "next/server";
import { dealershipSetup, readBody } from "@/lib/contracts";
import { prisma } from "@/lib/prisma";

export async function POST(request: NextRequest) {
  const parsed = await readBody(request, dealershipSetup);
  if (!parsed.success) return NextResponse.json({ error: "Invalid dealership setup" }, { status: 400 });
  const input = parsed.data;
  const depot = await prisma.depot.findUnique({ where: { id: input.depotId } });
  if (!depot || depot.metroId !== input.metroId)
    return NextResponse.json({ error: "Configure a depot in the selected metro first" }, { status: 422 });
  const dealership = await prisma.dealership.create({ data: { ...input, endpointPolicies: { create: {
    effectiveDate: new Date("1900-01-01T00:00:00Z"), departure: input.departure, returnTo: input.returnTo,
  } } }, select: { id: true } });
  return NextResponse.json({ success: true, id: dealership.id }, { status: 201 });
}
