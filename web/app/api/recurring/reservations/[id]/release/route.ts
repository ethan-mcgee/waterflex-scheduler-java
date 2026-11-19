import { NextRequest, NextResponse } from "next/server";
import { activeClient } from "@/lib/activeClient";
import { reservationReleased } from "@/lib/contracts";
import { releaseReservation } from "@/lib/reservations";

/** Gives a reserved time back; the customer is due again. */
export async function POST(req: NextRequest, { params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;
  const client = await activeClient(req);
  const result = await releaseReservation(client.id, id);
  if ("error" in result) return NextResponse.json({ error: result.error }, { status: result.status });
  return NextResponse.json(reservationReleased.parse(result));
}
