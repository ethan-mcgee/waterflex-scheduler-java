import { prisma } from "@/lib/prisma";
import { haversineMiles } from "@/lib/geo";

// MVP service-area check: within the metro's configured radius of any depot.
// This lets an operating area include nearby communities outside the urban
// core without baking one city's coverage policy into application code.

export async function resolveMetroForLocation(lat: number, lng: number): Promise<string | null> {
  const depots = await prisma.depot.findMany({
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
