import { NextRequest, NextResponse } from "next/server";
import { bookingRequest, readBody } from "@/lib/contracts";
import { activeClient } from "@/lib/activeClient";
import { geocoderRefusal, intakeJob } from "@/lib/apiJobIntake";

/**
 * A new job from the schedule page's form, taken in as the booking wizard takes one in, so it waits as PENDING to be
 * placed on the calendar. It needs a location: a follow-up request belongs in the booking wizard.
 */
export async function POST(req: NextRequest) {
  const parsed = await readBody(req, bookingRequest);
  if (!parsed.success) return NextResponse.json({ error: "Invalid job" }, { status: 400 });
  if (parsed.data.followUp === true) return NextResponse.json({ error: "A job placed on the calendar needs a confirmed location." }, { status: 400 });
  const client = await activeClient(req);
  try {
    const intake = await intakeJob(client.id, parsed.data);
    if ("refusal" in intake) return NextResponse.json(intake.refusal.body, { status: intake.refusal.status });
    return NextResponse.json({ jobId: intake.job.id });
  } catch (error) {
    const refusal = geocoderRefusal(error);
    if (refusal !== null) return NextResponse.json(refusal.body, { status: refusal.status });
    throw error;
  }
}
