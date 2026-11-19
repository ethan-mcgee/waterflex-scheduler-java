import { NextRequest, NextResponse } from "next/server";
import { newWorkOrderResponse, readBody, workOrderRequest } from "@/lib/contracts";
import { activeClient } from "@/lib/activeClient";
import { geocoderRefusal, intakeJob } from "@/lib/apiJobIntake";

/**
 * A new work order from the schedule's form, for a WaterFlex account or a typed address with its confirmed pin. It
 * waits as PENDING to be placed, and the schedule then asks the booking service for its recommended times. With
 * `followUp` it is taken in without a location and waits on the Follow-up page, as one sent there from booking does.
 * The same `requestId` sent again returns the same job.
 */
export async function POST(req: NextRequest) {
  const parsed = await readBody(req, workOrderRequest);
  if (!parsed.success) return NextResponse.json({ error: "Invalid work order" }, { status: 400 });
  const client = await activeClient(req);
  try {
    const intake = await intakeJob(client.id, parsed.data);
    if ("refusal" in intake) return NextResponse.json(intake.refusal.body, { status: intake.refusal.status });
    return NextResponse.json(newWorkOrderResponse.parse({ jobId: intake.job.id, followUp: parsed.data.followUp === true }));
  } catch (error) {
    const refusal = geocoderRefusal(error);
    if (refusal !== null) return NextResponse.json(refusal.body, { status: refusal.status });
    throw error;
  }
}
