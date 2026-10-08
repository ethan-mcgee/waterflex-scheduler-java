import { prisma } from "@/lib/prisma";
import { activeClient } from "@/lib/activeClient";
import { todayInTz } from "@/lib/date";
import TechnicianRoster from "./TechnicianRoster";
import { validateVersions } from "@/lib/technicianAvailability";

export const dynamic = "force-dynamic";

export default async function TechniciansPage() {
  const clientId = (await activeClient()).id;
  const today = todayInTz("America/Chicago");
  const [technicians, services, metros, dealerships, depots] = await Promise.all([
    prisma.technician.findMany({ where: { clientId }, include: { qualifications: true, depotAssignments: { include: { depot: true }, orderBy: { effectiveDate: "asc" } }, availabilityVersions: { include: { days: true }, orderBy: { effectiveDate: "asc" } }, shiftOverrides: {
      where: { serviceDate: { gte: new Date(`${today}T00:00:00Z`) } }, orderBy: { serviceDate: "asc" }, take: 101,
    } }, orderBy: { name: "asc" } }),
    prisma.serviceCatalog.findMany({ where: { active: true }, orderBy: { sortOrder: "asc" } }),
    prisma.metro.findMany({ where: { depots: { none: { dealership: { clientId: { not: clientId } } } } }, select: { id: true, name: true }, orderBy: { name: "asc" } }),
    prisma.dealership.findMany({ where: { clientId }, select: { id: true, name: true }, orderBy: { name: "asc" } }),
    prisma.depot.findMany({ where: { dealership: { clientId } }, select: { id: true, name: true, dealershipId: true, metroId: true }, orderBy: { name: "asc" } }),
  ]);
  return <TechnicianRoster technicians={technicians.map((tech) => {
    const versions = validateVersions(tech.availabilityVersions.map(version => ({ effectiveDate: version.effectiveDate, days: version.days })));
    return ({
      id: tech.id, name: tech.name, active: tech.active,
      depotAssignments: tech.depotAssignments.map(item => ({ effectiveDate: item.effectiveDate.toISOString().slice(0, 10),
        depotId: item.depotId, dealershipId: item.depot.dealershipId, metroId: item.depot.metroId })),
      email: tech.email, phone: tech.phone, bio: tech.bio, color: tech.color,
      homeAddress: tech.homeAddressLine1 != null && tech.homeAddressCity != null && tech.homeAddressState != null && tech.homeAddressPostalCode != null
        ? { line1: tech.homeAddressLine1, city: tech.homeAddressCity, state: tech.homeAddressState, postalCode: tech.homeAddressPostalCode } : null,
      availabilityVersions: versions.map(version => {
        const first = version.days.find(day => day.available);
        if (!first || first.shiftStartMin == null || first.shiftEndMin == null) throw new Error(`Invalid availability for ${tech.id}`);
        const editorStart = first.shiftStartMin, editorEnd = first.shiftEndMin;
        return { effectiveDate: version.effectiveDate.toISOString().slice(0, 10), days: version.days.map(day => ({
          dayOfWeek: day.dayOfWeek, available: day.available,
          startMin: day.shiftStartMin ?? editorStart, endMin: day.shiftEndMin ?? editorEnd,
        })) };
      }),
      shiftStartMin: validInterval(tech.shiftStartMin, tech.shiftEndMin) ? tech.shiftStartMin : null,
      shiftEndMin: validInterval(tech.shiftStartMin, tech.shiftEndMin) ? tech.shiftEndMin : null,
      qualifications: tech.qualifications.map((item) => item.serviceId),
      overridesTruncated: tech.shiftOverrides.length > 100,
      overrides: tech.shiftOverrides.slice(0, 100).map((item) => ({ date: item.serviceDate.toISOString().slice(0, 10), available: item.available,
        shiftStartMin: item.shiftStartMin, shiftEndMin: item.shiftEndMin })),
    }); })} services={services.map((service) => ({ id: service.id, name: service.name }))} metros={metros} dealerships={dealerships} depots={depots} today={today} />;
}

function validInterval(start: number, end: number): boolean {
  return Number.isInteger(start) && Number.isInteger(end) && start >= 0 && end <= 1440 && start < end;
}
