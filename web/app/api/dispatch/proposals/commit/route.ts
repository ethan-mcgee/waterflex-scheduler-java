import { NextRequest, NextResponse } from "next/server";
import { commitProposalRequest, readBody } from "@/lib/contracts";
import { activeClient } from "@/lib/activeClient";
import { commitApiProposal } from "@/lib/apiDispatch";
import { apiDispatchStep, onlyWithApi } from "@/lib/apiDispatchRoute";
import { publicApiEnabled } from "@/lib/schedulerApi";

/** Applies one of the client's IMPROVED proposals; only the client's own proposals are found. */
export async function POST(req: NextRequest) {
  if (!publicApiEnabled()) return onlyWithApi();
  const parsed = await readBody(req, commitProposalRequest);
  if (!parsed.success) return NextResponse.json({ error: "Invalid request body" }, { status: 400 });
  const clientId = (await activeClient(req)).id;
  return apiDispatchStep(() => commitApiProposal(clientId, parsed.data.proposalId));
}
