import { NextRequest, NextResponse } from "next/server";
import { prisma } from "@/lib/prisma";

export async function POST(req: NextRequest) {
  const body = await req.json() as { technicianId?: string; date?: string; available?: boolean; shiftStartMin?: number | null; shiftEndMin?: number | null };
  if (!body.technicianId || !/^\d{4}-\d{2}-\d{2}$/.test(body.date ?? "") || typeof body.available !== "boolean")
    return NextResponse.json({ error: "Invalid override" }, { status: 400 });
  if (body.available && (!Number.isInteger(body.shiftStartMin) || !Number.isInteger(body.shiftEndMin)
      || body.shiftStartMin! < 0 || body.shiftEndMin! > 1440 || body.shiftStartMin! >= body.shiftEndMin!))
    return NextResponse.json({ error: "Invalid shift hours" }, { status: 400 });
  const serviceDate = new Date(`${body.date}T00:00:00.000Z`);
  const data = { available: body.available, shiftStartMin: body.available ? body.shiftStartMin : null,
    shiftEndMin: body.available ? body.shiftEndMin : null };
  const saved = await prisma.technicianShiftOverride.upsert({
    where: { technicianId_serviceDate: { technicianId: body.technicianId, serviceDate } },
    update: data, create: { technicianId: body.technicianId, serviceDate, ...data },
  });
  return NextResponse.json({ id: saved.id });
}
