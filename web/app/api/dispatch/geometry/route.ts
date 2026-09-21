import { date as dateContract, text } from "@/lib/contracts";
import { NextRequest, NextResponse } from "next/server";
import { dispatchGeometry, EngineError } from "@/lib/engineClient";

export async function GET(req: NextRequest) {
  const metroId = req.nextUrl.searchParams.get("metroId");
  const date = req.nextUrl.searchParams.get("date");
  const runId = req.nextUrl.searchParams.get("runId") ?? undefined;
  const phase = req.nextUrl.searchParams.get("phase") ?? "current";
  if (!metroId || !date || !text.safeParse(metroId).success || !dateContract.safeParse(date).success) return NextResponse.json({ error: "Missing metroId or date" }, { status: 400 });
  if (!["current", "before", "after"].includes(phase) || (phase === "after" && !runId)) return NextResponse.json({ error: "Invalid route phase or preview" }, { status: 400 });
  try {
    return NextResponse.json(await dispatchGeometry(metroId, date, runId, phase));
  } catch (error) {
    if (error instanceof EngineError) return NextResponse.json({ error: error.message }, { status: error.status });
    throw error;
  }
}
