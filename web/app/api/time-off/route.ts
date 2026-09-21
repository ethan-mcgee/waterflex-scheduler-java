import { readBody, timeOffRequest } from "@/lib/contracts";
import { NextRequest, NextResponse } from "next/server";
import { EngineError, submitTimeOff } from "@/lib/engineClient";

export async function POST(request: NextRequest) {
  const parsed = await readBody(request, timeOffRequest);
  if (!parsed.success) return NextResponse.json({ error: "Invalid time-off request" }, { status: 400 });
  try { return NextResponse.json(await submitTimeOff(parsed.data)); }
  catch (error) {
    if (error instanceof EngineError) return NextResponse.json({ error: error.message }, { status: error.status });
    throw error;
  }
}
