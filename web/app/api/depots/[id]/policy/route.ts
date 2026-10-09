import { NextRequest, NextResponse } from "next/server";
import { activeClient } from "@/lib/activeClient";
import { notFound, ownsDepot } from "@/lib/clientScope";
import { dealershipPolicy, readBody } from "@/lib/contracts";
import { updateDepotPolicy, EngineError } from "@/lib/engineClient";
import { setApiDepotPolicy } from "@/lib/apiMasterData";
import { changeStep } from "@/lib/apiChangeRoute";
import { publicApiEnabled } from "@/lib/schedulerApi";

export async function PUT(request: NextRequest, { params }: { params: { id: string } }) {
  const parsed = await readBody(request, dealershipPolicy);
  if (!parsed.success) return NextResponse.json({ error: "Invalid route endpoints" }, { status: 400 });
  const clientId = (await activeClient(request)).id;
  if (!(await ownsDepot(clientId, params.id))) return notFound("Depot");
  if (publicApiEnabled()) return changeStep(() => setApiDepotPolicy(clientId, params.id, parsed.data.departure, parsed.data.returnTo));
  try {
    return NextResponse.json(await updateDepotPolicy(params.id, parsed.data.departure, parsed.data.returnTo));
  } catch (error) {
    if (error instanceof EngineError) return NextResponse.json({ error: error.message }, { status: error.status });
    throw error;
  }
}
