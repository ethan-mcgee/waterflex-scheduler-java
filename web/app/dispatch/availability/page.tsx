import { prisma } from "@/lib/prisma";
import { addCalendarDays, mondayOfWeek, todayInTz } from "@/lib/date";
import AvailabilityEditor from "./AvailabilityEditor";

export const dynamic = "force-dynamic";

export default async function AvailabilityPage() {
  const today = todayInTz("America/Chicago");
  const weekEnd = addCalendarDays(mondayOfWeek(today), 6);
  const [technicians, services, absences] = await Promise.all([
    prisma.technician.findMany({ include: { qualifications: true, shiftOverrides: {
      where: { serviceDate: { gte: new Date(`${today}T00:00:00Z`) } }, orderBy: { serviceDate: "asc" }, take: 101,
    } }, orderBy: { name: "asc" } }),
    prisma.serviceCatalog.findMany({ where: { active: true }, orderBy: { sortOrder: "asc" } }),
    prisma.timeOffInterval.findMany({ where: {
      request: { status: "APPROVED" },
      serviceDate: { gte: new Date(`${mondayOfWeek(today)}T00:00:00Z`), lte: new Date(`${weekEnd}T00:00:00Z`) },
    }, select: { request: { select: { technicianId: true } }, serviceDate: true, startMin: true, endMin: true } }),
  ]);
  return <AvailabilityEditor technicians={technicians.map((tech) => ({
      id: tech.id, name: tech.name, active: tech.active,
      shiftStartMin: validInterval(tech.shiftStartMin, tech.shiftEndMin) ? tech.shiftStartMin : null,
      shiftEndMin: validInterval(tech.shiftStartMin, tech.shiftEndMin) ? tech.shiftEndMin : null,
      qualifications: tech.qualifications.map((item) => item.serviceId),
      overridesTruncated: tech.shiftOverrides.length > 100,
      overrides: tech.shiftOverrides.slice(0, 100).map((item) => ({ date: item.serviceDate.toISOString().slice(0, 10), available: item.available,
        shiftStartMin: item.shiftStartMin, shiftEndMin: item.shiftEndMin })),
    }))} services={services.map((service) => ({ id: service.id, name: service.name }))} today={today}
    absences={absences.map((item) => ({ technicianId: item.request.technicianId, date: item.serviceDate.toISOString().slice(0, 10),
      startMin: validInterval(item.startMin, item.endMin) ? item.startMin : null, endMin: validInterval(item.startMin, item.endMin) ? item.endMin : null }))} />;
}

function validInterval(start: number, end: number): boolean {
  return Number.isInteger(start) && Number.isInteger(end) && start >= 0 && end <= 1440 && start < end;
}
