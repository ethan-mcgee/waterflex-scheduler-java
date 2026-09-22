import type { PrismaClient } from "@prisma/client";
import { OMAHA_METRO_ID, OMAHA_TIMEZONE } from "./fakeDataCore";
import { initialAvailability } from "./technicianAvailability";
import { technicianColor } from "./technicianColor";

const OMAHA_DEPOT_ID = "depot-omaha-main";

const OMAHA_TECHNICIANS = [
  { id: "tech-1", name: "Alex Rivera", homeLat: 41.1544, homeLng: -96.0422 },
  { id: "tech-2", name: "Jordan Lee", homeLat: 41.2619, homeLng: -95.8608 },
  { id: "tech-3", name: "Sam Patel", homeLat: 41.2864, homeLng: -96.2345 },
] as const;

const OMAHA_SERVICES = [
  {
    code: "SOFTENER_INSTALL",
    name: "Water Softener Installation",
    description: "Install a new whole-home water softener system.",
    estDurationMin: 180,
    bufferPct: 0.2,
    startsAtDepot: true,
    sortOrder: 1,
  },
  {
    code: "FILTER_SWAP",
    name: "Filter Replacement",
    description: "Swap filter cartridges on an existing system.",
    estDurationMin: 45,
    bufferPct: 0.1,
    startsAtDepot: false,
    sortOrder: 2,
  },
  {
    code: "SYSTEM_INSPECTION",
    name: "Annual System Inspection",
    description: "Routine inspection and water quality test.",
    estDurationMin: 60,
    bufferPct: 0.1,
    startsAtDepot: false,
    sortOrder: 3,
  },
  {
    code: "REPAIR_DIAGNOSTIC",
    name: "Repair / Diagnostic Visit",
    description: "Diagnose and repair a malfunctioning system.",
    estDurationMin: 90,
    bufferPct: 0.25,
    startsAtDepot: true,
    sortOrder: 4,
  },
] as const;

const SCORING_WEIGHTS = [
  { key: "w_drive", value: 1.0 },
  { key: "w_days", value: 0.5 },
  { key: "w_util", value: 0.2 },
  { key: "w_window", value: 0.1 },
  { key: "w_opt_drive", value: 1.0 },
  { key: "w_opt_balance", value: 1.0 },
  { key: "w_opt_overtime", value: 10.0 },
] as const;

export async function ensureOmahaConfiguration(prisma: PrismaClient): Promise<void> {
  const metro = await prisma.metro.upsert({
    where: { id: OMAHA_METRO_ID },
    update: {
      name: "Omaha Metro Area",
      timezone: OMAHA_TIMEZONE,
      stateCode: "NE",
      serviceRadiusMi: 65,
    },
    create: {
      id: OMAHA_METRO_ID,
      name: "Omaha Metro Area",
      timezone: OMAHA_TIMEZONE,
      stateCode: "NE",
      serviceRadiusMi: 65,
    },
  });

  const depot = await prisma.depot.upsert({
    where: { id: OMAHA_DEPOT_ID },
    update: {
      metroId: metro.id,
      name: "Omaha Main Depot",
      lat: 41.2565,
      lng: -95.9345,
    },
    create: {
      id: OMAHA_DEPOT_ID,
      metroId: metro.id,
      name: "Omaha Main Depot",
      lat: 41.2565,
      lng: -95.9345,
    },
  });

  for (const technician of OMAHA_TECHNICIANS) {
    const configuration = {
      metroId: metro.id,
      depotId: depot.id,
      name: technician.name,
      homeLat: technician.homeLat,
      homeLng: technician.homeLng,
      startLocation: "HOME" as const,
      endLocation: "HOME" as const,
      shiftStartMin: 8 * 60,
      shiftEndMin: 17 * 60,
      maxDailyMinutes: 540,
      maxOvertimeMinutes: 60,
      active: true,
    };
    await prisma.technician.upsert({
      where: { id: technician.id },
      update: configuration,
      create: { id: technician.id, ...configuration, color: technicianColor(technician.id), availabilityVersions: initialAvailability(8 * 60, 17 * 60) },
    });
  }

  for (const service of OMAHA_SERVICES) {
    await prisma.serviceCatalog.upsert({
      where: { code: service.code },
      update: { ...service, active: true },
      create: { ...service, active: true },
    });
  }

  const serviceIds = await prisma.serviceCatalog.findMany({ select: { id: true } });
  await prisma.technicianQualification.createMany({
    data: OMAHA_TECHNICIANS.flatMap((technician) => serviceIds.map((service) => ({
      technicianId: technician.id, serviceId: service.id,
    }))),
    skipDuplicates: true,
  });

  for (const weight of SCORING_WEIGHTS) {
    await prisma.scoringWeight.upsert({
      where: { key: weight.key },
      update: { value: weight.value },
      create: weight,
    });
  }
}
