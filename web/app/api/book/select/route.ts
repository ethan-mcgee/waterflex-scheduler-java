import { readBody, selectRequest } from "@/lib/contracts";
import { NextRequest, NextResponse } from "next/server";
import { selectOffer, requestSlots, EngineError } from "@/lib/engineClient";
import { prisma } from "@/lib/prisma";

export async function POST(req: NextRequest) {
  const parsed = await readBody(req, selectRequest);
  if (!parsed.success) return NextResponse.json({ error: "Invalid request body" }, { status: 400 });
  const body = parsed.data;
  try {
    return NextResponse.json(await selectOffer(body.jobId, body.offerId));
  } catch (error) {
    if (error instanceof EngineError) {
      if (error.status === 409) {
        const refreshed = await requestSlots(body.jobId, true).catch(() => ({ offers: [] }));
        return NextResponse.json({ error: error.message, offers: refreshed.offers }, { status: 409 });
      }
      if (error.status === 503) {
        await prisma.job.update({ where: { id: body.jobId }, data: {
          manualFollowUpStatus: "PENDING", manualFollowUpReason: "ROAD_ROUTING_UNAVAILABLE",
        } });
        return NextResponse.json({ pendingReference: body.jobId }, { status: 503 });
      }
      return NextResponse.json({ error: error.message }, { status: error.status });
    }
    throw error;
  }
}
