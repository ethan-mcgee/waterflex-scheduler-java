import { PrismaClient } from "@prisma/client";
import { readFileSync } from "node:fs";
import { z } from "zod";
import { coverageBounds } from "../lib/coverage";
import { locationBounds, serviceAreaCircle } from "../lib/contracts";

const prisma = new PrismaClient();
async function main() {
  const depots = await prisma.depot.findMany({ select: { lat: true, lng: true, metro: { select: { serviceRadiusMi: true } } } });
  const circles = depots.map(d => serviceAreaCircle.parse({ lat: d.lat, lng: d.lng, radiusMi: d.metro.serviceRadiusMi }));
  const coverage = { circles, marginMi: 10, bounds: coverageBounds(circles, 10) };
  const file = process.argv[2];
  if (file) {
    const manifest = z.object({ coverage: z.object({ bounds: locationBounds }) }).parse(JSON.parse(readFileSync(file, "utf8")));
    const b = manifest.coverage.bounds, current = coverage.bounds;
    if (b.west > current.west || b.east < current.east || b.south > current.south || b.north < current.north)
      throw new Error("Prepared maps do not cover current depots, radii, and preparation margin");
  }
  console.log(JSON.stringify(coverage));
}
main().catch(error => { console.error(error); process.exitCode = 1; }).finally(() => prisma.$disconnect());
