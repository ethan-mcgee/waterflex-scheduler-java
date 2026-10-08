import { z } from "zod";
import { readBody, text } from "@/lib/contracts";
import { NextRequest, NextResponse } from "next/server";
import { retryApiTimeOff } from "@/lib/apiTimeOff";
import { apiTimeOffStep } from "@/lib/apiTimeOffRoute";
import { publicApiEnabled } from "@/lib/schedulerApi";
import { activeClient } from "@/lib/activeClient";
import { notFound, ownsTimeOffRequest } from "@/lib/clientScope";
import { retryTimeOff, EngineError } from "@/lib/engineClient";

export async function POST(request: NextRequest) {
  const parsed = await readBody(request, z.object({ id: text }));
  if (!parsed.success) return NextResponse.json({ error: "Invalid request body" }, { status: 400 });
  if (!(await ownsTimeOffRequest((await activeClient(request)).id, parsed.data.id))) return notFound("Time-off request");
  if (publicApiEnabled()) return apiTimeOffStep(async () => retryApiTimeOff((await activeClient(request)).id, parsed.data.id));
  try { return NextResponse.json(await retryTimeOff(parsed.data.id)); }
  catch (error) {
    if (error instanceof EngineError) return NextResponse.json({ error: error.message }, { status: error.status });
    throw error;
  }
}
