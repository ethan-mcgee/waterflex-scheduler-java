import { readBody, availabilityRequest, deleteAvailabilityRequest } from "@/lib/contracts";
import { NextRequest, NextResponse } from "next/server";
import { deleteApiAvailability, setApiAvailability } from "@/lib/apiTimeOff";
import { apiTimeOffStep } from "@/lib/apiTimeOffRoute";
import { publicApiEnabled } from "@/lib/schedulerApi";
import { activeClient } from "@/lib/activeClient";
import { notFound, ownsTechnician } from "@/lib/clientScope";
import { EngineError, updateAvailability, deleteAvailabilityOverride } from "@/lib/engineClient";

export async function POST(req: NextRequest) {
  const parsed = await readBody(req, availabilityRequest);
  if (!parsed.success) return NextResponse.json({ error: "Invalid request body" }, { status: 400 });
  const body = parsed.data;
  if (!(await ownsTechnician((await activeClient(req)).id, body.technicianId))) return notFound("Technician");
  // Through the scheduling API, the portal records this itself; the scheduler's own service is not called.
  if (publicApiEnabled()) return apiTimeOffStep(async () => setApiAvailability((await activeClient(req)).id, body));
  try { return NextResponse.json(await updateAvailability(body)); }
  catch (error) {
    if (error instanceof EngineError) return NextResponse.json({ error: error.message }, { status: error.status });
    throw error;
  }
}

export async function DELETE(req: NextRequest) {
  const parsed = await readBody(req, deleteAvailabilityRequest);
  if (!parsed.success) return NextResponse.json({ error: "Invalid request body" }, { status: 400 });
  const body = parsed.data;
  if (!(await ownsTechnician((await activeClient(req)).id, body.technicianId))) return notFound("Technician");
  // Through the scheduling API, the portal records this itself; the scheduler's own service is not called.
  if (publicApiEnabled()) return apiTimeOffStep(async () => deleteApiAvailability((await activeClient(req)).id, body));
  try { return NextResponse.json(await deleteAvailabilityOverride(body)); }
  catch (error) {
    if (error instanceof EngineError) return NextResponse.json({ error: error.message }, { status: error.status });
    throw error;
  }
}
