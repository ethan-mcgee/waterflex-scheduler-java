import { NextResponse } from "next/server";
import { TimeOffRefused } from "./apiTimeOff";

/** Runs one API-mode time-off or availability step for a route, turning refusals into error responses. */
export async function apiTimeOffStep(step: () => Promise<unknown>): Promise<NextResponse> {
  try {
    return NextResponse.json(await step());
  } catch (error) {
    if (error instanceof TimeOffRefused) return NextResponse.json({ error: error.message }, { status: error.status });
    throw error;
  }
}
