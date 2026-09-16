import { randomBytes } from "node:crypto";
import type { Prisma } from "@prisma/client";
import { confirmHold, selectOffer, EngineError, requestSlots, type SlotOffer } from "./engineClient";
import { addCalendarDays, localMidnightUtc } from "./date";
import {
  buildCallPlans,
  candidateDates,
  createSeededRandom,
  dateFromFakeExternalId,
  fakeExternalId,
  FAKE_DATA_PREFIX,
  FAKE_SERVICE_CODES,
  type FakeDataInput,
  type FakeLocation,
  type FakeServiceCode,
  OMAHA_FAKE_LOCATIONS,
  OMAHA_METRO_ID,
  OMAHA_TIMEZONE,
  randomLocationForCall,
  validateFakeDataInput,
} from "./fakeDataCore";
import { ensureOmahaConfiguration } from "./omahaConfiguration";
import { prisma } from "./prisma";

const TWO_HOURS_MS = 2 * 60 * 60 * 1_000;
const CONFIRM_ATTEMPTS = 4;

interface OmahaConfiguration {
  services: Map<FakeServiceCode, { id: string; name: string; durationMin: number }>;
}

export interface FakeDataSummary {
  requested: number;
  created: number;
  skipped: number;
  seed: number;
  startDate: string;
  endDate: string;
  byDate: Record<string, number>;
  byService: Record<string, number>;
  warnings: string[];
}

export interface FakeDataClearSummary {
  removed: number;
  startDate: string;
  endDate: string;
}

async function loadOmahaConfiguration(): Promise<OmahaConfiguration> {
  const [metro, depotCount, technicianCount, services] = await Promise.all([
    prisma.metro.findUnique({ where: { id: OMAHA_METRO_ID } }),
    prisma.depot.count({ where: { metroId: OMAHA_METRO_ID } }),
    prisma.technician.count({ where: { metroId: OMAHA_METRO_ID, active: true } }),
    prisma.serviceCatalog.findMany({ where: { code: { in: [...FAKE_SERVICE_CODES] }, active: true } }),
  ]);
  if (!metro || metro.timezone !== OMAHA_TIMEZONE || depotCount === 0 || technicianCount === 0) {
    throw new Error("The Omaha metro, depot, timezone, and active technicians must be configured first.");
  }
  const byCode = new Map(
    services.map((service) => [
      service.code as FakeServiceCode,
      {
        id: service.id,
        name: service.name,
        durationMin: Math.round(service.estDurationMin * (1 + service.bufferPct)),
      },
    ])
  );
  const missing = FAKE_SERVICE_CODES.filter((code) => !byCode.has(code));
  if (missing.length > 0) throw new Error(`Active Omaha services are missing: ${missing.join(", ")}.`);
  return { services: byCode };
}

function inRange(date: string, startDate: string, endDate: string): boolean {
  return date >= startDate && date <= endDate;
}

async function cleanupFakeDataValidated(startDate: string, endDate: string): Promise<number> {
  const dayStart = localMidnightUtc(startDate, OMAHA_TIMEZONE);
  const dayAfterEnd = localMidnightUtc(addCalendarDays(endDate, 1), OMAHA_TIMEZONE);
  const candidates = await prisma.job.findMany({
    where: { externalId: { startsWith: FAKE_DATA_PREFIX } },
    select: {
      id: true,
      externalId: true,
      customerId: true,
      addressId: true,
      appointment: {
        select: { id: true, technicianId: true, serviceDate: true },
      },
    },
  });
  const jobs = candidates.filter((job) => {
    if (job.appointment) {
      return job.appointment.serviceDate >= dayStart && job.appointment.serviceDate < dayAfterEnd;
    }
    const encodedDate = dateFromFakeExternalId(job.externalId);
    return encodedDate !== null && inRange(encodedDate, startDate, endDate);
  });
  if (jobs.length === 0) return 0;

  const jobIds = jobs.map((job) => job.id);
  const appointmentIds = jobs.flatMap((job) => (job.appointment ? [job.appointment.id] : []));
  const aggregateIds = [...jobIds, ...appointmentIds, ...jobs.map((job) => job.customerId)];
  const addressIds = [...new Set(jobs.map((job) => job.addressId))];
  const customerIds = [...new Set(jobs.map((job) => job.customerId))];
  const affectedDays = new Map<string, { technicianId: string; serviceDate: Date }>();
  for (const job of jobs) {
    if (job.appointment) {
      const key = `${job.appointment.technicianId}:${job.appointment.serviceDate.toISOString()}`;
      affectedDays.set(key, {
        technicianId: job.appointment.technicianId,
        serviceDate: job.appointment.serviceDate,
      });
    }
  }

  await prisma.$transaction(async (tx) => {
    await tx.outboundEvent.deleteMany({ where: { aggregateId: { in: aggregateIds } } });
    await tx.slotHold.deleteMany({ where: { jobId: { in: jobIds } } });
    await tx.bookingOptimization.deleteMany({ where: { jobId: { in: jobIds } } });
    await tx.appointment.deleteMany({ where: { jobId: { in: jobIds } } });
    await tx.job.deleteMany({ where: { id: { in: jobIds } } });
    await tx.address.deleteMany({ where: { id: { in: addressIds } } });
    await tx.customer.deleteMany({ where: { id: { in: customerIds } } });

    for (const { technicianId, serviceDate } of affectedDays.values()) {
      const survivors = await tx.appointment.findMany({
        where: { technicianId, serviceDate },
        orderBy: [{ plannedStart: "asc" }, { id: "asc" }],
        select: { id: true },
      });
      for (const [sequence, survivor] of survivors.entries()) {
        await tx.appointment.update({ where: { id: survivor.id }, data: { sequence } });
      }
      await tx.scheduleDay.updateMany({
        where: { technicianId, serviceDate },
        data: { version: { increment: 1 } },
      });
    }
  });
  return jobs.length;
}

export async function clearFakeData(startDate: string, endDate: string): Promise<FakeDataClearSummary> {
  validateFakeDataInput({ startDate, endDate, totalCalls: 1 });
  const removed = await cleanupFakeDataValidated(startDate, endDate);
  return { removed, startDate, endDate };
}

function selectLocation(date: string, usedByDate: Map<string, Set<string>>, random: () => number): FakeLocation {
  let used = usedByDate.get(date);
  if (!used) {
    used = new Set<string>();
    usedByDate.set(date, used);
  }
  if (used.size >= OMAHA_FAKE_LOCATIONS.length) used.clear();
  const available = OMAHA_FAKE_LOCATIONS.filter((location) => !used?.has(location.slug));
  return available[Math.floor(random() * available.length)] as FakeLocation;
}

type AddressKeyParts = Pick<FakeLocation, "line1" | "city" | "state" | "postalCode"> & {
  line2?: string | null;
};

function addressKey(location: AddressKeyParts): string {
  return [location.line1, location.line2 ?? "", location.city, location.state, location.postalCode]
    .map((part) => part.trim().toLowerCase())
    .join("|");
}

type CoordinateKeyParts = Pick<FakeLocation, "lat" | "lng">;

function coordinateKey(location: CoordinateKeyParts): string {
  return `${location.lat.toFixed(6)}|${location.lng.toFixed(6)}`;
}

function selectUniqueLocation(
  location: FakeLocation,
  usedAddresses: Set<string>,
  usedCoordinates: Set<string>,
  random: () => number
): FakeLocation {
  while (true) {
    const candidate = randomLocationForCall(location, random);
    if (!usedAddresses.has(addressKey(candidate)) && !usedCoordinates.has(coordinateKey(candidate))) return candidate;
  }
}

async function createPendingJob(
  tx: Prisma.TransactionClient,
  externalId: string,
  identity: { firstName: string; lastName: string; email: string; phone: string },
  location: FakeLocation,
  service: { id: string; durationMin: number }
) {
  const customer = await tx.customer.create({ data: { externalId, ...identity } });
  const address = await tx.address.create({
    data: {
      customerId: customer.id,
      line1: location.line1,
      line2: location.line2,
      city: location.city,
      state: location.state,
      postalCode: location.postalCode,
      lat: location.lat,
      lng: location.lng,
      geocodePrecision: "SYNTHETIC",
      geocodedAt: new Date(),
    },
  });
  const job = await tx.job.create({
    data: {
      externalId,
      customerId: customer.id,
      addressId: address.id,
      serviceId: service.id,
      durationMin: service.durationMin,
      status: "PENDING",
    },
  });
  return { jobId: job.id, customerId: customer.id, addressId: address.id };
}

async function cleanupPendingJob(ids: { jobId: string; customerId: string; addressId: string }) {
  await prisma.$transaction(async (tx) => {
    await tx.slotHold.deleteMany({ where: { jobId: ids.jobId } });
    await tx.bookingOffer.deleteMany({ where: { jobId: ids.jobId } });
    await tx.bookingOptimization.deleteMany({ where: { jobId: ids.jobId } });
    await tx.job.deleteMany({ where: { id: ids.jobId, status: "PENDING" } });
    await tx.address.deleteMany({ where: { id: ids.addressId, jobs: { none: {} } } });
    await tx.customer.deleteMany({ where: { id: ids.customerId, jobs: { none: {} } } });
  });
}

function exactTwoHourOffers(offers: SlotOffer[], date: string): SlotOffer[] {
  return offers
    .filter(
      (offer) =>
        offer.date === date &&
        new Date(offer.windowEnd).getTime() - new Date(offer.windowStart).getTime() === TWO_HOURS_MS
    )
    .sort((left, right) => {
      const byStart = left.windowStart.localeCompare(right.windowStart);
      return byStart !== 0 ? byStart : left.offerId.localeCompare(right.offerId);
    });
}

async function tryBookDate(args: {
  date: string;
  seed: number;
  plan: ReturnType<typeof buildCallPlans>[number];
  dateAttempt: number;
  location: FakeLocation;
  service: { id: string; durationMin: number };
  random: () => number;
}): Promise<boolean> {
  const externalId = fakeExternalId(args.date, args.seed, args.plan.ordinal, args.dateAttempt);
  const ids = await prisma.$transaction((tx) =>
    createPendingJob(
      tx,
      externalId,
      {
        firstName: args.plan.firstName,
        lastName: args.plan.lastName,
        email: args.plan.email,
        phone: args.plan.phone,
      },
      args.location,
      args.service
    )
  );

  try {
    for (let confirmAttempt = 0; confirmAttempt < CONFIRM_ATTEMPTS; confirmAttempt++) {
      const { offers } = await requestSlots(ids.jobId);
      const candidates = exactTwoHourOffers(offers, args.date);
      if (candidates.length === 0) {
        await cleanupPendingJob(ids);
        return false;
      }
      const selected = candidates[Math.floor(args.random() * candidates.length)] as SlotOffer;
      try {
        const hold = await selectOffer(ids.jobId, selected.offerId);
        const confirmation = await confirmHold(hold.holdId);
        await prisma.appointment.update({
          where: { id: confirmation.appointmentId },
          data: { externalId },
        });
        return true;
      } catch (error) {
        if (!(error instanceof EngineError) || error.status !== 409) throw error;
      }
    }
    await cleanupPendingJob(ids);
    return false;
  } catch (error) {
    await cleanupPendingJob(ids);
    throw error;
  }
}

export async function generateFakeData(rawInput: FakeDataInput): Promise<FakeDataSummary> {
  const input = validateFakeDataInput(rawInput);
  await ensureOmahaConfiguration(prisma);
  const configuration = await loadOmahaConfiguration();
  const seed = input.seed ?? randomBytes(4).readUInt32LE(0);
  const random = createSeededRandom(seed);
  const plans = buildCallPlans(input, random);

  await cleanupFakeDataValidated(input.startDate, input.endDate);

  const scheduledAddresses = await prisma.appointment.findMany({
    select: {
      job: {
        select: {
          address: {
            select: {
              line1: true,
              line2: true,
              city: true,
              state: true,
              postalCode: true,
              lat: true,
              lng: true,
            },
          },
        },
      },
    },
  });

  const byDate: Record<string, number> = {};
  const byService: Record<string, number> = {};
  const warnings: string[] = [];
  const usedLocations = new Map<string, Set<string>>();
  const usedAddresses = new Set(scheduledAddresses.map(({ job }) => addressKey(job.address)));
  const usedCoordinates = new Set(
    scheduledAddresses.flatMap(({ job }) => {
      const { lat, lng } = job.address;
      return lat === null || lng === null ? [] : [coordinateKey({ lat, lng })];
    })
  );
  let created = 0;

  for (const plan of plans) {
    const service = configuration.services.get(plan.serviceCode);
    if (!service) throw new Error(`Service ${plan.serviceCode} became unavailable during generation.`);
    let booked = false;
    const dates = candidateDates(plan.preferredDate, input.weekdays, random);
    for (const [dateAttempt, date] of dates.entries()) {
      const location = selectLocation(date, usedLocations, random);
      const uniqueLocation = selectUniqueLocation(location, usedAddresses, usedCoordinates, random);
      booked = await tryBookDate({ date, seed, plan, dateAttempt, location: uniqueLocation, service, random });
      if (booked) {
        const used = usedLocations.get(date) as Set<string>;
        used.add(location.slug);
        usedAddresses.add(addressKey(uniqueLocation));
        usedCoordinates.add(coordinateKey(uniqueLocation));
        byDate[date] = (byDate[date] ?? 0) + 1;
        byService[plan.serviceCode] = (byService[plan.serviceCode] ?? 0) + 1;
        created++;
        break;
      }
    }
  }

  const skipped = input.totalCalls - created;
  if (skipped > 0) {
    warnings.unshift(`Scheduler capacity allowed ${created} of ${input.totalCalls} requested calls.`);
  }
  return {
    requested: input.totalCalls,
    created,
    skipped,
    seed,
    startDate: input.startDate,
    endDate: input.endDate,
    byDate: Object.fromEntries(Object.entries(byDate).sort(([left], [right]) => left.localeCompare(right))),
    byService,
    warnings,
  };
}
