import { NextRequest, NextResponse } from "next/server";
import { prisma } from "@/lib/prisma";

export async function POST(req: NextRequest) {
  const body = await req.json() as { technicianId?: string; serviceId?: string; qualified?: boolean };
  if (!body.technicianId || !body.serviceId || typeof body.qualified !== "boolean")
    return NextResponse.json({ error: "Invalid qualification" }, { status: 400 });
  const where = { technicianId_serviceId: { technicianId: body.technicianId, serviceId: body.serviceId } };
  if (body.qualified) await prisma.technicianQualification.upsert({ where, update: {}, create: { technicianId: body.technicianId, serviceId: body.serviceId } });
  else await prisma.technicianQualification.deleteMany({ where: { technicianId: body.technicianId, serviceId: body.serviceId } });
  return NextResponse.json({ success: true });
}
