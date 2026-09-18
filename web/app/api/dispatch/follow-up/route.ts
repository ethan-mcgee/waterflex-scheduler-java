import { NextRequest, NextResponse } from "next/server";
import { prisma } from "@/lib/prisma";

export async function POST(request: NextRequest) {
  const { jobId, action } = await request.json() as { jobId?: string; action?: string };
  if (!jobId || !["CONTACTED", "RESOLVED"].includes(action ?? ""))
    return NextResponse.json({ error: "Invalid follow-up action" }, { status: 400 });
  const job = await prisma.job.findUnique({ where: { id: jobId } });
  if (!job) return NextResponse.json({ error: "Request not found" }, { status: 404 });
  const updated = await prisma.job.update({ where: { id: jobId }, data: { manualFollowUpStatus: action } });
  return NextResponse.json({ status: updated.manualFollowUpStatus });
}
