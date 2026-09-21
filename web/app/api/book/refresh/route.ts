import { readBody, jobRequest } from "@/lib/contracts";
import { NextRequest, NextResponse } from "next/server";
import { EngineError, requestSlots } from "@/lib/engineClient";
import { prisma } from "@/lib/prisma";

export async function POST(request: NextRequest) {
  const parsed = await readBody(request, jobRequest);
  if (!parsed.success) return NextResponse.json({ error: "Invalid request body" }, { status: 400 });
  const body = parsed.data;
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
