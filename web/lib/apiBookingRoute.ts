import { NextResponse } from "next/server";
import { BookingRefused, searchApiOffers, StaleReceipt } from "./apiBooking";
import { appointmentSearchMessage } from "./appointmentSearch";

/** Runs one API booking step for a route, turning refusals into the booking page's error responses. */
export async function apiBookingStep(clientId: string, jobId: string, step: () => Promise<unknown>): Promise<NextResponse> {
  try {
    return NextResponse.json(await step());
  } catch (error) {
    if (error instanceof BookingRefused) return NextResponse.json({ error: error.message }, { status: error.status });
    if (error instanceof StaleReceipt) {
      // The schedule changed between the snapshot and the write: nothing was written, so offer current times instead.
      console.warn("Booking receipt was stale", error.message);
      try {
        const refreshed = await searchApiOffers(clientId, jobId);
        return NextResponse.json({ ...refreshed, error: appointmentSearchMessage(refreshed.search) ?? "The schedule changed while booking. Please choose a time again." }, { status: 409 });
      } catch (refreshError) {
        if (refreshError instanceof BookingRefused) return NextResponse.json({ error: refreshError.message }, { status: refreshError.status });
        throw refreshError;
      }
    }
    throw error;
  }
}

export function notUsedWithApi(): NextResponse {
  return NextResponse.json({ error: "Not used when booking runs through the scheduling API" }, { status: 404 });
}
