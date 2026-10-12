import { NextRequest, NextResponse } from "next/server";
import { activeClient } from "@/lib/activeClient";
import { connectionStatusOf } from "@/lib/apiConnection";
import { connectionReply } from "@/lib/apiConnectionCore";

export const dynamic = "force-dynamic";

/** Whether the active client can schedule through the public API, and why not. */
export async function GET(request: NextRequest) {
  const client = await activeClient(request);
  const reply = connectionReply.parse({ clientId: client.id, ...await connectionStatusOf(client.id) });
  return NextResponse.json(reply, { headers: { "Cache-Control": "no-store" } });
}
