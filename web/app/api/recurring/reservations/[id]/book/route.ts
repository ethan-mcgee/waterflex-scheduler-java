import { NextRequest, NextResponse } from "next/server";
import { activeClient } from "@/lib/activeClient";
import { readBody, reservationBookRequest, reservationBooked } from "@/lib/contracts";
import { geocoderRefusal } from "@/lib/apiJobIntake";
import { bookReservation } from "@/lib/reservations";

/** Takes a reservation's work order in, ready to place on the reserved day. */
export async function POST(req: NextRequest, { params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;
  const parsed = await readBody(req, reservationBookRequest);
  if (!parsed.success) return NextResponse.json({ error: "Enter the customer's first and last name, email and phone." }, { status: 400 });
  const client = await activeClient(req);
  try {
    const result = await bookReservation(client.id, id, parsed.data);
    if ("error" in result) return NextResponse.json({ error: result.error }, { status: result.status });
    return NextResponse.json(reservationBooked.parse(result));
  } catch (error) {
    const refusal = geocoderRefusal(error);
    if (refusal === null) throw error;
    return NextResponse.json(refusal.body, { status: refusal.status });
  }
}
