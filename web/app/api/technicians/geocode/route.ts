import { NextRequest, NextResponse } from "next/server";
import { z } from "zod";
import { readBody, text } from "@/lib/contracts";
import { GeocoderError, searchAddress } from "@/lib/geocode";
import { geocoderStatus } from "@/lib/technicianHome";

const address = z.object({ line1: text, city: text, state: text, postalCode: text }).strict();

export async function POST(request: NextRequest) {
  const parsed = await readBody(request, address);
  if (!parsed.success) return NextResponse.json({ error: "Enter a complete street address" }, { status: 400 });
  try {
    const candidates = await searchAddress(parsed.data);
    return candidates.length ? NextResponse.json({ candidates }) :
      NextResponse.json({ error: "No matching address found. Check the street, city, state, and ZIP." }, { status: 404 });
  } catch (error) {
    if (!(error instanceof GeocoderError)) throw error;
    return NextResponse.json({ error: error.message }, { status: geocoderStatus(error) });
  }
}
