import { NextRequest, NextResponse } from "next/server";
import { createTechnicianRequest, readBody } from "@/lib/contracts";
import { searchAddress } from "@/lib/geocode";
import { resolveMetroForLocation } from "@/lib/serviceArea";
import { prisma } from "@/lib/prisma";

export async function POST(request: NextRequest) {
  const parsed = await readBody(request, createTechnicianRequest);
  if (!parsed.success) return NextResponse.json({ error: "Invalid technician profile or weekly availability" }, { status: 400 });
  const input = parsed.data;
  const [depot, services, candidates] = await Promise.all([
    prisma.depot.findUnique({ where: { id: input.depotId } }),
    prisma.serviceCatalog.findMany({ where: { id: { in: input.qualifications }, active: true }, select: { id: true } }),
    searchAddress(input.address),
  ]);
  if (!depot || services.length !== input.qualifications.length)
    return NextResponse.json({ error: "Invalid metro, dealership, depot, or qualification" }, { status: 400 });
  const selected = candidates.find(candidate => Math.abs(candidate.lat - input.confirmedPin.lat) < 0.0001 &&
    Math.abs(candidate.lng - input.confirmedPin.lng) < 0.0001);
  if (!selected) return NextResponse.json({ error: "Choose a verified address pin" }, { status: 422 });
  const locationMetro = await resolveMetroForLocation(selected.lat, selected.lng);
  if (locationMetro !== depot.metroId) return NextResponse.json({ error: "The address is outside the selected depot's metro service area" }, { status: 422 });
  const firstAvailable = input.days.find(day => day.available);
  if (!firstAvailable || firstAvailable.shiftStartMin == null || firstAvailable.shiftEndMin == null)
    return NextResponse.json({ error: "Valid shift hours are required" }, { status: 400 });
  const technician = await prisma.technician.create({ data: {
    name: input.name, email: input.email ?? null, phone: input.phone ?? null,
    bio: input.bio ?? null, color: input.color, homeLat: selected.lat, homeLng: selected.lng,
    homeAddressLine1: input.address.line1, homeAddressCity: input.address.city,
    homeAddressState: input.address.state, homeAddressPostalCode: input.address.postalCode,
    shiftStartMin: firstAvailable.shiftStartMin, shiftEndMin: firstAvailable.shiftEndMin,
    qualifications: { create: input.qualifications.map(serviceId => ({ serviceId })) },
    depotAssignments: { create: { depotId: depot.id, effectiveDate: new Date("1900-01-01T00:00:00Z") } },
    availabilityVersions: { create: { effectiveDate: new Date("1900-01-01T00:00:00Z"),
      days: { create: input.days.map(day => ({ dayOfWeek: day.dayOfWeek, available: day.available,
        shiftStartMin: day.shiftStartMin, shiftEndMin: day.shiftEndMin })) } } },
  }, select: { id: true } });
  return NextResponse.json({ success: true, id: technician.id }, { status: 201 });
}
