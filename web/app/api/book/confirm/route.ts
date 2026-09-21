import { readBody, confirmRequest } from "@/lib/contracts";
import { NextRequest, NextResponse } from "next/server";
import { confirmHold, EngineError } from "@/lib/engineClient";
import { prisma } from "@/lib/prisma";

export async function POST(req: NextRequest) {
  const parsed = await readBody(req, confirmRequest);
  if (!parsed.success) return NextResponse.json({ error: "Invalid request body" }, { status: 400 });
  const body = parsed.data;
  try {
    const result = await confirmHold(body.holdId);
    return NextResponse.json(result);
  } catch (err) {
    if (err instanceof EngineError) {
      if (err.status === 503) {
        const hold = await prisma.slotHold.findUnique({ where: { id: body.holdId }, select: { jobId: true } });
        if (hold) {
          await prisma.job.update({ where: { id: hold.jobId }, data: {
            manualFollowUpStatus: "PENDING", manualFollowUpReason: "ROAD_ROUTING_UNAVAILABLE",
          } });
          return NextResponse.json({ pendingReference: hold.jobId }, { status: 503 });
        }
      }
      // 409 from the engine means the slot fell through between offer and
      // confirm (someone else booked it, or it expired) — surface that as
      // a normal "please pick again" response, not a 500.
      return NextResponse.json({ error: err.message }, { status: err.status });
    }
    throw err;
  }
}
