import { readBody, selectRequest } from "@/lib/contracts";
import { NextRequest, NextResponse } from "next/server";
import { EngineError, releaseOffers } from "@/lib/engineClient";
import { activeClient } from "@/lib/activeClient";
import { notFound, ownsJob } from "@/lib/clientScope";

export async function POST(request: NextRequest) {
  const parsed = await readBody(request, selectRequest);
  if (!parsed.success) return NextResponse.json({ error: "Invalid request body" }, { status: 400 });
  if (!(await ownsJob((await activeClient(request)).id, parsed.data.jobId))) return notFound("Booking");
  try {
    return NextResponse.json(await releaseOffers(parsed.data.jobId, parsed.data.offerId));
  } catch (error) {
    if (error instanceof EngineError) return NextResponse.json({ error: error.message }, { status: error.status });
    throw error;
  }
}
