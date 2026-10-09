import { readBody, refreshRequest } from "@/lib/contracts";
import { NextRequest, NextResponse } from "next/server";
import { apiBookingStep } from "@/lib/apiBookingRoute";
import { searchApiOffers } from "@/lib/apiBooking";
import { activeClient } from "@/lib/activeClient";
import { notFound, ownsJob } from "@/lib/clientScope";

/** Searches again for a pending booking of the client, ending its earlier offers. */
export async function POST(request: NextRequest) {
  const parsed = await readBody(request, refreshRequest);
  if (!parsed.success) return NextResponse.json({ error: "Invalid request body" }, { status: 400 });
  const body = parsed.data;
  const client = await activeClient(request);
  if (!(await ownsJob(client.id, body.jobId))) return notFound("Booking");
  return apiBookingStep(client.id, body.jobId, () => searchApiOffers(client.id, body.jobId));
}
