import { readBody, selectRequest } from "@/lib/contracts";
import { NextRequest, NextResponse } from "next/server";
import { EngineError, releaseOffers } from "@/lib/engineClient";
import { publicApiEnabled } from "@/lib/schedulerApi";
import { apiBookingStep } from "@/lib/apiBookingRoute";
import { releaseApiOffers } from "@/lib/apiBooking";
import { activeClient } from "@/lib/activeClient";
import { notFound, ownsJob } from "@/lib/clientScope";

export async function POST(request: NextRequest) {
  const parsed = await readBody(request, selectRequest);
  if (!parsed.success) return NextResponse.json({ error: "Invalid request body" }, { status: 400 });
  const client = await activeClient(request);
  if (!(await ownsJob(client.id, parsed.data.jobId))) return notFound("Booking");
  if (publicApiEnabled()) return apiBookingStep(client.id, parsed.data.jobId, () => releaseApiOffers(client.id, parsed.data.jobId, parsed.data.offerId));
  try {
    return NextResponse.json(await releaseOffers(parsed.data.jobId, parsed.data.offerId));
  } catch (error) {
    if (error instanceof EngineError) return NextResponse.json({ error: error.message }, { status: error.status });
    throw error;
  }
}
