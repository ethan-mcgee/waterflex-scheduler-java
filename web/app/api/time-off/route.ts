import { readBody, timeOffRequest } from "@/lib/contracts";
import { NextRequest, NextResponse } from "next/server";
import { submitApiTimeOff } from "@/lib/apiTimeOff";
import { apiTimeOffStep } from "@/lib/apiTimeOffRoute";
import { activeClient } from "@/lib/activeClient";
import { notFound, ownsTechnician } from "@/lib/clientScope";

export async function POST(request: NextRequest) {
  const parsed = await readBody(request, timeOffRequest);
  if (!parsed.success) return NextResponse.json({ error: "Invalid time-off request" }, { status: 400 });
  if (!(await ownsTechnician((await activeClient(request)).id, parsed.data.technicianId))) return notFound("Technician");
  // The page then analyzes the request one day at a time through /api/time-off/analyze.
  return apiTimeOffStep(async () => submitApiTimeOff((await activeClient(request)).id, parsed.data));
}
