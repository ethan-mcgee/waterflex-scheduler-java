import { readBody, applyRequest } from "@/lib/contracts";
import { NextRequest, NextResponse } from "next/server";
import { applyOptimization, EngineError } from "@/lib/engineClient";

export async function POST(req: NextRequest) {
  const parsed = await readBody(req, applyRequest);
  if (!parsed.success) return NextResponse.json({ error: "Invalid request body" }, { status: 400 });
  const body = parsed.data;
  try {
    return NextResponse.json(await applyOptimization(body.runId));
  } catch (error) {
    if (error instanceof EngineError) {
      return NextResponse.json({ error: error.message }, { status: error.status });
    }
    throw error;
  }
}
