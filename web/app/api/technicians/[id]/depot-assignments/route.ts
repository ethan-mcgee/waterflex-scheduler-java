import { NextRequest, NextResponse } from "next/server";
import { activeClient } from "@/lib/activeClient";
import { notFound, ownsDepot, ownsTechnician } from "@/lib/clientScope";
import { technicianDepotAssignment, readBody } from "@/lib/contracts";
import { assignApiTechnicianDepot } from "@/lib/apiMasterData";
import { changeStep } from "@/lib/apiChangeRoute";

export async function POST(request: NextRequest, { params }: { params: { id: string } }) {
  const parsed = await readBody(request, technicianDepotAssignment);
  if (!parsed.success) return NextResponse.json({ error: "Invalid depot assignment" }, { status: 400 });
  const clientId = (await activeClient(request)).id;
  if (!(await ownsTechnician(clientId, params.id))) return notFound("Technician");
  if (!(await ownsDepot(clientId, parsed.data.depotId))) return notFound("Depot");
  return changeStep(() => assignApiTechnicianDepot(clientId, params.id, parsed.data.depotId, parsed.data.effectiveDate));
}
