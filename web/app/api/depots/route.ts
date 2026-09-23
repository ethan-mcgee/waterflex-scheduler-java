import { NextRequest, NextResponse } from "next/server";
import { depotSetup, readBody } from "@/lib/contracts";
import { GeocoderError, searchAddress } from "@/lib/geocode";
import { prisma } from "@/lib/prisma";
import { nearbyCandidate } from "@/lib/depotPin";

export async function POST(request: NextRequest) {
  const parsed = await readBody(request, depotSetup);
  if (!parsed.success) return NextResponse.json({ error: "Invalid depot setup" }, { status: 400 });
  const input = parsed.data;
  const [metro, dealership] = await Promise.all([
    prisma.metro.findUnique({ where: { id: input.metroId } }),
    prisma.dealership.findUnique({ where: { id: input.dealershipId } }),
  ]);
  if (!metro || !dealership) return NextResponse.json({ error: "Metro or dealership not found" }, { status: 404 });
  let candidates;
  try { candidates = await searchAddress(input.address); }
  catch (error) {
    if (!(error instanceof GeocoderError)) throw error;
    return NextResponse.json({ error: error.message }, { status: error.kind === "timeout" ? 504 : error.kind === "malformed" ? 502 : 503 });
  }
  const candidate = nearbyCandidate(input.confirmedPin, candidates);
  if (!candidate) return NextResponse.json({ error: "Pin must be within 250 meters of the located address. Confirm the address and pin again." }, { status: 422 });
  const depot = await prisma.depot.create({ data: { metroId: metro.id, dealershipId: dealership.id, name: input.name,
    lat: input.confirmedPin.lat, lng: input.confirmedPin.lng,
    addressLine1: input.address.line1, addressCity: input.address.city, addressState: input.address.state,
    addressPostalCode: input.address.postalCode, geocodeLat: candidate.lat, geocodeLng: candidate.lng,
    geocodePrecision: candidate.precision, pinConfirmedAt: new Date(),
    endpointPolicies: { create: { effectiveDate: new Date("1900-01-01T00:00:00Z"), departure: input.departure, returnTo: input.returnTo } } }, select: { id: true } });
  return NextResponse.json({ success: true, id: depot.id }, { status: 201 });
}
