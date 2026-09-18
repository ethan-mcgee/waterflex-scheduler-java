import { NextRequest, NextResponse } from "next/server";
import { EngineError, submitTimeOff } from "@/lib/engineClient";

export async function POST(request: NextRequest) {
  try { return NextResponse.json(await submitTimeOff(await request.json())); }
  catch (error) {
    if (error instanceof EngineError) return NextResponse.json({ error: error.message }, { status: error.status });
    throw error;
  }
}
