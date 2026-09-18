import { NextRequest, NextResponse } from "next/server";
import { EngineError, updateAvailability } from "@/lib/engineClient";

export async function POST(req: NextRequest) {
  const body = await req.json() as { technicianId?: string; date?: string; available?: boolean; shiftStartMin?: number | null; shiftEndMin?: number | null };
  if (!body.technicianId || !/^\d{4}-\d{2}-\d{2}$/.test(body.date ?? "") || typeof body.available !== "boolean")
    return NextResponse.json({ error: "Invalid override" }, { status: 400 });
  if (body.available && (!Number.isInteger(body.shiftStartMin) || !Number.isInteger(body.shiftEndMin)
      || body.shiftStartMin! < 0 || body.shiftEndMin! > 1440 || body.shiftStartMin! >= body.shiftEndMin!))
    return NextResponse.json({ error: "Invalid shift hours" }, { status: 400 });
  try { return NextResponse.json(await updateAvailability(body as { technicianId: string; date: string; available: boolean; shiftStartMin?: number | null; shiftEndMin?: number | null })); }
  catch (error) {
    if (error instanceof EngineError) return NextResponse.json({ error: error.message }, { status: error.status });
    throw error;
  }
}
