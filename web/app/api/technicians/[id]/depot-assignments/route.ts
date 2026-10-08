import { NextRequest, NextResponse } from "next/server";
import { activeClient } from "@/lib/activeClient";
import { notFound, ownsDepot, ownsTechnician } from "@/lib/clientScope";
import { technicianDepotAssignment, readBody } from "@/lib/contracts";
import { assignTechnicianDepot, EngineError } from "@/lib/engineClient";

export async function POST(request: NextRequest, { params }: { params: { id: string } }) {
  const parsed = await readBody(request, technicianDepotAssignment);
  if (!parsed.success) return NextResponse.json({ error: "Invalid depot assignment" }, { status: 400 });
  const clientId = (await activeClient(request)).id;
  if (!(await ownsTechnician(clientId, params.id))) return notFound("Technician");
  if (!(await ownsDepot(clientId, parsed.data.depotId))) return notFound("Depot");
  try {
    return NextResponse.json(await assignTechnicianDepot(params.id, parsed.data.depotId, parsed.data.effectiveDate));
  } catch (error) {
    if (error instanceof EngineError) return NextResponse.json({ error: error.message }, { status: error.status });
    throw error;
  }
}
