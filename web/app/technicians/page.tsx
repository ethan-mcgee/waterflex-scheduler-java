import { prisma } from "@/lib/prisma";
import { addCalendarDays, mondayOfWeek, todayInTz } from "@/lib/date";
import TechnicianRoster from "./TechnicianRoster";
import { validateVersions } from "@/lib/technicianAvailability";

export const dynamic = "force-dynamic";

export default async function TechniciansPage() {
  const today = todayInTz("America/Chicago");
  const weekEnd = addCalendarDays(mondayOfWeek(today), 6);
  const [technicians, services, metros, absences] = await Promise.all([
    prisma.technician.findMany({ include: { qualifications: true, availabilityVersions: { include: { days: true }, orderBy: { effectiveDate: "asc" } }, shiftOverrides: {
      where: { serviceDate: { gte: new Date(`${today}T00:00:00Z`) } }, orderBy: { serviceDate: "asc" }, take: 101,
    } }, orderBy: { name: "asc" } }),
    prisma.serviceCatalog.findMany({ where: { active: true }, orderBy: { sortOrder: "asc" } }),
    prisma.metro.findMany({ select: { id: true, name: true }, orderBy: { name: "asc" } }),
    prisma.timeOffInterval.findMany({ where: {
      request: { status: "APPROVED" },
      serviceDate: { gte: new Date(`${mondayOfWeek(today)}T00:00:00Z`), lte: new Date(`${weekEnd}T00:00:00Z`) },
    }, select: { request: { select: { technicianId: true } }, serviceDate: true, startMin: true, endMin: true } }),
  ]);
  return <TechnicianRoster technicians={technicians.map((tech) => {
    const versions = validateVersions(tech.availabilityVersions.map(version => ({ effectiveDate: version.effectiveDate, days: version.days })));
    return ({
      id: tech.id, name: tech.name, active: tech.active,
      email: tech.email, phone: tech.phone, bio: tech.bio, color: tech.color,
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
    }); })} services={services.map((service) => ({ id: service.id, name: service.name }))} metros={metros} today={today}
    absences={absences.map((item) => ({ technicianId: item.request.technicianId, date: item.serviceDate.toISOString().slice(0, 10),
      startMin: validInterval(item.startMin, item.endMin) ? item.startMin : null, endMin: validInterval(item.startMin, item.endMin) ? item.endMin : null }))} />;
}

function validInterval(start: number, end: number): boolean {
  return Number.isInteger(start) && Number.isInteger(end) && start >= 0 && end <= 1440 && start < end;
}
