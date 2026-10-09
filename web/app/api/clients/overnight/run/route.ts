import { NextRequest, NextResponse } from "next/server";
import { activeClient } from "@/lib/activeClient";
import { requestRun } from "@/lib/overnight";

/** "Run now": queues an overnight run for the active client, or returns the one already queued or running. */
export async function POST(request: NextRequest) {
  const clientId = (await activeClient(request)).id;
  const { run, alreadyActive } = await requestRun(clientId);
  return NextResponse.json({ run, alreadyActive }, { status: alreadyActive ? 200 : 202 });
}
