import { NextRequest, NextResponse } from "next/server";
import { cancelRequestBody, readBody } from "@/lib/contracts";
import { activeClient } from "@/lib/activeClient";
import { cancelRequest } from "@/lib/schedulerApi";
import { refusedFor } from "@/lib/apiDispatch";
import { apiDispatchStep } from "@/lib/apiDispatchRoute";

/**
 * Asks the scheduler to stop the client's running optimization. The optimize call itself then answers that it was
 * cancelled; "NOT_RUNNING" means it already finished or never reached this scheduler, so its answer arrives as usual.
 */
export async function POST(req: NextRequest) {
  const parsed = await readBody(req, cancelRequestBody);
  if (!parsed.success) return NextResponse.json({ error: "Invalid request body" }, { status: 400 });
  const clientId = (await activeClient(req)).id;
  return apiDispatchStep(async () => {
    try { return { outcome: await cancelRequest(clientId, parsed.data.requestId) }; }
    catch (error) { return refusedFor(error); }
  });
}
