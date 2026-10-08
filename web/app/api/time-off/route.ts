import { readBody, timeOffRequest } from "@/lib/contracts";
import { NextRequest, NextResponse } from "next/server";
import { submitApiTimeOff } from "@/lib/apiTimeOff";
import { apiTimeOffStep } from "@/lib/apiTimeOffRoute";
import { publicApiEnabled } from "@/lib/schedulerApi";
import { activeClient } from "@/lib/activeClient";
import { notFound, ownsTechnician } from "@/lib/clientScope";
import { EngineError, submitTimeOff } from "@/lib/engineClient";

export async function POST(request: NextRequest) {
  const parsed = await readBody(request, timeOffRequest);
  if (!parsed.success) return NextResponse.json({ error: "Invalid time-off request" }, { status: 400 });
  if (!(await ownsTechnician((await activeClient(request)).id, parsed.data.technicianId))) return notFound("Technician");
  // Through the scheduling API, the request is analyzed by /api/time-off/analyze instead of the scheduler's queue.
  if (publicApiEnabled()) return apiTimeOffStep(async () => submitApiTimeOff((await activeClient(request)).id, parsed.data));
  try { return NextResponse.json(await submitTimeOff(parsed.data)); }
  catch (error) {
    if (error instanceof EngineError) return NextResponse.json({ error: error.message }, { status: error.status });
    throw error;
  }
}
