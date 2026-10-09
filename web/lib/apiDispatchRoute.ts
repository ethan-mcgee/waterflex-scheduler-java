import { NextResponse } from "next/server";
import { DispatchRefused } from "./apiDispatch";

/** Runs one API dispatch step for a route, turning refusals into the dispatch board's error responses. */
export async function apiDispatchStep(step: () => Promise<unknown>): Promise<NextResponse> {
  try {
    return NextResponse.json(await step());
  } catch (error) {
    if (error instanceof DispatchRefused) return NextResponse.json({ error: error.message }, { status: error.status });
    throw error;
  }
}
