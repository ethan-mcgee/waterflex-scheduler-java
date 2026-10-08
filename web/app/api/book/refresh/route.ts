import { readBody, refreshRequest } from "@/lib/contracts";
import { NextRequest, NextResponse } from "next/server";
import { EngineError, requestSlots } from "@/lib/engineClient";
import { activeClient } from "@/lib/activeClient";
import { notFound, ownsJob } from "@/lib/clientScope";

export async function POST(request: NextRequest) {
  const started = performance.now();
  const parsed = await readBody(request, refreshRequest);
  if (!parsed.success) return NextResponse.json({ error: "Invalid request body" }, { status: 400 });
  const body = parsed.data;
  if (!(await ownsJob((await activeClient(request)).id, body.jobId))) return notFound("Booking");
  try {
    const result = await requestSlots(body.jobId, true, 5000, request.signal, body.deadlineEpochMs);
    return NextResponse.json(result);
  } catch (error) {
    if (error instanceof EngineError && error.status === 504) {
      // No new budget is granted after browser transport consumed the original one.
      const elapsedMs = Math.ceil(performance.now() - started);
      return NextResponse.json({ jobId: body.jobId, offers: [], search: {
        outcome: "SEARCH_INCOMPLETE", prescribedSearchCompleted: false, retryable: true, elapsedMs, apiElapsedMs: elapsedMs,
      } });
    }
    if (error instanceof EngineError) return NextResponse.json({ error: error.message }, { status: error.status });
    throw error;
  }
}
