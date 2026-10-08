import { readBody, applyRequest } from "@/lib/contracts";
import { NextRequest, NextResponse } from "next/server";
import { notUsedWithApi } from "@/lib/apiBookingRoute";
import { publicApiEnabled } from "@/lib/schedulerApi";
import { activeClient } from "@/lib/activeClient";
import { notFound, ownsOptimizationRun } from "@/lib/clientScope";
import { applyOptimization, EngineError } from "@/lib/engineClient";

export async function POST(req: NextRequest) {
  // Through the scheduling API, the board uses /api/dispatch/proposals instead.
  if (publicApiEnabled()) return notUsedWithApi();
  const parsed = await readBody(req, applyRequest);
  if (!parsed.success) return NextResponse.json({ error: "Invalid request body" }, { status: 400 });
  const body = parsed.data;
  if (!(await ownsOptimizationRun((await activeClient(req)).id, body.runId))) return notFound("Preview");
  try {
    return NextResponse.json(await applyOptimization(body.runId));
  } catch (error) {
    if (error instanceof EngineError) {
      return NextResponse.json({ error: error.message }, { status: error.status });
    }
    throw error;
  }
}
