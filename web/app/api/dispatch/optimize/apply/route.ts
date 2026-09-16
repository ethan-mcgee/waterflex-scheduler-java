import { NextRequest, NextResponse } from "next/server";
import { applyOptimization, EngineError } from "@/lib/engineClient";

export async function POST(req: NextRequest) {
  const body = (await req.json()) as { runId?: string };
  if (!body.runId) {
    return NextResponse.json({ error: "Missing runId" }, { status: 400 });
  }
  try {
    return NextResponse.json(await applyOptimization(body.runId));
  } catch (error) {
    if (error instanceof EngineError) {
      return NextResponse.json({ error: error.message }, { status: error.status });
    }
    throw error;
  }
}
