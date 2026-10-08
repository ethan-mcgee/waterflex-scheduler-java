import { readBody, refreshRequest } from "@/lib/contracts";
import { NextRequest, NextResponse } from "next/server";
import { EngineError, requestSlots } from "@/lib/engineClient";
import { publicApiEnabled } from "@/lib/schedulerApi";
import { apiBookingStep } from "@/lib/apiBookingRoute";
import { searchApiOffers } from "@/lib/apiBooking";
import { activeClient } from "@/lib/activeClient";
import { notFound, ownsJob } from "@/lib/clientScope";

export async function POST(request: NextRequest) {
  const started = performance.now();
  const parsed = await readBody(request, refreshRequest);
  if (!parsed.success) return NextResponse.json({ error: "Invalid request body" }, { status: 400 });
  const body = parsed.data;
  const client = await activeClient(request);
  if (!(await ownsJob(client.id, body.jobId))) return notFound("Booking");
  if (publicApiEnabled()) return apiBookingStep(client.id, body.jobId, () => searchApiOffers(client.id, body.jobId));
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
