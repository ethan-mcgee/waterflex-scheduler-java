import { z } from "zod";
import { readBody, text } from "@/lib/contracts";
import { NextRequest, NextResponse } from "next/server";
import { prisma } from "@/lib/prisma";

export async function POST(request: NextRequest) {
  const parsed = await readBody(request, z.object({ jobId: text, action: z.enum(["CONTACTED", "RESOLVED"]) }));
  if (!parsed.success) return NextResponse.json({ error: "Invalid request body" }, { status: 400 });
  const { jobId, action } = parsed.data;
  if (!jobId || !["CONTACTED", "RESOLVED"].includes(action ?? ""))
    return NextResponse.json({ error: "Invalid follow-up action" }, { status: 400 });
  const job = await prisma.job.findUnique({ where: { id: jobId } });
  if (!job) return NextResponse.json({ error: "Request not found" }, { status: 404 });
  const updated = await prisma.job.update({ where: { id: jobId }, data: { manualFollowUpStatus: action } });
  return NextResponse.json({ status: updated.manualFollowUpStatus });
}
