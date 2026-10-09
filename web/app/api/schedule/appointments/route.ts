import { NextRequest, NextResponse } from "next/server";
import { activeClient } from "@/lib/activeClient";
import { notFound, ownsAppointment } from "@/lib/clientScope";
import { cancelAppointment, EngineError } from "@/lib/engineClient";
import { cancelApiAppointment } from "@/lib/apiCancel";
import { changeStep } from "@/lib/apiChangeRoute";
import { publicApiEnabled } from "@/lib/schedulerApi";

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
  const reason = body && typeof body === "object" && "reason" in body
    ? (body as { reason?: unknown }).reason : undefined;
  if (typeof appointmentId !== "string" || !appointmentId) {
    return NextResponse.json({ error: "Missing appointmentId." }, { status: 400 });
  }
  if (typeof reason !== "string" || !reason.trim()) return NextResponse.json({ error: "Cancellation reason required." }, { status: 400 });

  const clientId = (await activeClient(request)).id;
  if (!(await ownsAppointment(clientId, appointmentId))) return notFound("Appointment");
  if (publicApiEnabled()) return changeStep(() => cancelApiAppointment(clientId, appointmentId, reason));
  try {
    return NextResponse.json(await cancelAppointment({ appointment_id: appointmentId, reason }));
  } catch (error) {
    if (error instanceof EngineError) {
      return NextResponse.json({ error: error.message }, { status: error.status });
    }
    console.error("Appointment cancellation failed", error);
    return NextResponse.json({ error: "Appointment cancellation failed." }, { status: 500 });
  }
}
