import { NextRequest, NextResponse } from "next/server";
import { createTechnicianRequest, readBody } from "@/lib/contracts";
import { resolveHomeLocation } from "@/lib/technicianHome";
import { prisma } from "@/lib/prisma";

export async function POST(request: NextRequest) {
  const parsed = await readBody(request, createTechnicianRequest);
  if (!parsed.success) return NextResponse.json({ error: "Invalid technician profile or weekly availability" }, { status: 400 });
  const input = parsed.data;
  const [depot, services] = await Promise.all([
    prisma.depot.findUnique({ where: { id: input.depotId } }),
    prisma.serviceCatalog.findMany({ where: { id: { in: input.qualifications }, active: true }, select: { id: true } }),
  ]);
  if (!depot || services.length !== input.qualifications.length)
    return NextResponse.json({ error: "Invalid metro, dealership, depot, or qualification" }, { status: 400 });
  const resolved = await resolveHomeLocation({ address: input.address, confirmedPin: input.confirmedPin,
    manuallyConfirmed: input.manuallyConfirmed, metroId: depot.metroId });
  if (!resolved.ok) return NextResponse.json({ error: resolved.error }, { status: resolved.status });
  const firstAvailable = input.days.find(day => day.available);
  if (!firstAvailable || firstAvailable.shiftStartMin == null || firstAvailable.shiftEndMin == null)
    return NextResponse.json({ error: "Valid shift hours are required" }, { status: 400 });
  const technician = await prisma.technician.create({ data: {
    name: input.name, email: input.email ?? null, phone: input.phone ?? null,
    bio: input.bio ?? null, color: input.color, ...resolved.location,
    shiftStartMin: firstAvailable.shiftStartMin, shiftEndMin: firstAvailable.shiftEndMin,
    qualifications: { create: input.qualifications.map(serviceId => ({ serviceId })) },
    depotAssignments: { create: { depotId: depot.id, effectiveDate: new Date("1900-01-01T00:00:00Z") } },
    availabilityVersions: { create: { effectiveDate: new Date("1900-01-01T00:00:00Z"),
      days: { create: input.days.map(day => ({ dayOfWeek: day.dayOfWeek, available: day.available,
        shiftStartMin: day.shiftStartMin, shiftEndMin: day.shiftEndMin })) } } },
  }, select: { id: true } });
  return NextResponse.json({ success: true, id: technician.id }, { status: 201 });
}
