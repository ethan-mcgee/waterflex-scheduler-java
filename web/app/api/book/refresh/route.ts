import { NextRequest, NextResponse } from "next/server";
import { EngineError, requestSlots } from "@/lib/engineClient";
import { prisma } from "@/lib/prisma";

export async function POST(request: NextRequest) {
  const body = await request.json() as { jobId?: string };
  if (!body.jobId) return NextResponse.json({ error: "Missing jobId" }, { status: 400 });
  try {
    const result = await requestSlots(body.jobId, true);
    if (!result.offers.length) await prisma.job.update({ where: { id: body.jobId }, data: {
      manualFollowUpStatus: "PENDING", manualFollowUpReason: "NO_CAPACITY",
    } });
    return NextResponse.json(result);
  } catch (error) {
    if (error instanceof EngineError) return NextResponse.json({ error: error.message }, { status: error.status });
    throw error;
  }
}
