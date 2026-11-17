import { assembleSnapshot, effectiveEndpointPolicy, loadSnapshotFacts } from "./clientSnapshot";
import { scheduleDriveOf } from "./apiScheduleDriveCore";
import { drawRoutes } from "./schedulerApi";
import type { ScheduleDrive } from "./scheduleDrive";

/**
 * The drive times around every visit of one client's metro day, as the scheduler draws the day's routes in their
 * current order. A day with no visits has nothing to draw and is answered without a call.
 */
export async function apiScheduleDrive(clientId: string, metroId: string, serviceDate: string): Promise<ScheduleDrive> {
  const facts = await loadSnapshotFacts(clientId, metroId, [serviceDate]);
  const snapshot = assembleSnapshot(facts);
  if (!snapshot.appointments.some(item => item.serviceDate === serviceDate)) return { serviceDate, technicians: [], skipped: [] };
  const drawn = await drawRoutes(clientId, { serviceDate, snapshot });
  return scheduleDriveOf(serviceDate, snapshot, drawn, technicianId => effectiveEndpointPolicy(facts, technicianId, serviceDate));
}
