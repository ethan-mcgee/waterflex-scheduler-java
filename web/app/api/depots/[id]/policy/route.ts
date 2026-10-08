import { NextRequest, NextResponse } from "next/server";
import { activeClient } from "@/lib/activeClient";
import { notFound, ownsDepot } from "@/lib/clientScope";
import { dealershipPolicy, readBody } from "@/lib/contracts";
import { updateDepotPolicy, EngineError } from "@/lib/engineClient";

export async function PUT(request: NextRequest, { params }: { params: { id: string } }) {
  const parsed = await readBody(request, dealershipPolicy);
  if (!parsed.success) return NextResponse.json({ error: "Invalid route endpoints" }, { status: 400 });
  if (!(await ownsDepot((await activeClient(request)).id, params.id))) return notFound("Depot");
  try {
    return NextResponse.json(await updateDepotPolicy(params.id, parsed.data.departure, parsed.data.returnTo));
  } catch (error) {
    if (error instanceof EngineError) return NextResponse.json({ error: error.message }, { status: error.status });
    throw error;
  }
}
