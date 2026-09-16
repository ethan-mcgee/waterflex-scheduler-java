import { NextRequest, NextResponse } from "next/server";
import { selectOffer, requestSlots, EngineError } from "@/lib/engineClient";
import { prisma } from "@/lib/prisma";

export async function POST(req: NextRequest) {
  const body = (await req.json()) as { jobId?: string; offerId?: string };
  if (!body.jobId || !body.offerId) return NextResponse.json({ error: "Missing jobId or offerId" }, { status: 400 });
  try {
    return NextResponse.json(await selectOffer(body.jobId, body.offerId));
  } catch (error) {
    if (error instanceof EngineError) {
      if (error.status === 409) {
        const refreshed = await requestSlots(body.jobId).catch(() => ({ offers: [] }));
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
