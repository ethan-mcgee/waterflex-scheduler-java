import { z } from "zod";
import { readBody, text } from "@/lib/contracts";
import { NextRequest, NextResponse } from "next/server";
import { retryTimeOff, EngineError } from "@/lib/engineClient";

export async function POST(request: NextRequest) {
  const parsed = await readBody(request, z.object({ id: text }));
  if (!parsed.success) return NextResponse.json({ error: "Invalid request body" }, { status: 400 });
  try { return NextResponse.json(await retryTimeOff(parsed.data.id)); }
  catch (error) {
    if (error instanceof EngineError) return NextResponse.json({ error: error.message }, { status: error.status });
    throw error;
  }
}
