import { NextRequest, NextResponse } from "next/server";
import { activeClient } from "@/lib/activeClient";
import { readBody, reservationRequest, reservationResult } from "@/lib/contracts";
import { reserveTimes } from "@/lib/reservations";

/** Reserves times in a branch week for due recurring customers. Reservations are planning marks the scheduler does not see. */
export async function POST(req: NextRequest) {
  const parsed = await readBody(req, reservationRequest);
  if (!parsed.success) return NextResponse.json({ error: "Invalid reservation request" }, { status: 400 });
  const client = await activeClient(req);
  const result = await reserveTimes(client.id, parsed.data);
  if ("error" in result) return NextResponse.json({ error: result.error }, { status: result.status });
  return NextResponse.json(reservationResult.parse(result));
}
