import { NextResponse } from "next/server";
import { ChangeRefused } from "./apiMasterDataCore";

/** Runs one change to the client's own facts through the public API for a route, turning refusals into error responses. */
export async function changeStep(step: () => Promise<unknown>): Promise<NextResponse> {
  try {
    return NextResponse.json(await step());
  } catch (error) {
    if (error instanceof ChangeRefused) return NextResponse.json({ error: error.message }, { status: error.status });
    throw error;
  }
}
