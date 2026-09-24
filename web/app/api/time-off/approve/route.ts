import { z } from "zod";
import { readBody, text } from "@/lib/contracts";
import { NextRequest, NextResponse } from "next/server";
import { approveTimeOff, EngineError } from "@/lib/engineClient";

export async function POST(request: NextRequest) {
  const parsed = await readBody(request, z.object({ id: text, allowAdditionalOvertime: z.boolean().optional(),
    approvedRepairIds: z.array(text).max(366).optional() }).refine(value => !value.allowAdditionalOvertime
      || (value.approvedRepairIds !== undefined && value.approvedRepairIds.length > 0 && new Set(value.approvedRepairIds).size === value.approvedRepairIds.length), "Reviewed repair IDs required"));
  if (!parsed.success) return NextResponse.json({ error: "Invalid request body" }, { status: 400 });
  const { id } = parsed.data;
  if (!id) return NextResponse.json({ error: "Missing request ID" }, { status: 400 });
  try { return NextResponse.json(await approveTimeOff(id, parsed.data.allowAdditionalOvertime, parsed.data.approvedRepairIds)); }
  catch (error) {
    if (error instanceof EngineError) return NextResponse.json({ error: error.message }, { status: error.status });
    throw error;
  }
}
