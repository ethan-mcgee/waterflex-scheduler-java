import { NextRequest, NextResponse } from "next/server";
import { bookingAddress, bookingLocationResponse, readBody } from "@/lib/contracts";
import { bookingServiceArea } from "@/lib/serviceArea";
import { GeocoderError, searchAddress } from "@/lib/geocode";

export async function POST(req: NextRequest) {
  const input = await readBody(req, bookingAddress);
  if (!input.success) return NextResponse.json({ error: "Complete the service address." }, { status: 400 });
  try {
    const serviceArea = await bookingServiceArea();
    const candidates = await searchAddress(input.data, serviceArea.bounds);
    return NextResponse.json(bookingLocationResponse.parse({ serviceArea, candidates,
      status: candidates.some(c => c.precision === "ROOFTOP") ? "MATCHED" : candidates.length ? "NEEDS_PLACEMENT" : "NO_MATCH" }));
  } catch (error) {
    if (error instanceof GeocoderError) return NextResponse.json({ status: "LOOKUP_FAILED", error: error.message },
      { status: error.kind === "timeout" ? 504 : error.kind === "malformed" ? 502 : 503 });
    return NextResponse.json({ error: "Service-area information is unavailable. Please retry." }, { status: 503 });
  }
}
