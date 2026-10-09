import { readBody, qualificationRequest } from "@/lib/contracts";
import { NextRequest, NextResponse } from "next/server";
import { setApiQualification } from "@/lib/apiTimeOff";
import { apiTimeOffStep } from "@/lib/apiTimeOffRoute";
import { activeClient } from "@/lib/activeClient";
import { notFound, ownsTechnician } from "@/lib/clientScope";

export async function POST(req: NextRequest) {
  const parsed = await readBody(req, qualificationRequest);
  if (!parsed.success) return NextResponse.json({ error: "Invalid request body" }, { status: 400 });
  const body = parsed.data;
  if (!(await ownsTechnician((await activeClient(req)).id, body.technicianId))) return notFound("Technician");
  // The portal owns this master data and records it itself; see lib/apiTimeOff for when a change is refused.
  return apiTimeOffStep(async () => setApiQualification((await activeClient(req)).id, body));
}
