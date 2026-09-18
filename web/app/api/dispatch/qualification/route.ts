import { NextRequest, NextResponse } from "next/server";
import { EngineError, updateQualification } from "@/lib/engineClient";

export async function POST(req: NextRequest) {
  const body = await req.json() as { technicianId?: string; serviceId?: string; qualified?: boolean };
  if (!body.technicianId || !body.serviceId || typeof body.qualified !== "boolean")
    return NextResponse.json({ error: "Invalid qualification" }, { status: 400 });
  try { return NextResponse.json(await updateQualification(body as { technicianId: string; serviceId: string; qualified: boolean })); }
  catch (error) {
    if (error instanceof EngineError) return NextResponse.json({ error: error.message }, { status: error.status });
    throw error;
  }
}
