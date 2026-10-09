import { z } from "zod";
import { readBody, text } from "@/lib/contracts";
import { NextRequest, NextResponse } from "next/server";
import { denyApiTimeOff } from "@/lib/apiTimeOff";
import { apiTimeOffStep } from "@/lib/apiTimeOffRoute";
import { activeClient } from "@/lib/activeClient";
import { notFound, ownsTimeOffRequest } from "@/lib/clientScope";

export async function POST(request: NextRequest) {
  const parsed = await readBody(request, z.object({ id: text }));
  if (!parsed.success) return NextResponse.json({ error: "Invalid request body" }, { status: 400 });
  if (!(await ownsTimeOffRequest((await activeClient(request)).id, parsed.data.id))) return notFound("Time-off request");
  return apiTimeOffStep(async () => denyApiTimeOff((await activeClient(request)).id, parsed.data.id));
}
