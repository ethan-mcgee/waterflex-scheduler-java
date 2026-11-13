// Seeds and removes the portal records the move check timing study drags. Run by infra/run-move-check-timing.ps1:
//   npx tsx timing/seedMoveTiming.ts seed --run-dir <dir> --first-date <YYYY-MM-DD> --cold-days <n>
//   npx tsx timing/seedMoveTiming.ts remove --run-dir <dir>
// The seed writes <dir>/plan.json (record IDs only, no secrets), which the timing spec reads and remove deletes by.
//
// Every study day has three technicians in metro-omaha, the metro the rehearsal stack routes:
//   source  6 visits of 45 minutes with two-hour promised windows from 08:00 to 16:00 (semi full); the visit with
//           the 12:00 to 14:00 window is the one dragged.
//   roomy   3 visits with a gap at midday, so the dragged visit fits: the feasible move.
//   full    a 5-hour visit from 08:00 and a visit with a window from 11:00, so the dragged visit, placed after them,
//           cannot arrive by 14:00: the move that does not fit.
// Day 0 is the warm day every warm drag repeats on. Each cold day gets coordinates shifted by its index and a salt
// from the run ID, so the scheduler's route cache has never seen its stops.
import { createHash } from "node:crypto";
import { mkdirSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { createPrismaClient } from "../lib/portalDatabase";
import { initialAvailability } from "../lib/technicianAvailability";
import { localMinute } from "../lib/zonedTime";
import { addCalendarDays } from "../lib/date";
import { date as dateContract } from "../lib/contracts";
import { readPlan, timingPlan, TIMING_CLIENT_ID, TIMING_METRO_ID, TIMING_ZONE as ZONE, type TimingPlan } from "./timingPlan";

/** Omaha points inside the study area (41.22 to 41.28 N, 96.05 to 95.95 W), as offsets from its south-west corner. */
const POINTS = {
  depot: [0.030, 0.050], homes: [[0.050, 0.020], [0.010, 0.080], [0.045, 0.090]],
  source: [[0.012, 0.015], [0.020, 0.030], [0.028, 0.022], [0.035, 0.040], [0.040, 0.060], [0.048, 0.072]],
  roomy: [[0.008, 0.070], [0.018, 0.085], [0.030, 0.092]],
  full: [[0.052, 0.010], [0.044, 0.030]],
} as const;

const weekday = (value: string) => new Date(`${value}T12:00:00Z`).getUTCDay();

/** The first date and the next `count - 1` weekdays after it. */
export function studyDates(first: string, count: number): string[] {
  if (weekday(first) === 0 || weekday(first) === 6) throw new Error(`${first} is not a weekday`);
  const dates = [first];
  let next = first;
  while (dates.length < count) { next = addCalendarDays(next, 1); if (weekday(next) !== 0 && weekday(next) !== 6) dates.push(next); }
  return dates;
}

/** A salt of 1 to 400 hundred-thousandths of a degree from the run ID, so each run's cold stops are new to the cache. */
function salt(runId: string): number {
  return (createHash("sha256").update(runId).digest().readUInt16BE(0) % 400 + 1) / 100_000;
}

function argument(args: string[], name: string): string {
  const at = args.indexOf(name);
  const value = at < 0 ? undefined : args[at + 1];
  if (value === undefined || value.startsWith("--")) throw new Error(`Missing ${name}`);
  return value;
}

async function seed(runDir: string, firstDate: string, coldDays: number) {
  const prisma = createPrismaClient();
  try {
    const runId = runDir.replace(/[\\/]+$/, "").split(/[\\/]/).at(-1) ?? runDir;
    const dates = studyDates(firstDate, 1 + 2 * coldDays);
    const shift = salt(runId);
    const hadClient = (await prisma.client.findUnique({ where: { id: TIMING_CLIENT_ID } })) !== null;
    if (!hadClient) await prisma.client.create({ data: { id: TIMING_CLIENT_ID, name: "Move check timing" } });
    const existingMetro = await prisma.metro.findUnique({ where: { id: TIMING_METRO_ID } });
    if (existingMetro !== null && existingMetro.timezone !== ZONE) throw new Error(`${TIMING_METRO_ID} uses ${existingMetro.timezone}, not ${ZONE}`);
    if (existingMetro === null) await prisma.metro.create({ data: { id: TIMING_METRO_ID, name: "Omaha", timezone: ZONE, stateCode: "NE" } });
    const hadSettings = (await prisma.clientSolverSettings.findUnique({ where: { clientId: TIMING_CLIENT_ID } })) !== null;
    if (!hadSettings) {
      const { storeSolverSettings } = await import("../lib/clientSettingsStore");
      const saved = await storeSolverSettings(TIMING_CLIENT_ID, null, { regularHourly: "30", overtimeHourly: "45", mileagePerMile: "0.67",
        travelBufferPercent: "20", travelBufferMinutes: 5, arrivalBufferPercent: 25, arrivalBufferMinMinutes: 15, arrivalBufferMaxMinutes: 60,
        fairnessBudgetPercent: "2", offerLimit: 4, windowHours: 2, bookingHorizonWeekdays: 3, horizonMode: "FIXED", horizonLeadWeekdays: 2,
        horizonFullPercent: 85, dailySearch: "TABU_KOPT", costSearchSeconds: 10, fairnessSearchSeconds: 5, unimprovedSearchSeconds: null,
        bookingDeadlineMs: 5000, regularWindowThreshold: 2 });
      if (!("saved" in saved)) throw new Error("The timing client's solver settings were refused");
    }
    const at = (offset: readonly [number, number], extra: number) => ({ lat: 41.22 + offset[0] + extra, lng: -96.05 + offset[1] + extra });
    const dealership = await prisma.dealership.create({ data: { clientId: TIMING_CLIENT_ID, name: `Timing dealership ${runId}` } });
    const depotPoint = at(POINTS.depot, 0);
    const depot = await prisma.depot.create({ data: { metroId: TIMING_METRO_ID, dealershipId: dealership.id, name: `Timing depot ${runId}`, ...depotPoint,
      endpointPolicies: { create: { effectiveDate: new Date("1900-01-01T00:00:00Z"), departure: "DEPOT", returnTo: "HOME" } } } });
    const service = await prisma.serviceCatalog.create({ data: { code: `TIMING_${createHash("sha256").update(runId).digest("hex").slice(0, 12)}`, name: "Softener service", estDurationMin: 45 } });
    const technician = (name: string, color: string, home: readonly [number, number]) => prisma.technician.create({ data: {
      clientId: TIMING_CLIENT_ID, name, color, ...(({ lat, lng }) => ({ homeLat: lat, homeLng: lng }))(at(home, 0)),
      shiftStartMin: 480, shiftEndMin: 1020, availabilityVersions: initialAvailability(480, 1020),
      qualifications: { create: { serviceId: service.id } },
      depotAssignments: { create: { depotId: depot.id, effectiveDate: new Date("1900-01-01T00:00:00Z") } } } });
    const [sourceHome, roomyHome, fullHome] = POINTS.homes;
    const source = await technician("Timing Source", "#2563eb", sourceHome);
    const roomy = await technician("Timing Roomy", "#059669", roomyHome);
    const full = await technician("Timing Full", "#dc2626", fullHome);

    const customers: string[] = [];
    const visit = async (technicianId: string, date: string, sequence: number, point: { lat: number; lng: number }, windowStart: number, windowEnd: number, minutes: number, label: string) => {
      const customer = await prisma.customer.create({ data: { clientId: TIMING_CLIENT_ID, firstName: "Timing", lastName: label, email: `${customers.length}.${runId}@example.invalid`, phone: "4025550100" } });
      customers.push(customer.id);
      const address = await prisma.address.create({ data: { customerId: customer.id, line1: `${sequence + 1} Timing St`, city: "Omaha", state: "NE", postalCode: "68102", ...point,
        geocodePrecision: "ROOFTOP", pinConfirmedAt: new Date("2026-01-01T00:00:00Z") } });
      const job = await prisma.job.create({ data: { customerId: customer.id, addressId: address.id, serviceId: service.id, durationMin: minutes, status: "SCHEDULED" } });
      const start = localMinute(date, windowStart, false, ZONE);
      const appointment = await prisma.appointment.create({ data: { jobId: job.id, technicianId, serviceDate: new Date(`${date}T00:00:00Z`),
        windowStart: start, windowEnd: localMinute(date, windowEnd, true, ZONE), plannedStart: start,
        plannedEnd: new Date(start.getTime() + minutes * 60_000), sequence } });
      return appointment.id;
    };

    const days: TimingPlan["days"] = [];
    for (const [index, date] of dates.entries()) {
      const extra = index === 0 ? 0 : index * 0.0007 + shift;
      const sourceWindows: Array<[number, number]> = [[480, 600], [540, 660], [600, 720], [720, 840], [780, 900], [840, 960]];
      let moving = "";
      for (const [sequence, window] of sourceWindows.entries()) {
        const id = await visit(source.id, date, sequence, at(POINTS.source[sequence] ?? POINTS.depot, extra), window[0], window[1], 45, `Source ${sequence + 1}`);
        if (sequence === 3) moving = id;
      }
      const roomyWindows: Array<[number, number]> = [[480, 600], [600, 720], [840, 960]];
      for (const [sequence, window] of roomyWindows.entries())
        await visit(roomy.id, date, sequence, at(POINTS.roomy[sequence] ?? POINTS.depot, extra), window[0], window[1], 45, `Roomy ${sequence + 1}`);
      await visit(full.id, date, 0, at(POINTS.full[0], extra), 480, 510, 300, "Full 1");
      await visit(full.id, date, 1, at(POINTS.full[1], extra), 660, 960, 60, "Full 2");
      days.push({ date, kind: index === 0 ? "warm" : index <= coldDays ? "cold-feasible" : "cold-not-feasible", moving });
    }
    const plan: TimingPlan = { runId, clientId: TIMING_CLIENT_ID, metroId: TIMING_METRO_ID, timeZone: ZONE,
      technicians: { source: source.id, roomy: roomy.id, full: full.id }, days,
      created: { client: !hadClient, metro: existingMetro === null, settings: !hadSettings, dealership: dealership.id, depot: depot.id, service: service.id, customers } };
    mkdirSync(runDir, { recursive: true });
    writeFileSync(join(runDir, "plan.json"), `${JSON.stringify(timingPlan.parse(plan), null, 2)}\n`);
    console.log(`Seeded ${days.length} days (${dates[0]} to ${dates.at(-1)}) for ${TIMING_CLIENT_ID}`);
  } finally { await prisma.$disconnect(); }
}

async function remove(runDir: string) {
  const plan = readPlan(runDir);
  const prisma = createPrismaClient();
  try {
    const technicians = Object.values(plan.technicians);
    const jobs = await prisma.job.findMany({ where: { customerId: { in: plan.created.customers } }, select: { id: true, addressId: true } });
    await prisma.appointment.deleteMany({ where: { technicianId: { in: technicians } } });
    await prisma.job.deleteMany({ where: { id: { in: jobs.map(job => job.id) } } });
    await prisma.address.deleteMany({ where: { customerId: { in: plan.created.customers } } });
    await prisma.customer.deleteMany({ where: { id: { in: plan.created.customers } } });
    await prisma.technicianQualification.deleteMany({ where: { technicianId: { in: technicians } } });
    await prisma.technician.deleteMany({ where: { id: { in: technicians } } });
    await prisma.serviceCatalog.delete({ where: { id: plan.created.service } });
    await prisma.depot.delete({ where: { id: plan.created.depot } });
    await prisma.dealership.delete({ where: { id: plan.created.dealership } });
    if (plan.created.settings) await prisma.clientSolverSettings.delete({ where: { clientId: plan.clientId } });
    if (plan.created.metro) await prisma.metro.delete({ where: { id: plan.metroId } });
    if (plan.created.client) await prisma.client.delete({ where: { id: plan.clientId } });
    console.log(`Removed the records of ${plan.runId}`);
  } finally { await prisma.$disconnect(); }
}

async function main() {
  const [command, ...args] = process.argv.slice(2);
  if (command === "seed") {
    const coldDays = Number(argument(args, "--cold-days"));
    if (!Number.isInteger(coldDays) || coldDays < 0 || coldDays > 50) throw new Error("--cold-days must be 0 to 50");
    await seed(argument(args, "--run-dir"), dateContract.parse(argument(args, "--first-date")), coldDays);
  } else if (command === "remove") {
    await remove(argument(args, "--run-dir"));
  } else {
    throw new Error("Usage: seedMoveTiming.ts seed --run-dir <dir> --first-date <YYYY-MM-DD> --cold-days <n> | remove --run-dir <dir>");
  }
}

main().catch((error: unknown) => { console.error(error); process.exitCode = 1; });
