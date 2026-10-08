import { z } from "zod";
import { readBody, text } from "@/lib/contracts";
import { NextRequest, NextResponse } from "next/server";
import { approveApiTimeOff } from "@/lib/apiTimeOff";
import { apiTimeOffStep } from "@/lib/apiTimeOffRoute";
import { publicApiEnabled } from "@/lib/schedulerApi";
import { activeClient } from "@/lib/activeClient";
import { notFound, ownsTimeOffRequest } from "@/lib/clientScope";
import { approveTimeOff, EngineError } from "@/lib/engineClient";

export async function POST(request: NextRequest) {
  // Overtime is never assigned, so approval carries only the request ID.
  const parsed = await readBody(request, z.object({ id: text }).strict());
  if (!parsed.success) return NextResponse.json({ error: "Invalid request body" }, { status: 400 });
  const { id } = parsed.data;
  if (!id) return NextResponse.json({ error: "Missing request ID" }, { status: 400 });
  if (!(await ownsTimeOffRequest((await activeClient(request)).id, id))) return notFound("Time-off request");
  if (publicApiEnabled()) return apiTimeOffStep(async () => approveApiTimeOff((await activeClient(request)).id, id));
  try { return NextResponse.json(await approveTimeOff(id)); }
  catch (error) {
    if (error instanceof EngineError) return NextResponse.json({ error: error.message }, { status: error.status });
    throw error;
  }
}
