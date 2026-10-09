import type { GeometryResponse } from "./dispatchGeometry";
import type { PublicLocation, PublicSnapshot } from "./publicApi";
import type { RouteGeometry } from "./schedulerApi";

const point = (location: PublicLocation): { lat: number; lng: number } | null => "lat" in location ? { lat: location.lat, lng: location.lng } : null;

/**
 * The dispatch board's road geometry from the scheduler's drawing of a client's snapshot (POST /api/v1/routes/geometry):
 * each leg becomes a road feature in its absence-bounded interval, each located stop a marker at its planned start,
 * and each drawn technician-day its departure and return. A skipped technician-day is not drawn.
 */
export function dispatchGeometryOf(serviceDate: string, snapshot: PublicSnapshot, drawn: RouteGeometry): GeometryResponse {
  const result: GeometryResponse = { type: "FeatureCollection", routingIdentity: drawn.routingIdentity, serviceDate, phase: "current", features: [], stops: [], endpoints: [] };
  for (const route of drawn.routes) {
    const { technicianId } = route;
    if (route.serviceDate !== serviceDate) throw new Error(`The scheduler drew ${technicianId} on ${route.serviceDate}, not ${serviceDate}`);
    const day = snapshot.technicianDays.find(item => item.technicianId === technicianId && item.serviceDate === serviceDate);
    if (day === undefined) throw new Error(`The scheduler drew ${technicianId}, who does not work on ${serviceDate} in the snapshot`);
    const booked = snapshot.appointments.filter(item => item.technicianId === technicianId && item.serviceDate === serviceDate);
    const drawnIds = route.stops.map(stop => stop.appointmentId).sort(), bookedIds = booked.map(item => item.id).sort();
    if (drawnIds.length !== bookedIds.length || drawnIds.some((id, index) => id !== bookedIds[index]))
      throw new Error(`The scheduler drew different stops than ${technicianId} has on ${serviceDate}`);
    const start = point(day.start), end = point(day.end);
    // The portal sends route endpoints as the technician's home or depot coordinates, never as an address alone.
    if (start === null || end === null)
      throw new Error(`The route endpoints of ${technicianId} on ${serviceDate} have no coordinates`);
    result.endpoints?.push({ technicianId, departureLat: start.lat, departureLng: start.lng, returnLat: end.lat, returnLng: end.lng });
    for (const stop of route.stops) {
      const appointment = booked.find(item => item.id === stop.appointmentId);
      if (appointment === undefined) throw new Error(`The scheduler drew unknown stop ${stop.appointmentId}`);
      result.stops.push({ id: stop.appointmentId, technicianId, sequence: stop.sequence, plannedStart: appointment.plannedStart, lat: stop.lat, lng: stop.lng });
    }
    for (const leg of route.legs)
      result.features.push({ type: "Feature", properties: { technicianId, interval: `${technicianId}:${leg.segment}`, legIndex: leg.legIndex, seconds: leg.seconds, meters: leg.meters },
        geometry: { type: "LineString", coordinates: leg.coordinates } });
  }
  return result;
}
