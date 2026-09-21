import { readBody, qualificationRequest } from "@/lib/contracts";
import { NextRequest, NextResponse } from "next/server";
import { EngineError, updateQualification } from "@/lib/engineClient";

export async function POST(req: NextRequest) {
  const parsed = await readBody(req, qualificationRequest);
  if (!parsed.success) return NextResponse.json({ error: "Invalid request body" }, { status: 400 });
  const body = parsed.data;
  try { return NextResponse.json(await updateQualification(body)); }
  catch (error) {
    if (error instanceof EngineError) return NextResponse.json({ error: error.message }, { status: error.status });
    throw error;
  }
}
