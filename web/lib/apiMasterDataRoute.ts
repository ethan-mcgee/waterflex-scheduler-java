import { NextResponse } from "next/server";
import { MasterDataRefused } from "./apiMasterDataCore";

/** Runs one master-data change through the public API for a route, turning refusals into error responses. */
export async function masterDataStep(step: () => Promise<unknown>): Promise<NextResponse> {
  try {
    return NextResponse.json(await step());
  } catch (error) {
    if (error instanceof MasterDataRefused) return NextResponse.json({ error: error.message }, { status: error.status });
    throw error;
  }
}
