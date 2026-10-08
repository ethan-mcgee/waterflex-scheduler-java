import { randomUUID } from "node:crypto";
import type { Prisma } from "@prisma/client";
import { prisma } from "./prisma";
import { buildClientSnapshot, SnapshotError, STORED_MINUTES_TIME_ZONE } from "./clientSnapshot";
import { commitAtScheduler, DispatchRefused, markRefused, proposeApiRepair, recordReceipt, view } from "./apiDispatch";
import { currentLastModified, StaleReceipt, writeReceipts, type ReceiptWrite } from "./apiReceipt";
import { absenceWindow, analysisProgress, apiTimeOffReport, automaticallyApproved, AWAITING_ANALYSIS, feasible, frozen, initialApiReport,
  nextPendingDay, reportMatches, requestDates, submissionProblem, unchangedDay, type ApiTimeOffDay, type ApiTimeOffReport, type Interval } from "./apiTimeOffCore";
import { todayInTz } from "./date";
import type { TechnicianDayVersion } from "./schedulerApi";

/**
 * Time off, availability and qualifications when the portal schedules through the public API. The portal is the host:
 * it records requests and edits itself, and asks the scheduler only to repair the days an absence disturbs, one day per
 * analysis step (`/api/v1/repairs/proposals`). Approval commits every day's repair and writes them all, with the
 * approval, in one compare-and-set transaction. The scheduler's own time-off service is never called.
 */

export class TimeOffRefused extends Error {
  constructor(readonly status: number, message: string) { super(message); this.name = "TimeOffRefused"; }
}

const stamp = (value: string) => new Date(`${value}T00:00:00Z`);
const isoDate = (value: Date) => value.toISOString().slice(0, 10);
const FROZEN = "Scheduling cutoff has passed; coordinate with customer service";
const REANALYZE = "The schedule changed since this request was analyzed. It will be analyzed again.";

type Loaded = { id: string; technicianId: string; status: string; firstDate: string; intervals: Interval[];
  reportStatus: string; report: ApiTimeOffReport };

async function load(clientId: string, id: string): Promise<Loaded> {
  const request = await prisma.timeOffRequest.findFirst({ where: { id, technician: { clientId } },
    include: { intervals: { orderBy: { serviceDate: "asc" } }, report: true } });
  if (request === null) throw new TimeOffRefused(404, "Time-off request not found");
  if (request.report === null) throw new TimeOffRefused(409, "This request has no analysis report");
  const intervals = request.intervals.map(item => ({ date: isoDate(item.serviceDate), startMin: item.startMin, endMin: item.endMin }));
  const first = intervals[0];
  if (first === undefined) throw new TimeOffRefused(409, "This request has no requested days");
  const parsed = apiTimeOffReport.safeParse(request.report.data);
  if (!parsed.success) throw new TimeOffRefused(409, "This request was not analyzed through the scheduling API; deny it and submit it again");
  if (!reportMatches(parsed.data, request.technicianId, intervals)) throw new TimeOffRefused(409, "The analysis report does not match the requested days");
  return { id: request.id, technicianId: request.technicianId, status: request.status, firstDate: first.date, intervals,
    reportStatus: request.report.status, report: parsed.data };
}

/** The metro whose depot the technician works from on the date, as of that date. */
async function metroOn(technicianId: string, serviceDate: string): Promise<{ id: string; timezone: string } | null> {
  const assignment = await prisma.technicianDepotAssignment.findFirst({ where: { technicianId, effectiveDate: { lte: stamp(serviceDate) } },
    orderBy: { effectiveDate: "desc" }, select: { depot: { select: { metro: { select: { id: true, timezone: true } } } } } });
  return assignment?.depot.metro ?? null;
}

/** Records a new request and its days, to be analyzed through the API. Checks match the scheduler's own submission. */
export async function submitApiTimeOff(clientId: string, input: { technicianId: string; firstDate: string; lastDate: string; startMin: number; endMin: number;
  category: string; reason: string }): Promise<{ requestId: string; status: "PENDING" }> {
  const problem = submissionProblem(input, new Date(), STORED_MINUTES_TIME_ZONE);
  if (problem !== null) throw new TimeOffRefused(400, problem);
  const intervals = requestDates(input.firstDate, input.lastDate).map(date => ({ date, startMin: input.startMin, endMin: input.endMin }));
  return prisma.$transaction(async tx => {
    const technician = await tx.$queryRaw<Array<{ id: string }>>`SELECT id FROM technician WHERE id = ${input.technicianId} AND "clientId" = ${clientId} AND active = true FOR UPDATE`;
    if (technician.length === 0) throw new TimeOffRefused(404, "Technician not found");
    const overlaps = await tx.timeOffInterval.count({ where: { request: { technicianId: input.technicianId, status: { in: ["PENDING", "READY", "APPROVED", "ANALYZING"] } },
      serviceDate: { in: intervals.map(item => stamp(item.date)) }, startMin: { lt: input.endMin }, endMin: { gt: input.startMin } } });
    if (overlaps > 0) throw new TimeOffRefused(409, "Overlapping time-off request");
    const request = await tx.timeOffRequest.create({ data: { technicianId: input.technicianId, category: input.category, reason: input.reason.trim(), status: "PENDING",
      intervals: { create: intervals.map(item => ({ serviceDate: stamp(item.date), startMin: item.startMin, endMin: item.endMin })) },
      report: { create: { status: AWAITING_ANALYSIS, progress: 0, data: initialApiReport(input.technicianId, intervals) } } } });
    return { requestId: request.id, status: "PENDING" as const };
  });
}

async function lastModifiedOf(technicianId: string, serviceDate: string): Promise<string> {
  const lastModified = (await currentLastModified(prisma, [{ technicianId, serviceDate }])).get(`${technicianId}|${serviceDate}`);
  if (lastModified === undefined) throw new TimeOffRefused(404, "Technician not found");
  return lastModified;
}

/** One day's analysis; throws TimeOffRefused for a failure that leaves the day pending. */
async function analyzeDay(clientId: string, request: Loaded, pending: ApiTimeOffDay): Promise<ApiTimeOffDay> {
  const { service_date: serviceDate, start_min: startMin, end_min: endMin } = pending;
  const metro = await metroOn(request.technicianId, serviceDate);
  if (metro === null) throw new TimeOffRefused(409, `The technician has no depot on ${serviceDate}`);
  if (frozen(serviceDate, new Date(), metro.timezone)) return { ...pending, status: "FROZEN_CSR_COORDINATION", reason: FROZEN };
  let snapshot;
  try { snapshot = await buildClientSnapshot(clientId, metro.id, [serviceDate]); }
  catch (error) {
    if (error instanceof SnapshotError) throw new TimeOffRefused(409, `Could not analyze ${serviceDate}: ${error.message}`);
    throw error;
  }
  const working = snapshot.technicianDays.find(item => item.technicianId === request.technicianId);
  // With no shift, or no appointments to move, the absence changes no route; approval checks the day is still so.
  if (working === undefined || !snapshot.appointments.some(item => item.technicianId === request.technicianId))
    return { ...pending, status: working === undefined ? "NO_SHIFT" : "NO_APPOINTMENTS", last_modified: working?.lastModified ?? await lastModifiedOf(request.technicianId, serviceDate) };
  try {
    const row = await proposeApiRepair(clientId, snapshot, { technicianId: request.technicianId, serviceDate,
      window: absenceWindow(serviceDate, startMin, endMin, metro.timezone) }, request.id);
    const proposal = view(row, null);
    if (proposal.decision !== "IMPROVED") return { ...pending, status: "NEEDS_COORDINATION", proposal_id: row.id, reason: proposal.reason };
    const reassigned = proposal.changes.filter(change => change.fromTechnicianId !== change.toTechnicianId).length;
    return { ...pending, status: "REPAIR_PREVIEW", proposal_id: row.id, reassigned_jobs: reassigned };
  } catch (error) {
    // The scheduler could not place the absent technician's day, for example a location it cannot route.
    if (error instanceof DispatchRefused && error.status === 422) return { ...pending, status: "NEEDS_COORDINATION", reason: error.message };
    if (error instanceof DispatchRefused) throw new TimeOffRefused(error.status, error.message);
    throw error;
  }
}

/** The request's status, locked for the rest of the transaction. */
async function lockedStatus(tx: Prisma.TransactionClient, id: string): Promise<string | undefined> {
  const rows = await tx.$queryRaw<Array<{ status: string }>>`SELECT status FROM time_off_request WHERE id = ${id} FOR UPDATE`;
  return rows[0]?.status;
}

/** Saves an analysis while the request is still pending; a request denied or decided meanwhile keeps its own state. */
async function saveReport(id: string, status: string, report: ApiTimeOffReport, requestStatus?: "READY"): Promise<boolean> {
  return prisma.$transaction(async tx => {
    if (await lockedStatus(tx, id) !== "PENDING") return false;
    await tx.timeOffReport.update({ where: { requestId: id }, data: { status, progress: analysisProgress(report), data: report } });
    if (requestStatus !== undefined) await tx.timeOffRequest.update({ where: { id }, data: { status: requestStatus } });
    return true;
  });
}

/**
 * Analyzes the next pending day of a pending request and saves the result. When every day is analyzed, a request
 * whose every day can be approved becomes READY (and is approved at once if it starts two weeks out or later);
 * otherwise it needs coordination. A failure that a retry can fix is saved as ROUTING_FAILURE or ANALYSIS_FAILURE.
 */
export async function analyzeApiTimeOff(clientId: string, id: string): Promise<{ requestId: string; status: string; progress: number; done: boolean }> {
  const request = await load(clientId, id);
  if (request.status !== "PENDING" || (request.reportStatus !== AWAITING_ANALYSIS && request.reportStatus !== "ANALYZING"))
    return { requestId: id, status: request.reportStatus, progress: analysisProgress(request.report), done: true };
  const index = nextPendingDay(request.report);
  let report = request.report;
  if (index !== null) {
    const pending = report.days[index];
    if (pending === undefined) throw new Error(`Day ${index} of request ${id} is missing`);
    await prisma.timeOffReport.updateMany({ where: { requestId: id, status: AWAITING_ANALYSIS }, data: { status: "ANALYZING" } });
    let analyzed: ApiTimeOffDay;
    try { analyzed = await analyzeDay(clientId, request, pending); }
    catch (error) {
      if (!(error instanceof TimeOffRefused) || error.status === 404) throw error;
      if (error.status === 429) throw error;
      const status = error.status === 503 ? "ROUTING_FAILURE" : "ANALYSIS_FAILURE";
      if (!await saveReport(id, status, { ...report, failure: error.message })) return settledResult(clientId, id);
      return { requestId: id, status, progress: analysisProgress(report), done: true };
    }
    report = { ...report, days: report.days.map((item, at) => at === index ? analyzed : item) };
    if (nextPendingDay(report) !== null) {
      if (!await saveReport(id, "ANALYZING", report)) return settledResult(clientId, id);
      return { requestId: id, status: "ANALYZING", progress: analysisProgress(report), done: false };
    }
  }
  const ready = feasible(report);
  if (!await saveReport(id, ready ? "READY" : "NEEDS_COORDINATION", report, ready ? "READY" : undefined)) return settledResult(clientId, id);
  if (ready && automaticallyApproved(request.firstDate, todayInTz(STORED_MINUTES_TIME_ZONE))) {
    try { return { ...(await approveApiTimeOff(clientId, id)), progress: 100, done: true }; }
    catch (error) { if (!(error instanceof TimeOffRefused)) throw error; }
  }
  return settledResult(clientId, id);
}

async function settledResult(clientId: string, id: string) {
  const settled = await load(clientId, id);
  return { requestId: id, status: settled.reportStatus, progress: analysisProgress(settled.report), done: true };
}

/** Sends a request back for a fresh analysis; its earlier repair proposals can no longer be applied. */
async function reanalyze(id: string, technicianId: string, intervals: readonly Interval[], why: string): Promise<void> {
  const proposals = await prisma.portalApiDailyProposal.findMany({ where: { timeOffRequestId: id, receiptId: null, commitRefusal: null }, select: { id: true } });
  for (const proposal of proposals) await markRefused(proposal.id, why);
  await prisma.$transaction(async tx => {
    const status = await lockedStatus(tx, id);
    if (status !== "PENDING" && status !== "READY") return;
    await tx.timeOffRequest.update({ where: { id }, data: { status: "PENDING" } });
    await tx.timeOffReport.update({ where: { requestId: id }, data: { status: AWAITING_ANALYSIS, progress: 0, data: initialApiReport(technicianId, intervals) } });
  });
}

/**
 * Approves a READY request: commits every day's repair at the scheduler, then in one transaction compares every
 * technician-day the repairs and the shift-free days cover, writes every repair, records the receipts and approves the
 * request. Any change since the analysis writes nothing and sends the request back for analysis.
 */
export async function approveApiTimeOff(clientId: string, id: string): Promise<{ requestId: string; status: string }> {
  const request = await load(clientId, id);
  if (request.status === "APPROVED") return { requestId: id, status: "APPROVED" };
  if (request.status !== "READY" || request.reportStatus !== "READY" || !feasible(request.report))
    throw new TimeOffRefused(409, "Fresh feasible report required");
  const now = new Date();
  for (const item of request.report.days) {
    const metro = await metroOn(request.technicianId, item.service_date);
    if (metro === null || frozen(item.service_date, now, metro.timezone)) {
      const days = request.report.days.map(other => other === item
        ? { service_date: item.service_date, start_min: item.start_min, end_min: item.end_min, status: "FROZEN_CSR_COORDINATION" as const, reason: FROZEN } : other);
      await prisma.$transaction(async tx => {
        if (await lockedStatus(tx, id) !== "READY") return;
        await tx.timeOffRequest.update({ where: { id }, data: { status: "PENDING" } });
        await tx.timeOffReport.update({ where: { requestId: id }, data: { status: "NEEDS_COORDINATION", progress: 100, data: { ...request.report, days } } });
      });
      throw new TimeOffRefused(409, "Frozen date requires CSR coordination");
    }
  }
  const writes: ReceiptWrite[] = [];
  const receipts: Array<{ proposalId: string; write: ReceiptWrite }> = [];
  const unchanged: TechnicianDayVersion[] = [];
  for (const item of request.report.days) {
    if (unchangedDay(item.status)) {
      if (item.last_modified === undefined) throw new Error("A day left as it is keeps its timestamp");
      unchanged.push({ technicianId: request.technicianId, serviceDate: item.service_date, lastModified: item.last_modified });
      continue;
    }
    const row = await prisma.portalApiDailyProposal.findFirst({ where: { id: item.proposal_id, clientId, kind: "REPAIR", timeOffRequestId: id } });
    if (row === null) throw new TimeOffRefused(409, `The repair proposal for ${item.service_date} is missing`);
    if (row.receiptId !== null) throw new Error(`Repair proposal ${row.id} was applied without approving request ${id}`);
    let committed;
    try { committed = row.commitRefusal !== null ? { refused: row.commitRefusal } : await commitAtScheduler(clientId, row); }
    catch (error) {
      if (error instanceof DispatchRefused) throw new TimeOffRefused(error.status, error.message);
      throw error;
    }
    if ("refused" in committed) {
      await reanalyze(id, request.technicianId, request.intervals, committed.refused);
      throw new TimeOffRefused(409, REANALYZE);
    }
    const write = { receipt: committed.receipt, complete: committed.routed };
    writes.push(write);
    receipts.push({ proposalId: row.id, write });
  }
  try {
    await writeReceipts(clientId, writes, { unchanged, record: async tx => {
      for (const { proposalId, write } of receipts) await recordReceipt(tx, proposalId, write.receipt);
      const approved = await tx.timeOffRequest.updateMany({ where: { id, status: "READY" }, data: { status: "APPROVED", decidedAt: new Date() } });
      if (approved.count !== 1) throw new StaleReceipt(`Time-off request ${id} is no longer ready`);
      await tx.timeOffReport.update({ where: { requestId: id }, data: { status: "APPLIED" } });
    } });
  } catch (error) {
    if (!(error instanceof StaleReceipt)) throw error;
    const current = await prisma.timeOffRequest.findUnique({ where: { id }, select: { status: true } });
    if (current?.status === "APPROVED") return { requestId: id, status: "APPROVED" };
    await reanalyze(id, request.technicianId, request.intervals, REANALYZE);
    throw new TimeOffRefused(409, REANALYZE);
  }
  return { requestId: id, status: "APPROVED" };
}

/** Sends a request whose analysis failed back to be analyzed again. */
export async function retryApiTimeOff(clientId: string, id: string): Promise<{ requestId: string; status: "PENDING" }> {
  const request = await load(clientId, id);
  if (request.status !== "PENDING") throw new TimeOffRefused(409, "Only pending requests can be retried");
  if (request.reportStatus !== "ROUTING_FAILURE" && request.reportStatus !== "ANALYSIS_FAILURE" && request.reportStatus !== "NEEDS_COORDINATION")
    throw new TimeOffRefused(409, "Only failed analyses can be retried");
  await reanalyze(id, request.technicianId, request.intervals, "The request was analyzed again");
  return { requestId: id, status: "PENDING" };
}

export async function denyApiTimeOff(clientId: string, id: string): Promise<{ requestId: string; status: "DENIED" }> {
  return prisma.$transaction(async tx => {
    const rows = await tx.$queryRaw<Array<{ status: string }>>`SELECT r.status FROM time_off_request r JOIN technician t ON t.id = r."technicianId"
      WHERE r.id = ${id} AND t."clientId" = ${clientId} FOR UPDATE OF r`;
    const current = rows[0];
    if (current === undefined) throw new TimeOffRefused(404, "Time-off request not found");
    if (current.status === "DENIED") return { requestId: id, status: "DENIED" as const };
    if (current.status !== "PENDING" && current.status !== "READY") throw new TimeOffRefused(409, "Approved requests cannot be denied");
    await tx.timeOffRequest.update({ where: { id }, data: { status: "DENIED", decidedAt: new Date() } });
    await tx.timeOffReport.updateMany({ where: { requestId: id }, data: { status: "DENIED" } });
    return { requestId: id, status: "DENIED" as const };
  });
}

/** Locks the client's technician and the technician-day, refusing a frozen day or one that has appointments. */
async function lockEditableDay(tx: Prisma.TransactionClient, clientId: string, technicianId: string, serviceDate: string): Promise<void> {
  if (frozen(serviceDate, new Date(), STORED_MINUTES_TIME_ZONE)) throw new TimeOffRefused(409, "Frozen date requires CSR coordination");
  const technician = await tx.$queryRaw<Array<{ id: string }>>`SELECT id FROM technician WHERE id = ${technicianId} AND "clientId" = ${clientId} FOR UPDATE`;
  if (technician.length === 0) throw new TimeOffRefused(404, "Technician not found");
  await tx.$executeRaw`INSERT INTO schedule_day (id, "technicianId", "serviceDate", version) VALUES (${randomUUID()}, ${technicianId}, ${stamp(serviceDate)}, 0)
    ON CONFLICT ("technicianId", "serviceDate") DO NOTHING`;
  await tx.$queryRaw`SELECT version FROM schedule_day WHERE "technicianId" = ${technicianId} AND "serviceDate" = ${stamp(serviceDate)} FOR UPDATE`;
  if (await tx.appointment.count({ where: { technicianId, serviceDate: stamp(serviceDate), cancelledAt: null } }) > 0)
    throw new TimeOffRefused(409, "Existing appointments require schedule repair");
}

/** A date's shift for one technician, written by the portal as host; refused while the day has appointments. */
export async function setApiAvailability(clientId: string, input: { technicianId: string; date: string; available: boolean; shiftStartMin?: number | null; shiftEndMin?: number | null }): Promise<{ success: true }> {
  const start = input.shiftStartMin ?? null, end = input.shiftEndMin ?? null;
  if (input.available && (start === null || end === null || start < 0 || end > 1440 || start >= end)) throw new TimeOffRefused(400, "Invalid shift hours");
  await prisma.$transaction(async tx => {
    await lockEditableDay(tx, clientId, input.technicianId, input.date);
    const hours = input.available ? { shiftStartMin: start, shiftEndMin: end } : { shiftStartMin: null, shiftEndMin: null };
    await tx.technicianShiftOverride.upsert({ where: { technicianId_serviceDate: { technicianId: input.technicianId, serviceDate: stamp(input.date) } },
      create: { technicianId: input.technicianId, serviceDate: stamp(input.date), available: input.available, ...hours },
      update: { available: input.available, ...hours } });
    await tx.$executeRaw`UPDATE schedule_day SET version = version + 1 WHERE "technicianId" = ${input.technicianId} AND "serviceDate" = ${stamp(input.date)}`;
  });
  return { success: true };
}

export async function deleteApiAvailability(clientId: string, input: { technicianId: string; date: string }): Promise<{ success: true }> {
  await prisma.$transaction(async tx => {
    await lockEditableDay(tx, clientId, input.technicianId, input.date);
    await tx.technicianShiftOverride.deleteMany({ where: { technicianId: input.technicianId, serviceDate: stamp(input.date) } });
    await tx.$executeRaw`UPDATE schedule_day SET version = version + 1 WHERE "technicianId" = ${input.technicianId} AND "serviceDate" = ${stamp(input.date)}`;
  });
  return { success: true };
}

/** A technician's qualification; removing one is refused while the technician has appointments for that service. */
export async function setApiQualification(clientId: string, input: { technicianId: string; serviceId: string; qualified: boolean }): Promise<{ success: true }> {
  await prisma.$transaction(async tx => {
    const technician = await tx.$queryRaw<Array<{ id: string }>>`SELECT id FROM technician WHERE id = ${input.technicianId} AND "clientId" = ${clientId} FOR UPDATE`;
    if (technician.length === 0) throw new TimeOffRefused(404, "Technician not found");
    if (!input.qualified) {
      if (await tx.appointment.count({ where: { technicianId: input.technicianId, cancelledAt: null, job: { serviceId: input.serviceId } } }) > 0)
        throw new TimeOffRefused(409, "Existing schedule requires qualification repair");
      await tx.technicianQualification.deleteMany({ where: { technicianId: input.technicianId, serviceId: input.serviceId } });
    } else {
      await tx.technicianQualification.upsert({ where: { technicianId_serviceId: { technicianId: input.technicianId, serviceId: input.serviceId } },
        create: { technicianId: input.technicianId, serviceId: input.serviceId }, update: {} });
    }
  });
  return { success: true };
}
