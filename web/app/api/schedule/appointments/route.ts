import { NextRequest, NextResponse } from "next/server";
import { cancelAppointment, EngineError } from "@/lib/engineClient";

export async function DELETE(request: NextRequest) {
  let body: unknown;
  try {
    body = await request.json();
  } catch {
    return NextResponse.json({ error: "Request body must be valid JSON." }, { status: 400 });
  }

  const appointmentId =
    body && typeof body === "object" && "appointmentId" in body
      ? (body as { appointmentId?: unknown }).appointmentId
      : undefined;
  if (typeof appointmentId !== "string" || !appointmentId) {
    return NextResponse.json({ error: "Missing appointmentId." }, { status: 400 });
  }

  try {
    return NextResponse.json(await cancelAppointment({ appointment_id: appointmentId }));
  } catch (error) {
    if (error instanceof EngineError) {
      return NextResponse.json({ error: error.message }, { status: error.status });
    }
    console.error("Appointment cancellation failed", error);
    return NextResponse.json({ error: "Appointment cancellation failed." }, { status: 500 });
  }
}
