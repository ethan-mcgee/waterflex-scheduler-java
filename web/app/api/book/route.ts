import { readBody, workOrderRequest } from "@/lib/contracts";
import { NextRequest, NextResponse } from "next/server";
import { activeClient } from "@/lib/activeClient";
import { apiBookingStep } from "@/lib/apiBookingRoute";
import { searchApiOffers } from "@/lib/apiBooking";
import { geocoderRefusal, intakeJob } from "@/lib/apiJobIntake";

export async function POST(req: NextRequest) {
  const parsed = await readBody(req, workOrderRequest);
  if (!parsed.success) return NextResponse.json({ error: "Invalid booking request" }, { status: 400 });
  const input = parsed.data;
  const client = await activeClient(req);
  try {
    const intake = await intakeJob(client.id, input);
    if ("refusal" in intake) return NextResponse.json(intake.refusal.body, { status: intake.refusal.status });
    const jobId = intake.job.id;
    if (input.followUp) return NextResponse.json({ status: "FOLLOW_UP", jobId, pendingReference: jobId });
    return apiBookingStep(client.id, jobId, () => searchApiOffers(client.id, jobId));
  } catch (error) {
    const refusal = geocoderRefusal(error);
    if (refusal !== null) return NextResponse.json(refusal.body, { status: refusal.status });
    throw error;
  }
}
