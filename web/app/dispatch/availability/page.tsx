import { prisma } from "@/lib/prisma";
import AvailabilityEditor from "./AvailabilityEditor";

export const dynamic = "force-dynamic";

export default async function AvailabilityPage() {
  const [technicians, services] = await Promise.all([
    prisma.technician.findMany({ include: { qualifications: true, shiftOverrides: { orderBy: { serviceDate: "asc" }, take: 20 } }, orderBy: { name: "asc" } }),
    prisma.serviceCatalog.findMany({ where: { active: true }, orderBy: { sortOrder: "asc" } }),
  ]);
  return <AvailabilityEditor technicians={technicians.map((tech) => ({
      id: tech.id, name: tech.name, shiftStartMin: tech.shiftStartMin, shiftEndMin: tech.shiftEndMin,
      qualifications: tech.qualifications.map((item) => item.serviceId),
      overrides: tech.shiftOverrides.map((item) => ({ date: item.serviceDate.toISOString().slice(0, 10), available: item.available,
        shiftStartMin: item.shiftStartMin, shiftEndMin: item.shiftEndMin })),
    }))} services={services.map((service) => ({ id: service.id, name: service.name }))} />;
}
