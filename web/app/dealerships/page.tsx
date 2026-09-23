import { prisma } from "@/lib/prisma";
import { todayInTz } from "@/lib/date";
import DealershipSetup from "./DealershipSetup";

export const dynamic = "force-dynamic";

export default async function DealershipsPage() {
  const today = todayInTz("America/Chicago");
  const todayKey = new Date(`${today}T00:00:00Z`);
  const [metros, depots, dealerships, technicians] = await Promise.all([
    prisma.metro.findMany({ select: { id: true, name: true }, orderBy: { name: "asc" } }),
    prisma.depot.findMany({ include: { endpointPolicies: { orderBy: { effectiveDate: "asc" } } }, orderBy: { name: "asc" } }),
    prisma.dealership.findMany({ orderBy: { name: "asc" } }),
    prisma.technician.findMany({ where: { active: true }, select: { depotAssignments: {
      where: { effectiveDate: { lte: todayKey } }, orderBy: { effectiveDate: "desc" }, take: 1,
      select: { depotId: true, depot: { select: { dealershipId: true } } },
    } } }),
  ]);
  const assignments = technicians.map(item => item.depotAssignments[0]).filter(item => item != null);
  return <DealershipSetup metros={metros} depots={depots.map(item => {
    const policy = item.endpointPolicies.filter(candidate => candidate.effectiveDate <= todayKey).at(-1);
    const upcoming = item.endpointPolicies.find(candidate => candidate.effectiveDate > todayKey);
    if (!policy) throw new Error(`Depot ${item.id} is missing a current route policy`);
    return { id: item.id, dealershipId: item.dealershipId, metroId: item.metroId, name: item.name,
      lat: item.lat, lng: item.lng, departure: policy.departure, returnTo: policy.returnTo,
      policyEffectiveDate: policy.effectiveDate.toISOString().slice(0, 10),
      upcomingPolicy: upcoming ? { departure: upcoming.departure, returnTo: upcoming.returnTo,
        effectiveDate: upcoming.effectiveDate.toISOString().slice(0, 10) } : null,
      technicianCount: assignments.filter(a => a.depotId === item.id).length };
  })} dealerships={dealerships.map(item => ({
    id: item.id, name: item.name, technicianCount: assignments.filter(a => a.depot.dealershipId === item.id).length,
  }))} />;
}
