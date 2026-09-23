import { prisma } from "@/lib/prisma";
import DealershipSetup from "./DealershipSetup";

export const dynamic = "force-dynamic";

export default async function DealershipsPage() {
  const [metros, depots, dealerships] = await Promise.all([
    prisma.metro.findMany({ select: { id: true, name: true }, orderBy: { name: "asc" } }),
    prisma.depot.findMany({ include: { endpointPolicies: { orderBy: { effectiveDate: "desc" }, take: 1 } }, orderBy: { name: "asc" } }),
    prisma.dealership.findMany({ orderBy: { name: "asc" } }),
  ]);
  const assignments = await prisma.technicianDepotAssignment.findMany({ include: { depot: { select: { dealershipId: true } } } });
  return <DealershipSetup metros={metros} depots={depots.map(item => {
    const policy = item.endpointPolicies[0];
    if (!policy) throw new Error(`Depot ${item.id} is missing a route policy`);
    return { id: item.id, dealershipId: item.dealershipId, metroId: item.metroId, name: item.name,
      lat: item.lat, lng: item.lng, departure: policy.departure, returnTo: policy.returnTo,
      policyEffectiveDate: policy.effectiveDate.toISOString().slice(0, 10) };
  })} dealerships={dealerships.map(item => ({
    id: item.id, name: item.name, technicianCount: new Set(assignments.filter(a => a.depot.dealershipId === item.id).map(a => a.technicianId)).size,
  }))} />;
}
