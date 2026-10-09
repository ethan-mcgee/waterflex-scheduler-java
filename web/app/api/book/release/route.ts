import { readBody, selectRequest } from "@/lib/contracts";
import { NextRequest, NextResponse } from "next/server";
import { apiBookingStep } from "@/lib/apiBookingRoute";
import { releaseApiOffers } from "@/lib/apiBooking";
import { activeClient } from "@/lib/activeClient";
import { notFound, ownsJob } from "@/lib/clientScope";

/** Releases the holds of a booking's current offers, as when the customer starts over. */
export async function POST(request: NextRequest) {
  const parsed = await readBody(request, selectRequest);
  if (!parsed.success) return NextResponse.json({ error: "Invalid request body" }, { status: 400 });
  const client = await activeClient(request);
  if (!(await ownsJob(client.id, parsed.data.jobId))) return notFound("Booking");
  return apiBookingStep(client.id, parsed.data.jobId, () => releaseApiOffers(client.id, parsed.data.jobId, parsed.data.offerId));
}
