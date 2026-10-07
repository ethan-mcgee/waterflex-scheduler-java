import { z } from "zod";
import { readBody, text } from "@/lib/contracts";
import { NextRequest, NextResponse } from "next/server";
import { approveTimeOff, EngineError } from "@/lib/engineClient";

export async function POST(request: NextRequest) {
  // Overtime is never assigned, so approval carries only the request ID.
  const parsed = await readBody(request, z.object({ id: text }).strict());
  if (!parsed.success) return NextResponse.json({ error: "Invalid request body" }, { status: 400 });
  const { id } = parsed.data;
  if (!id) return NextResponse.json({ error: "Missing request ID" }, { status: 400 });
  try { return NextResponse.json(await approveTimeOff(id)); }
  catch (error) {
    if (error instanceof EngineError) return NextResponse.json({ error: error.message }, { status: error.status });
    throw error;
  }
}
