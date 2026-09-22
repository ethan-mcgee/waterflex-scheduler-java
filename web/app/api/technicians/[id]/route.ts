import { NextRequest, NextResponse } from "next/server";
import { prisma } from "@/lib/prisma";
import { readBody, technicianProfileRequest } from "@/lib/contracts";

export async function PATCH(request: NextRequest, { params }: { params: { id: string } }) {
  const parsed = await readBody(request, technicianProfileRequest);
  if (!parsed.success) return NextResponse.json({ error: "Invalid technician profile" }, { status: 400 });
  const existing = await prisma.technician.findUnique({ where: { id: params.id }, select: { id: true } });
  if (!existing) return NextResponse.json({ error: "Technician not found" }, { status: 404 });
  await prisma.technician.update({ where: { id: params.id }, data: parsed.data });
  return NextResponse.json({ success: true });
}
