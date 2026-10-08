import { prisma } from "@/lib/prisma";
import { haversineMiles } from "@/lib/geo";
import { coverageBounds } from "./coverage";
import { serviceAreaCircle } from "./contracts";

/** The client's service area, or null when the client has no depots yet (nothing can be booked). */
export async function bookingServiceArea(clientId: string) {
  const depots = await prisma.depot.findMany({ where: { dealership: { clientId } },
    select: { lat: true, lng: true, metro: { select: { serviceRadiusMi: true } } } });
  if (depots.length === 0) return null;
  const circles = depots.map(d => serviceAreaCircle.parse({ lat: d.lat, lng: d.lng, radiusMi: d.metro.serviceRadiusMi }));
  return { circles, bounds: coverageBounds(circles) };
}

// MVP service-area check: within the metro's configured radius of any depot.
// This lets an operating area include nearby communities outside the urban
// core without baking one city's coverage policy into application code.

export async function resolveMetroForLocation(lat: number, lng: number, clientId: string): Promise<string | null> {
  const depots = await prisma.depot.findMany({
    where: { dealership: { clientId } },
    select: {
      metroId: true,
      lat: true,
      lng: true,
      metro: { select: { serviceRadiusMi: true } },
    },
  });

  let bestMetroId: string | null = null;
  let bestDistance = Infinity;
  for (const depot of depots) {
    const distance = haversineMiles(lat, lng, depot.lat, depot.lng);
    if (distance <= depot.metro.serviceRadiusMi && distance < bestDistance) {
      bestDistance = distance;
      bestMetroId = depot.metroId;
    }
  }

  return bestMetroId;
}

export async function isWithinMetroServiceArea(lat: number, lng: number, metroId: string): Promise<boolean> {
  const depots = await prisma.depot.findMany({ where: { metroId }, select: {
    lat: true, lng: true, metro: { select: { serviceRadiusMi: true } },
  } });
  return depots.some(depot => haversineMiles(lat, lng, depot.lat, depot.lng) <= depot.metro.serviceRadiusMi);
}
