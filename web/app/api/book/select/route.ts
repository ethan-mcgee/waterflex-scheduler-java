import { readBody, selectRequest } from "@/lib/contracts";
import { NextRequest, NextResponse } from "next/server";
import { apiBookingStep } from "@/lib/apiBookingRoute";
import { bookApiOffer } from "@/lib/apiBooking";
import { activeClient } from "@/lib/activeClient";
import { notFound, ownsJob } from "@/lib/clientScope";

/** Books the chosen offer: its hold is selected and confirmed, and the appointment written. */
export async function POST(req: NextRequest) {
  const parsed = await readBody(req, selectRequest);
  if (!parsed.success) return NextResponse.json({ error: "Invalid request body" }, { status: 400 });
  const body = parsed.data;
  const client = await activeClient(req);
  if (!(await ownsJob(client.id, body.jobId))) return notFound("Booking");
  return apiBookingStep(client.id, body.jobId, () => bookApiOffer(client.id, body.jobId, body.offerId));
}
