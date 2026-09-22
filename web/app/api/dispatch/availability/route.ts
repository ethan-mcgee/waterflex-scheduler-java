import { readBody, availabilityRequest, deleteAvailabilityRequest } from "@/lib/contracts";
import { NextRequest, NextResponse } from "next/server";
import { EngineError, updateAvailability, deleteAvailabilityOverride } from "@/lib/engineClient";

export async function POST(req: NextRequest) {
  const parsed = await readBody(req, availabilityRequest);
  if (!parsed.success) return NextResponse.json({ error: "Invalid request body" }, { status: 400 });
  const body = parsed.data;
  try { return NextResponse.json(await updateAvailability(body)); }
  catch (error) {
    if (error instanceof EngineError) return NextResponse.json({ error: error.message }, { status: error.status });
    throw error;
  }
}

export async function DELETE(req: NextRequest) {
  const parsed = await readBody(req, deleteAvailabilityRequest);
  if (!parsed.success) return NextResponse.json({ error: "Invalid request body" }, { status: 400 });
  const body = parsed.data;
  try { return NextResponse.json(await deleteAvailabilityOverride(body)); }
  catch (error) {
    if (error instanceof EngineError) return NextResponse.json({ error: error.message }, { status: error.status });
    throw error;
  }
}
