import { assembleSnapshot, effectiveEndpointPolicy, loadSnapshotFacts } from "./clientSnapshot";
import { dispatchGeometryOf, type GeometryOptions } from "./apiGeometryCore";
import { drawRoutes, evaluateRoutes, SchedulerApiError } from "./schedulerApi";
import type { GeometryResponse } from "./dispatchGeometry";
import type { PublicSnapshot } from "./publicApi";

/**
 * The day's cost as the scheduler times the current order (POST /api/v1/routes/evaluate). A day that does not hold, or
 * a scheduler that cannot price it, leaves the cost unavailable with the reason; it is never shown as zero.
 */
export async function currentCost(clientId: string, serviceDate: string, snapshot: PublicSnapshot): Promise<GeometryOptions["cost"]> {
  try {
    const evaluation = await evaluateRoutes(clientId, { serviceDate, snapshot });
    return evaluation.feasible ? { costCents: evaluation.costCents } : { unavailableReason: "The current plan does not hold within its shifts" };
  } catch (error) {
    if (error instanceof SchedulerApiError) return { unavailableReason: error.message };
    throw error;
  }
}

/** The dispatch board's current road routes of one client's metro day, drawn and priced by the scheduler from the client's snapshot. */
export async function apiDispatchGeometry(clientId: string, metroId: string, serviceDate: string): Promise<GeometryResponse> {
  const facts = await loadSnapshotFacts(clientId, metroId, [serviceDate]);
  const snapshot = assembleSnapshot(facts);
  const [drawn, cost] = await Promise.all([drawRoutes(clientId, { serviceDate, snapshot }), currentCost(clientId, serviceDate, snapshot)]);
  return dispatchGeometryOf(serviceDate, snapshot, drawn,
    { phase: "current", cost, anchors: technicianId => effectiveEndpointPolicy(facts, technicianId, serviceDate) });
}
