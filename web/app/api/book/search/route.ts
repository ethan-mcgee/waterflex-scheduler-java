import { NextRequest, NextResponse } from "next/server";
import { durableSearchStart, savedBookingSearch, readBody } from "@/lib/contracts";
import { startBookingSearch, bookingSearchStatus, EngineError } from "@/lib/engineClient";

export async function POST(request: NextRequest) {
  const parsed = await readBody(request, durableSearchStart);
  if (!parsed.success) return NextResponse.json({ error: "Invalid search request" }, { status: 400 });
  try { return NextResponse.json(await startBookingSearch(parsed.data.jobId, parsed.data.requestId, parsed.data.refresh)); }
  catch (error) { if (error instanceof EngineError) return NextResponse.json({ error: error.message }, { status: error.status }); throw error; }
}
async function status(request: NextRequest, cancel: boolean) {
  const parsed = savedBookingSearch.safeParse({ id: request.nextUrl.searchParams.get("id"), jobId: request.nextUrl.searchParams.get("jobId") });
  if (!parsed.success) return NextResponse.json({ error: "Invalid search identifier" }, { status: 400 });
  try { return NextResponse.json(await bookingSearchStatus(parsed.data.id, parsed.data.jobId, cancel)); }
  catch (error) { if (error instanceof EngineError) return NextResponse.json({ error: error.message }, { status: error.status }); throw error; }
}
export function GET(request: NextRequest) { return status(request, false); }
export function DELETE(request: NextRequest) { return status(request, true); }
