import { NextRequest, NextResponse } from "next/server";
import { prisma } from "@/lib/prisma";
import { activeClient } from "@/lib/activeClient";
import { readBody, technicianProfileRequest } from "@/lib/contracts";
import { currentMetroIdFor, resolveHomeLocation, type HomeLocation } from "@/lib/technicianHome";

export async function PATCH(request: NextRequest, { params }: { params: { id: string } }) {
  const parsed = await readBody(request, technicianProfileRequest);
  if (!parsed.success) return NextResponse.json({ error: "Invalid technician profile" }, { status: 400 });
  const existing = await prisma.technician.findFirst({ where: { id: params.id, clientId: (await activeClient(request)).id }, select: { id: true } });
  if (!existing) return NextResponse.json({ error: "Technician not found" }, { status: 404 });
  const { address, confirmedPin, manuallyConfirmed, ...profile } = parsed.data;
  let home: HomeLocation | undefined;
  if (address !== undefined && confirmedPin !== undefined && manuallyConfirmed !== undefined) {
    const metroId = await currentMetroIdFor(params.id);
    if (!metroId) return NextResponse.json({ error: "Assign the technician to a depot before changing the home address" }, { status: 409 });
    const resolved = await resolveHomeLocation({ address, confirmedPin, manuallyConfirmed, metroId });
    if (!resolved.ok) return NextResponse.json({ error: resolved.error }, { status: resolved.status });
    home = resolved.location;
  }
  await prisma.technician.update({ where: { id: params.id }, data: { ...profile, ...home } });
  return NextResponse.json({ success: true });
}
