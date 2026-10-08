import { readBody, selectRequest } from "@/lib/contracts";
import { NextRequest, NextResponse } from "next/server";
import { selectOffer, requestSlots, EngineError } from "@/lib/engineClient";
import { appointmentSearchMessage } from "@/lib/appointmentSearch";
import { activeClient } from "@/lib/activeClient";
import { notFound, ownsJob } from "@/lib/clientScope";

export async function POST(req: NextRequest) {
  const parsed = await readBody(req, selectRequest);
  if (!parsed.success) return NextResponse.json({ error: "Invalid request body" }, { status: 400 });
  const body = parsed.data;
  if (!(await ownsJob((await activeClient(req)).id, body.jobId))) return notFound("Booking");
  try {
    return NextResponse.json(await selectOffer(body.jobId, body.offerId));
  } catch (error) {
    if (error instanceof EngineError) {
      if (error.status === 409) {
        try {
          const refreshed = await requestSlots(body.jobId, true, 5000, req.signal);
          return NextResponse.json({ ...refreshed, error: appointmentSearchMessage(refreshed.search) ?? error.message }, { status: 409 });
        } catch (refreshError) {
          if (refreshError instanceof EngineError)
            return NextResponse.json({ error: refreshError.message }, { status: refreshError.status });
          throw refreshError;
        }
      }
      return NextResponse.json({ error: error.message }, { status: error.status });
    }
    throw error;
  }
}
