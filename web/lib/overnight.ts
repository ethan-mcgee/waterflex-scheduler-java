import { randomUUID } from "node:crypto";
import { Prisma } from "@prisma/client";
import { prisma } from "./prisma";
import { DispatchRefused, NO_TECHNICIANS, proposeApiDay } from "./apiDispatch";
import { frozen } from "./apiTimeOffCore";
import { dueSlot, OVERNIGHT_TIME_ZONE, overnightDates, timeOf } from "./overnightCore";

/**
 * The portal's overnight optimization, acting as the host for each client: a run asks the scheduling API for a daily
 * proposal of every day ahead in every metro the client serves, and keeps each one for a dispatcher to review. Nothing
 * is applied. Runs are queued in the database, by "Run now" or by the client's run times, and a worker claims them.
 */

/** A running run whose worker has not reported for this long is abandoned; one day takes well under a minute. */
export const ABANDON_AFTER_MINUTES = 10;

export type RunTrigger = "SCHEDULED" | "MANUAL";
export type RunStatus = "QUEUED" | "RUNNING" | "FINISHED" | "ABANDONED";
export type DayOutcome = "PROPOSED" | "SKIPPED" | "FAILED";

export interface OvernightDayView {
  metroId: string;
  serviceDate: string;
  outcome: DayOutcome;
  message: string | null;
  /** The kept proposal's decision and whether it waits for a dispatcher, was applied, or can no longer be applied. */
  proposal: { proposalId: string; decision: string; state: "WAITING" | "APPLIED" | "REFUSED" | "NOTHING_TO_APPLY" } | null;
}

export interface OvernightRunView {
  runId: string;
  trigger: RunTrigger;
  status: RunStatus;
  scheduledFor: string | null;
  createdAt: string;
  startedAt: string | null;
  finishedAt: string | null;
  days: OvernightDayView[];
}

const TRIGGERS: readonly RunTrigger[] = ["SCHEDULED", "MANUAL"];
const STATUSES: readonly RunStatus[] = ["QUEUED", "RUNNING", "FINISHED", "ABANDONED"];
const OUTCOMES: readonly DayOutcome[] = ["PROPOSED", "SKIPPED", "FAILED"];

function member<T extends string>(values: readonly T[], value: string, what: string): T {
  const found = values.find(item => item === value);
  if (found === undefined) throw new Error(`Stored overnight ${what} ${value} is not known`);
  return found;
}

/** The client's run times as minutes after local midnight, earliest first. */
export async function loadRunMinutes(clientId: string): Promise<number[]> {
  const rows = await prisma.clientOvernightTime.findMany({ where: { clientId }, orderBy: { localMinute: "asc" }, select: { localMinute: true } });
  return rows.map(row => row.localMinute);
}

/** Replaces the client's run times; none leaves runs to "Run now". */
export async function saveRunMinutes(clientId: string, minutes: readonly number[]): Promise<number[]> {
  for (const minute of minutes) timeOf(minute);
  await prisma.$transaction(async tx => {
    // Locks the client row so two saves never interleave their delete and insert.
    await tx.$queryRaw`SELECT id FROM client WHERE id = ${clientId} FOR UPDATE`;
    await tx.clientOvernightTime.deleteMany({ where: { clientId } });
    if (minutes.length > 0) await tx.clientOvernightTime.createMany({ data: minutes.map(localMinute => ({ clientId, localMinute })) });
  });
  return loadRunMinutes(clientId);
}

/** Queues a manual run, unless the client already has a queued or running run, which is returned instead. */
export async function requestRun(clientId: string): Promise<{ run: OvernightRunView; alreadyActive: boolean }> {
  const id = randomUUID();
  const inserted = await prisma.$executeRaw`INSERT INTO portal_overnight_run (id, "clientId", trigger, status)
    VALUES (${id}::uuid, ${clientId}, 'MANUAL', 'QUEUED') ON CONFLICT DO NOTHING`;
  if (inserted === 1) return { run: await runView(clientId, id), alreadyActive: false };
  const active = await prisma.portalOvernightRun.findFirst({ where: { clientId, status: { in: ["QUEUED", "RUNNING"] } }, select: { id: true } });
  // The active run finished between the insert and this read; asking again queues a new one.
  if (active === null) return requestRun(clientId);
  return { run: await runView(clientId, active.id), alreadyActive: true };
}

/**
 * Queues each client's run time that is due at `now`. Every portal process may do this; the first insert for a run
 * time wins, and a client with a run already queued or running gets none until it ends.
 */
export async function enqueueDueRuns(now: Date): Promise<number> {
  const times = await prisma.clientOvernightTime.findMany({ select: { clientId: true, localMinute: true } });
  const byClient = new Map<string, number[]>();
  for (const time of times) byClient.set(time.clientId, [...(byClient.get(time.clientId) ?? []), time.localMinute]);
  let queued = 0;
  for (const [clientId, minutes] of byClient) {
    const slot = dueSlot(minutes, now);
    if (slot === null) continue;
    queued += await prisma.$executeRaw`INSERT INTO portal_overnight_run (id, "clientId", trigger, "scheduledFor", status)
      VALUES (${randomUUID()}::uuid, ${clientId}, 'SCHEDULED', ${slot}, 'QUEUED') ON CONFLICT DO NOTHING`;
  }
  return queued;
}

/** Ends running runs whose worker stopped reporting. The days they finished stay recorded. */
export async function abandonStaleRuns(): Promise<number> {
  return prisma.$executeRaw`UPDATE portal_overnight_run SET status = 'ABANDONED', "finishedAt" = clock_timestamp()
    WHERE status = 'RUNNING' AND "heartbeatAt" < clock_timestamp() - make_interval(mins => ${ABANDON_AFTER_MINUTES}::int)`;
}

/** Claims the oldest queued run; several workers never claim the same one. */
export async function claimRun(): Promise<{ runId: string; clientId: string } | null> {
  const rows = await prisma.$queryRaw<{ id: string; clientId: string }[]>`UPDATE portal_overnight_run
    SET status = 'RUNNING', "startedAt" = clock_timestamp(), "heartbeatAt" = clock_timestamp()
    WHERE id = (SELECT id FROM portal_overnight_run WHERE status = 'QUEUED' ORDER BY "createdAt", id FOR UPDATE SKIP LOCKED LIMIT 1)
    RETURNING id::text AS id, "clientId"`;
  const [row, extra] = rows;
  if (extra !== undefined) throw new Error("Claiming an overnight run returned more than one run");
  return row === undefined ? null : { runId: row.id, clientId: row.clientId };
}

class RunEnded extends Error {
  constructor(runId: string) { super(`Overnight run ${runId} is no longer running`); this.name = "RunEnded"; }
}

/** Records one metro day of a running run and moves its heartbeat, or throws RunEnded if the run was ended meanwhile. */
async function recordDay(tx: Prisma.TransactionClient, runId: string, metroId: string, serviceDate: string,
  outcome: DayOutcome, proposalId: string | null, message: string | null): Promise<void> {
  const alive = await tx.$executeRaw`UPDATE portal_overnight_run SET "heartbeatAt" = clock_timestamp() WHERE id = ${runId}::uuid AND status = 'RUNNING'`;
  if (alive !== 1) throw new RunEnded(runId);
  await tx.portalOvernightDay.create({ data: { runId, metroId, serviceDate: new Date(`${serviceDate}T00:00:00Z`), outcome, proposalId, message } });
}

export interface RunDependencies {
  propose: typeof proposeApiDay;
  now: () => Date;
  /** The days to propose; the scheduler's overnight days by default. */
  dates: (now: Date) => string[];
  /** Checked between days; true stops the run and abandons it, keeping the days already done. */
  stopping: () => boolean;
}

const defaults: RunDependencies = { propose: proposeApiDay, now: () => new Date(), dates: overnightDates, stopping: () => false };

/**
 * Proposes every day of a claimed run in every metro the client serves, one at a time, and finishes the run. A day the
 * scheduler cannot propose is recorded with the reason and the run moves on; nothing is ever applied.
 */
export async function executeRun(runId: string, clientId: string, dependencies: Partial<RunDependencies> = {}): Promise<RunStatus> {
  const { propose, now, dates, stopping } = { ...defaults, ...dependencies };
  const metros = await prisma.depot.findMany({ where: { dealership: { clientId } }, select: { metroId: true }, distinct: ["metroId"], orderBy: { metroId: "asc" } });
  const days = dates(now());
  try {
    for (const { metroId } of metros)
      for (const serviceDate of days) {
        if (stopping()) {
          await prisma.portalOvernightRun.updateMany({ where: { id: runId, status: "RUNNING" }, data: { status: "ABANDONED", finishedAt: new Date() } });
          return "ABANDONED";
        }
        await proposeDay(runId, clientId, metroId, serviceDate, propose, now);
      }
  } catch (error) {
    if (error instanceof RunEnded) return "ABANDONED";
    throw error;
  }
  const finished = await prisma.portalOvernightRun.updateMany({ where: { id: runId, status: "RUNNING" }, data: { status: "FINISHED", finishedAt: new Date() } });
  return finished.count === 1 ? "FINISHED" : "ABANDONED";
}

async function proposeDay(runId: string, clientId: string, metroId: string, serviceDate: string, propose: typeof proposeApiDay, now: () => Date) {
  const record = (outcome: DayOutcome, message: string) => prisma.$transaction(tx => recordDay(tx, runId, metroId, serviceDate, outcome, null, message));
  // A run that reaches a day after its 6 a.m. cutoff leaves it alone, as the scheduler would refuse to change it.
  if (frozen(serviceDate, now(), OVERNIGHT_TIME_ZONE)) return record("SKIPPED", "The day is frozen after 6 a.m.");
  try {
    await propose(clientId, metroId, serviceDate, async (tx, proposalId) => {
      await recordDay(tx, runId, metroId, serviceDate, "PROPOSED", proposalId, null);
      return runId;
    });
  } catch (error) {
    if (error instanceof RunEnded) throw error;
    if (error instanceof DispatchRefused) return record(error.message === NO_TECHNICIANS ? "SKIPPED" : "FAILED", error.message);
    console.error("Overnight day failed", runId, metroId, serviceDate, error);
    return record("FAILED", "The day could not be proposed. Ask an administrator to check the portal logs.");
  }
}

function dayView(day: Prisma.PortalOvernightRunGetPayload<{ include: typeof runInclude }>["days"][number]): OvernightDayView {
  const outcome = member(OUTCOMES, day.outcome, "day outcome");
  if ((outcome === "PROPOSED") !== (day.proposal !== null)) throw new Error(`Overnight day ${day.runId} ${day.metroId} does not match its proposal`);
  const proposal = day.proposal === null ? null : { proposalId: day.proposal.id, decision: day.proposal.decision,
    state: day.proposal.receiptId !== null ? "APPLIED" as const : day.proposal.commitRefusal !== null ? "REFUSED" as const
      : day.proposal.decision === "IMPROVED" ? "WAITING" as const : "NOTHING_TO_APPLY" as const };
  return { metroId: day.metroId, serviceDate: day.serviceDate.toISOString().slice(0, 10), outcome, message: day.message, proposal };
}

const runInclude = Prisma.validator<Prisma.PortalOvernightRunInclude>()({ days: { orderBy: [{ metroId: "asc" }, { serviceDate: "asc" }],
  include: { proposal: { select: { id: true, decision: true, receiptId: true, commitRefusal: true } } } } });

function toView(run: Prisma.PortalOvernightRunGetPayload<{ include: typeof runInclude }>): OvernightRunView {
  return { runId: run.id, trigger: member(TRIGGERS, run.trigger, "trigger"), status: member(STATUSES, run.status, "status"),
    scheduledFor: run.scheduledFor?.toISOString() ?? null, createdAt: run.createdAt.toISOString(), startedAt: run.startedAt?.toISOString() ?? null,
    finishedAt: run.finishedAt?.toISOString() ?? null, days: run.days.map(dayView) };
}

async function runView(clientId: string, runId: string): Promise<OvernightRunView> {
  const run = await prisma.portalOvernightRun.findFirst({ where: { id: runId, clientId }, include: runInclude });
  if (run === null) throw new Error(`Overnight run ${runId} of client ${clientId} not found`);
  return toView(run);
}

/** The client's latest runs, newest first. */
export async function recentRuns(clientId: string, take = 10): Promise<OvernightRunView[]> {
  const runs = await prisma.portalOvernightRun.findMany({ where: { clientId }, orderBy: [{ createdAt: "desc" }, { id: "desc" }], take, include: runInclude });
  return runs.map(toView);
}
