import { NextRequest, NextResponse } from "next/server";
import { EngineError, optimizationHistory } from "@/lib/engineClient";

export async function GET(req: NextRequest) {
  const metroId = req.nextUrl.searchParams.get("metroId");
  const date = req.nextUrl.searchParams.get("date");
  if (!metroId || !date) {
    return NextResponse.json({ error: "Missing metroId or date" }, { status: 400 });
  }
  try {
    return NextResponse.json(await optimizationHistory(metroId, date));
  } catch (error) {
    if (error instanceof EngineError) {
      return NextResponse.json({ error: error.message }, { status: error.status });
    }
    throw error;
  }
}
