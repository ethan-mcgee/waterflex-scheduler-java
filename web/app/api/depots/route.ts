import { NextRequest, NextResponse } from "next/server";
import { depotSetup, readBody } from "@/lib/contracts";
import { searchAddress } from "@/lib/geocode";
import { prisma } from "@/lib/prisma";

export async function POST(request: NextRequest) {
  const parsed = await readBody(request, depotSetup);
  if (!parsed.success) return NextResponse.json({ error: "Invalid depot setup" }, { status: 400 });
  const input = parsed.data;
  const [metro, dealership] = await Promise.all([
    prisma.metro.findUnique({ where: { id: input.metroId } }),
    prisma.dealership.findUnique({ where: { id: input.dealershipId } }),
  ]);
  if (!metro || !dealership) return NextResponse.json({ error: "Metro or dealership not found" }, { status: 404 });
  const candidates = await searchAddress(input.address);
  const pin = candidates.find(candidate => Math.abs(candidate.lat - input.confirmedPin.lat) < 0.0001 &&
    Math.abs(candidate.lng - input.confirmedPin.lng) < 0.0001);
  if (!pin) return NextResponse.json({ error: "Choose a verified depot pin" }, { status: 422 });
  const depot = await prisma.depot.create({ data: { metroId: metro.id, dealershipId: dealership.id, name: input.name, lat: pin.lat, lng: pin.lng,
    endpointPolicies: { create: { effectiveDate: new Date("1900-01-01T00:00:00Z"), departure: input.departure, returnTo: input.returnTo } } }, select: { id: true } });
  return NextResponse.json({ success: true, id: depot.id }, { status: 201 });
}
