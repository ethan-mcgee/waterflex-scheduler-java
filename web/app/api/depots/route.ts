import { NextRequest, NextResponse } from "next/server";
import { depotSetup, readBody } from "@/lib/contracts";
import { searchAddress } from "@/lib/geocode";
import { prisma } from "@/lib/prisma";

export async function POST(request: NextRequest) {
  const parsed = await readBody(request, depotSetup);
  if (!parsed.success) return NextResponse.json({ error: "Invalid depot setup" }, { status: 400 });
  const input = parsed.data;
  const metro = await prisma.metro.findUnique({ where: { id: input.metroId } });
  if (!metro) return NextResponse.json({ error: "Metro not found" }, { status: 404 });
  const candidates = await searchAddress(input.address);
  const pin = candidates.find(candidate => Math.abs(candidate.lat - input.confirmedPin.lat) < 0.0001 &&
    Math.abs(candidate.lng - input.confirmedPin.lng) < 0.0001);
  if (!pin) return NextResponse.json({ error: "Choose a verified depot pin" }, { status: 422 });
  const depot = await prisma.depot.create({ data: { metroId: metro.id, name: input.name, lat: pin.lat, lng: pin.lng }, select: { id: true } });
  return NextResponse.json({ success: true, id: depot.id }, { status: 201 });
}
