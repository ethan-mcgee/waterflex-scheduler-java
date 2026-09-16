import { NextRequest, NextResponse } from "next/server";
import { reassignAppointment, EngineError } from "@/lib/engineClient";

export async function POST(req: NextRequest) {
  const body = (await req.json()) as { appointmentId?: string; newTechnicianId?: string };
  if (!body.appointmentId || !body.newTechnicianId) {
    return NextResponse.json({ error: "Missing appointmentId or newTechnicianId" }, { status: 400 });
  }

  try {
    const result = await reassignAppointment({
      appointment_id: body.appointmentId,
      new_technician_id: body.newTechnicianId,
    });
    return NextResponse.json(result);
  } catch (err) {
    if (err instanceof EngineError) {
      // 409 from the engine means no feasible position preserves the
      // promised window — a normal rejection, not a server error.
      return NextResponse.json({ error: err.message }, { status: err.status });
    }
    throw err;
  }
}
