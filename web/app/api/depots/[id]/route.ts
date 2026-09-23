import { NextRequest, NextResponse } from "next/server";
import { depotDetails, readBody } from "@/lib/contracts";
import { updateDepotDetails, EngineError } from "@/lib/engineClient";
import { searchAddress } from "@/lib/geocode";
import { nearbyCandidate } from "@/lib/depotPin";
import { prisma } from "@/lib/prisma";

export async function PATCH(request: NextRequest, { params }: { params: Promise<{ id: string }> }) {
  const parsed = await readBody(request, depotDetails);
  if (!parsed.success) return NextResponse.json({ error: "Invalid depot details" }, { status: 400 });
  const { id } = await params;
  const existing = await prisma.depot.findUnique({ where: { id }, select: {
    addressLine1: true, addressCity: true, addressState: true, addressPostalCode: true,
  } });
  if (!existing) return NextResponse.json({ error: "Depot not found" }, { status: 404 });
  const { name, address, confirmedPin } = parsed.data;
  const addressChanged = address !== undefined && (address.line1 !== existing.addressLine1 ||
    address.city !== existing.addressCity || address.state !== existing.addressState || address.postalCode !== existing.addressPostalCode);
  if (addressChanged && !confirmedPin) return NextResponse.json({ error: "Confirm a new map pin for the changed address" }, { status: 400 });
  if (!addressChanged && address !== undefined) return NextResponse.json({ error: "Only include an address when it changes" }, { status: 400 });
  let candidate: { lat: number; lng: number; precision: string } | undefined;
  if (address && confirmedPin) {
    const nearby = nearbyCandidate(confirmedPin, await searchAddress(address));
    if (!nearby) return NextResponse.json({ error: "Pin must be within 250 meters of the located address. Confirm the address and pin again." }, { status: 422 });
    candidate = nearby;
  }
  try {
    return NextResponse.json(await updateDepotDetails(id, { name, ...(address && confirmedPin && candidate ? { address, confirmedPin, candidate } : {}) }));
  } catch (error) {
    if (error instanceof EngineError) return NextResponse.json({ error: error.message }, { status: error.status });
    throw error;
  }
}
