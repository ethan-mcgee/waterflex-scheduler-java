import { NextRequest, NextResponse } from "next/server";
import { approveTimeOff, EngineError } from "@/lib/engineClient";

export async function POST(request: NextRequest) {
  const { id } = await request.json() as { id?: string };
  if (!id) return NextResponse.json({ error: "Missing request ID" }, { status: 400 });
  try { return NextResponse.json(await approveTimeOff(id)); }
  catch (error) {
    if (error instanceof EngineError) return NextResponse.json({ error: error.message }, { status: error.status });
    throw error;
  }
}
