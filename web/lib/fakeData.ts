import { z } from "zod";
import { required } from "./contracts";
import { randomBytes } from "node:crypto";
import type { Prisma } from "@prisma/client";
import { metroClientId } from "./metroClient";
import { bookApiOffer, BookingRefused, searchApiOffers } from "./apiBooking";
import { purgeApiJobs } from "./apiPurge";
import { servesMetro } from "./clientScope";
import { addCalendarDays, todayInTz } from "./date";
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
  const today = new Date(`${todayInTz(OMAHA_TIMEZONE)}T00:00:00Z`);
  const [metro, depotCount, technicians, services] = await Promise.all([
    prisma.metro.findUnique({ where: { id: OMAHA_METRO_ID } }),
    prisma.depot.count({ where: { metroId: OMAHA_METRO_ID } }),
    prisma.technician.findMany({ where: { active: true }, select: { depotAssignments: { where: { effectiveDate: { lte: today } },
      include: { depot: { select: { metroId: true } } }, orderBy: { effectiveDate: "desc" }, take: 1 } } }),
    prisma.serviceCatalog.findMany({ where: { code: { in: [...FAKE_SERVICE_CODES] }, active: true } }),
  ]);
  const technicianCount = technicians.filter(technician => technician.depotAssignments[0]?.depot.metroId === OMAHA_METRO_ID).length;
  if (!metro || metro.timezone !== OMAHA_TIMEZONE || depotCount === 0 || technicianCount === 0) {
    throw new Error("The Omaha metro, depot, timezone, and active technicians must be configured first.");
  }
  const byCode = new Map(
    services.map((service) => [
      z.enum(FAKE_SERVICE_CODES).parse(service.code),
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

/**
 * The client the generated calls belong to: the one named, or the Omaha metro's only client. A metro may be shared, so
 * the client must serve Omaha and only its own generated calls are touched.
 */
async function fakeDataClient(clientId: string | undefined): Promise<string> {
  if (clientId === undefined) return metroClientId(prisma, OMAHA_METRO_ID);
  if (!(await servesMetro(clientId, OMAHA_METRO_ID))) throw new Error(`Client ${clientId} has no depot in the Omaha metro.`);
  return clientId;
}

function inRange(date: string, startDate: string, endDate: string): boolean {
  return date >= startDate && date <= endDate;
}

async function cleanupFakeDataValidated(clientId: string, startDate: string, endDate: string): Promise<number> {
  const dayStart = new Date(`${startDate}T00:00:00.000Z`);
  const dayAfterEnd = new Date(`${addCalendarDays(endDate, 1)}T00:00:00.000Z`);
  const candidates = await prisma.job.findMany({
    where: { externalId: { startsWith: FAKE_DATA_PREFIX }, customer: { clientId } },
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
  // The open days the purge leaves are re-timed; frozen days keep their times.
  await purgeApiJobs(clientId, OMAHA_METRO_ID, jobs.map(job => job.id));
  return jobs.length;
}

export async function clearFakeData(startDate: string, endDate: string, clientId?: string): Promise<FakeDataClearSummary> {
  validateFakeDataInput({ startDate, endDate, totalCalls: 1 });
  const removed = await cleanupFakeDataValidated(await fakeDataClient(clientId), startDate, endDate);
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
  return required(available[Math.floor(random() * available.length)], "Available location");
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
  clientId: string,
  externalId: string,
  identity: { firstName: string; lastName: string; email: string; phone: string },
  location: FakeLocation,
  service: { id: string; durationMin: number }
) {
  const customer = await tx.customer.create({ data: { clientId, externalId, ...identity } });
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

type BookableOffer = { offerId: string; date: string; windowStart: string; windowEnd: string };

/** The offers on the date a call may take, in start order; the scheduler decides each arrival window's length. */
function bookableOffers<O extends BookableOffer>(offers: readonly O[], date: string): O[] {
  return offers
    .filter(
      (offer) => offer.date === date
    )
    .sort((left, right) => {
      const byStart = left.windowStart.localeCompare(right.windowStart);
      return byStart !== 0 ? byStart : left.offerId.localeCompare(right.offerId);
    });
}

/** Searches the date through the public API and books one of its offers as the customer would. */
export async function bookFakeCall(clientId: string, jobId: string, date: string, random: () => number): Promise<"BOOKED" | "NO_OFFER" | "CONFLICT"> {
  const { offers } = await searchApiOffers(clientId, jobId, date);
  const candidates = bookableOffers(offers, date);
  if (candidates.length === 0) return "NO_OFFER";
  const selected = required(candidates[Math.floor(random() * candidates.length)], "Candidate offer");
  try {
    await bookApiOffer(clientId, jobId, selected.offerId);
    return "BOOKED";
  } catch (error) {
    if (error instanceof BookingRefused && error.status === 409) return "CONFLICT";
    throw error;
  }
}

async function tryBookDate(args: {
  clientId: string;
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
      args.clientId,
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
      const outcome = await bookFakeCall(args.clientId, ids.jobId, args.date, args.random);
      if (outcome === "NO_OFFER") {
        await cleanupPendingJob(ids);
        return false;
      }
      if (outcome === "BOOKED") {
        await prisma.appointment.update({ where: { jobId: ids.jobId }, data: { externalId } });
        return true;
      }
    }
    await cleanupPendingJob(ids);
    return false;
  } catch (error) {
    await cleanupPendingJob(ids);
    throw error;
  }
}

export async function generateFakeData(rawInput: FakeDataInput, client?: string): Promise<FakeDataSummary> {
  const input = validateFakeDataInput(rawInput);
  await ensureOmahaConfiguration(prisma);
  const clientId = await fakeDataClient(client);
  const configuration = await loadOmahaConfiguration();
  const seed = input.seed ?? randomBytes(4).readUInt32LE(0);
  const random = createSeededRandom(seed);
  const plans = buildCallPlans(input, random);

  await cleanupFakeDataValidated(clientId, input.startDate, input.endDate);

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
      booked = await tryBookDate({ clientId, date, seed, plan, dateAttempt, location: uniqueLocation, service, random });
      if (booked) {
        const used = required(usedLocations.get(date), "Used locations for date");
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
