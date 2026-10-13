import { assembleSnapshot, effectiveEndpointPolicy, loadSnapshotFacts } from "./clientSnapshot";
import { dispatchGeometryOf } from "./apiGeometryCore";
import { baselineMatches, overlayProposal } from "./apiProposalGeometryCore";
import { CHANGED, DispatchRefused, loadDailyProposal, refusedFor, storedProposalOf } from "./apiDispatch";
import { drawRoutes } from "./schedulerApi";
import type { GeometryResponse } from "./dispatchGeometry";

/**
 * The road routes of one of the client's improved daily proposals, drawn by the scheduler on the live day with every
 * appointment moved where the proposal puts it. A day that changed since the proposal is refused, never drawn.
 */
export async function apiProposalGeometry(clientId: string, proposalId: string): Promise<GeometryResponse> {
  const row = await loadDailyProposal(clientId, proposalId);
  const { proposal, technicianDays, baseline } = storedProposalOf(row);
  if (proposal.decision !== "IMPROVED") throw new DispatchRefused(409, "Only an improved proposal has optimized routes to draw.");
  const serviceDate = row.serviceDate.toISOString().slice(0, 10);
  try {
    const facts = await loadSnapshotFacts(clientId, row.metroId, [serviceDate]);
    const live = assembleSnapshot(facts);
    if (!baselineMatches(live, baseline, technicianDays)) throw new DispatchRefused(409, CHANGED);
    const snapshot = overlayProposal(live, proposal);
    const drawn = await drawRoutes(clientId, { serviceDate, snapshot });
    return dispatchGeometryOf(serviceDate, snapshot, drawn, { phase: "proposal", cost: { costCents: proposal.costCents },
      anchors: technicianId => effectiveEndpointPolicy(facts, technicianId, serviceDate) });
  } catch (error) {
    if (error instanceof DispatchRefused) throw error;
    return refusedFor(error);
  }
}
