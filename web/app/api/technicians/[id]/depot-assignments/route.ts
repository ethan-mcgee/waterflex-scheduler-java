import { NextRequest, NextResponse } from "next/server";
import { technicianDepotAssignment, readBody } from "@/lib/contracts";
import { assignTechnicianDepot, EngineError } from "@/lib/engineClient";

export async function POST(request: NextRequest, { params }: { params: { id: string } }) {
  const parsed = await readBody(request, technicianDepotAssignment);
  if (!parsed.success) return NextResponse.json({ error: "Invalid depot assignment" }, { status: 400 });
  try {
    return NextResponse.json(await assignTechnicianDepot(params.id, parsed.data.depotId, parsed.data.effectiveDate));
  } catch (error) {
    if (error instanceof EngineError) return NextResponse.json({ error: error.message }, { status: error.status });
    throw error;
  }
}
