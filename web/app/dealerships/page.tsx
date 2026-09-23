import { prisma } from "@/lib/prisma";
import DealershipSetup from "./DealershipSetup";

export const dynamic = "force-dynamic";

export default async function DealershipsPage() {
  const [metros, depots, dealerships] = await Promise.all([
    prisma.metro.findMany({ select: { id: true, name: true }, orderBy: { name: "asc" } }),
    prisma.depot.findMany({ select: { id: true, metroId: true, name: true, lat: true, lng: true }, orderBy: { name: "asc" } }),
    prisma.dealership.findMany({ include: { depot: true, _count: { select: { technicians: true } } }, orderBy: { name: "asc" } }),
  ]);
  return <DealershipSetup metros={metros} depots={depots} dealerships={dealerships.map(item => ({
    id: item.id, name: item.name, metroId: item.metroId, depotId: item.depotId,
    departure: item.departure, returnTo: item.returnTo, technicianCount: item._count.technicians,
  }))} />;
}
