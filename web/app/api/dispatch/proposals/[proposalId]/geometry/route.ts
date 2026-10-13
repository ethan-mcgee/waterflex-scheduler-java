import { NextRequest, NextResponse } from "next/server";
import { text } from "@/lib/contracts";
import { activeClient } from "@/lib/activeClient";
import { apiProposalGeometry } from "@/lib/apiProposalGeometry";
import { apiDispatchStep } from "@/lib/apiDispatchRoute";

/** The road routes of one of the client's improved daily proposals; only the client's own proposals are found. */
export async function GET(req: NextRequest, { params }: { params: Promise<{ proposalId: string }> }) {
  const { proposalId } = await params;
  if (!text.safeParse(proposalId).success) return NextResponse.json({ error: "Invalid proposal ID" }, { status: 400 });
  const clientId = (await activeClient(req)).id;
  return apiDispatchStep(() => apiProposalGeometry(clientId, proposalId));
}
