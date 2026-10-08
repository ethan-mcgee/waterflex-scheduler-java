import { randomUUID } from "node:crypto";
import type { PortalApiDailyProposal } from "@prisma/client";
import { prisma } from "./prisma";
import { buildClientSnapshot, SnapshotError } from "./clientSnapshot";
import { checkDailyReceipt, checkProposal, proposalChanges, storedProposal, type Placement, type ProposalChange } from "./apiDispatchCore";
import { currentLastModified, StaleReceipt, writeReceipt } from "./apiReceipt";
import { commitDailyProposal, createDailyProposal, SchedulerApiError, type CommitReceipt, type DailyProposal } from "./schedulerApi";

/**
 * Daily optimization through the public scheduling API, with the portal acting as WaterFlex Software for one client:
 * propose with a snapshot of the client's own technicians and appointments in the metro, keep the proposal, and apply
 * it by committing with current timestamps and writing the receipt with a compare-and-set.
 */

/** A dispatch step the dispatcher sees: an HTTP status and a message. */
export class DispatchRefused extends Error {
  constructor(readonly status: number, message: string) { super(message); this.name = "DispatchRefused"; }
}

const CHANGED = "The schedule changed since this proposal was made. Preview the day again.";

export type ProposalState = "OPEN" | "COMMITTED" | "REFUSED" | "NOT_COMMITTABLE";

export interface ApiProposalView {
  proposalId: string;
  serviceDate: string;
  createdAt: string;
  decision: DailyProposal["decision"];
  reason: string;
  costCents: number;
  state: ProposalState;
  committedAt: string | null;
  refusal: string | null;
  routes: DailyProposal["routes"];
  unresolvedAppointmentIds: string[];
  skippedTechnicianDays: DailyProposal["skippedTechnicianDays"];
  changes: ProposalChange[];
}

function stored(row: PortalApiDailyProposal) {
  const parsed = storedProposal.safeParse({ proposal: row.proposal, technicianDays: row.technicianDays, baseline: row.baseline });
  if (!parsed.success) throw new Error(`Stored proposal ${row.id} is not valid: ${parsed.error.message}`);
  if (parsed.data.proposal.proposalId !== row.id || parsed.data.proposal.decision !== row.decision) throw new Error(`Stored proposal ${row.id} does not match its row`);
  return parsed.data;
}

function view(row: PortalApiDailyProposal): ApiProposalView {
  const { proposal, baseline } = stored(row);
  const state: ProposalState = row.receiptId !== null ? "COMMITTED" : row.commitRefusal !== null ? "REFUSED"
    : proposal.decision === "IMPROVED" ? "OPEN" : "NOT_COMMITTABLE";
  return { proposalId: row.id, serviceDate: row.serviceDate.toISOString().slice(0, 10), createdAt: row.createdAt.toISOString(),
    decision: proposal.decision, reason: proposal.reason, costCents: proposal.costCents, state,
    committedAt: row.committedAt?.toISOString() ?? null, refusal: row.commitRefusal,
    routes: proposal.routes, unresolvedAppointmentIds: proposal.unresolvedAppointmentIds, skippedTechnicianDays: proposal.skippedTechnicianDays,
    changes: proposalChanges(baseline, proposal) };
}

function refusedFor(error: unknown): never {
  if (error instanceof SnapshotError) {
    if (error.code === "NOT_CONFIGURED") throw new DispatchRefused(503, "Scheduling is not set up for this client yet.");
    if (error.code === "METRO_NOT_FOUND" || error.code === "METRO_NOT_SERVED") throw new DispatchRefused(404, "Metro not found");
    throw new DispatchRefused(409, `The schedule needs attention before it can be optimized: ${error.message}`);
  }
  if (error instanceof SchedulerApiError) {
    if (error.code === "NOT_CONNECTED") { console.error("Scheduling API not connected", error.message); throw new DispatchRefused(503, "Scheduling is not connected for this client."); }
    if (error.code === "BUSY") throw new DispatchRefused(429, "The scheduler is busy. Try again shortly.");
    if (error.code === "INCOMPLETE_FACTS") throw new DispatchRefused(422, error.message);
    if (error.code === "ROUTING_UNAVAILABLE" || error.code === "CALCULATION_UNAVAILABLE" || error.code === "TRANSPORT")
      throw new DispatchRefused(503, `${error.message}. Try again shortly.`);
    console.error("Scheduling API call failed", error.status, error.code, error.message);
    throw new DispatchRefused(502, "The scheduling service failed. Try again, and if it fails again ask an administrator to check the logs.");
  }
  throw error;
}

/** Asks the scheduler for an optimized day of the client's technicians in the metro, and keeps the proposal. */
export async function proposeApiDay(clientId: string, metroId: string, serviceDate: string): Promise<ApiProposalView> {
  const requestId = randomUUID();
  let proposal: DailyProposal;
  let baseline: Placement[];
  let technicianDays: Array<{ technicianId: string; serviceDate: string }>;
  try {
    const snapshot = await buildClientSnapshot(clientId, metroId, [serviceDate]);
    technicianDays = snapshot.technicianDays.map(day => ({ technicianId: day.technicianId, serviceDate: day.serviceDate }));
    if (technicianDays.length === 0) throw new DispatchRefused(409, "No technician of this client works in this metro on that day.");
    // The snapshot's own placements, read in the same transaction as everything the scheduler sees.
    baseline = snapshot.appointments.map(item => ({ appointmentId: item.id, technicianId: item.technicianId, sequence: item.sequence,
      plannedStart: new Date(item.plannedStart).toISOString() }));
    proposal = await createDailyProposal(clientId, { requestId, serviceDate, snapshot });
    checkProposal(proposal, snapshot, serviceDate);
  } catch (error) {
    if (error instanceof DispatchRefused) throw error;
    return refusedFor(error);
  }
  const row = await prisma.portalApiDailyProposal.create({ data: { id: proposal.proposalId, clientId, metroId, serviceDate: new Date(`${serviceDate}T00:00:00Z`),
    requestId, decision: proposal.decision, proposal, technicianDays, baseline } });
  return view(row);
}

/** The client's latest proposals for a metro day, newest first. */
export async function apiProposalHistory(clientId: string, metroId: string, serviceDate: string): Promise<ApiProposalView[]> {
  const rows = await prisma.portalApiDailyProposal.findMany({ where: { clientId, metroId, serviceDate: new Date(`${serviceDate}T00:00:00Z`) },
    orderBy: { createdAt: "desc" }, take: 20 });
  return rows.map(view);
}

async function load(clientId: string, proposalId: string): Promise<PortalApiDailyProposal> {
  const row = await prisma.portalApiDailyProposal.findFirst({ where: { id: proposalId, clientId } });
  if (row === null) throw new DispatchRefused(404, "Proposal not found");
  return row;
}

/** Records that the proposal can no longer be applied, unless a concurrent commit already applied it. */
async function refuse(clientId: string, proposalId: string, message: string): Promise<ApiProposalView> {
  const marked = await prisma.portalApiDailyProposal.updateMany({ where: { id: proposalId, clientId, receiptId: null, commitRefusal: null }, data: { commitRefusal: message } });
  const row = await load(clientId, proposalId);
  if (row.receiptId !== null) return view(row);
  throw new DispatchRefused(409, marked.count === 1 ? message : row.commitRefusal ?? message);
}

/**
 * Applies an IMPROVED proposal: commits it with the current timestamps of every technician-day it covers, then writes
 * the receipt with a compare-and-set. A change to any of those days since the proposal refuses it for good, and
 * nothing is written. Applying a proposal that is already applied returns it unchanged.
 */
export async function commitApiProposal(clientId: string, proposalId: string): Promise<ApiProposalView> {
  let row = await load(clientId, proposalId);
  if (row.receiptId !== null) return view(row);
  if (row.commitRefusal !== null) throw new DispatchRefused(409, row.commitRefusal);
  const { proposal, technicianDays } = stored(row);
  if (proposal.decision !== "IMPROVED") throw new DispatchRefused(409, "Only an improved proposal can be applied.");
  // The commit's request ID is fixed before the first call, so a retry after a lost answer replays the same commit.
  await prisma.portalApiDailyProposal.updateMany({ where: { id: proposalId, commitRequestId: null }, data: { commitRequestId: randomUUID() } });
  row = await load(clientId, proposalId);
  const commitRequestId = row.commitRequestId;
  if (commitRequestId === null) throw new Error(`Proposal ${proposalId} has no commit request ID after it was set`);
  const current = await currentLastModified(prisma, technicianDays);
  const versions = [];
  for (const day of technicianDays) {
    const lastModified = current.get(`${day.technicianId}|${day.serviceDate}`);
    if (lastModified === undefined) return refuse(clientId, proposalId, CHANGED);
    versions.push({ ...day, lastModified });
  }
  let receipt: CommitReceipt;
  try {
    receipt = await commitDailyProposal(clientId, proposalId, { requestId: commitRequestId, technicianDays: versions });
  } catch (error) {
    if (error instanceof SchedulerApiError) {
      if (error.code === "STALE") return refuse(clientId, proposalId, CHANGED);
      // The same commit request ID with different timestamps: the day changed after an earlier attempt.
      if (error.code === "INVALID_REQUEST") { console.warn("Daily commit refused as invalid", proposalId, error.message); return refuse(clientId, proposalId, CHANGED); }
      if (error.code === "NOT_COMMITTABLE" || error.code === "NOT_FOUND" || error.code === "INCOMPLETE_FACTS") return refuse(clientId, proposalId, error.message);
    }
    return refusedFor(error);
  }
  const { days, routed } = checkDailyReceipt(receipt, proposal, technicianDays);
  try {
    await writeReceipt(clientId, receipt, days, { complete: routed, record: async tx => {
      const recorded = await tx.portalApiDailyProposal.updateMany({ where: { id: proposalId, receiptId: null, commitRefusal: null },
        data: { receiptId: receipt.receiptId, committedAt: new Date() } });
      if (recorded.count !== 1) throw new StaleReceipt(`Proposal ${proposalId} was already applied or refused`);
    } });
  } catch (error) {
    if (error instanceof StaleReceipt) return refuse(clientId, proposalId, CHANGED);
    throw error;
  }
  return view(await load(clientId, proposalId));
}
