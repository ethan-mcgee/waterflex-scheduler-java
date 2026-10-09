import { buildClientSnapshot } from "./clientSnapshot";
import { dispatchGeometryOf } from "./apiGeometryCore";
import { drawRoutes } from "./schedulerApi";
import type { GeometryResponse } from "./dispatchGeometry";

/** The dispatch board's current road routes of one client's metro day, drawn by the scheduler from the client's snapshot. */
export async function apiDispatchGeometry(clientId: string, metroId: string, serviceDate: string): Promise<GeometryResponse> {
  const snapshot = await buildClientSnapshot(clientId, metroId, [serviceDate]);
  return dispatchGeometryOf(serviceDate, snapshot, await drawRoutes(clientId, { serviceDate, snapshot }));
}
