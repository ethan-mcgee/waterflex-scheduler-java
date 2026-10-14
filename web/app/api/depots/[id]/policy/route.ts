import { NextRequest, NextResponse } from "next/server";
import { activeClient } from "@/lib/activeClient";
import { notFound, ownsDepot } from "@/lib/clientScope";
import { dealershipPolicy, readBody } from "@/lib/contracts";
import { setApiDepotPolicy } from "@/lib/apiMasterData";
import { changeStep } from "@/lib/apiChangeRoute";

export async function PUT(request: NextRequest, { params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;
  const parsed = await readBody(request, dealershipPolicy);
  if (!parsed.success) return NextResponse.json({ error: "Invalid route endpoints" }, { status: 400 });
  const clientId = (await activeClient(request)).id;
  if (!(await ownsDepot(clientId, id))) return notFound("Depot");
  return changeStep(() => setApiDepotPolicy(clientId, id, parsed.data.departure, parsed.data.returnTo));
}
