export type RoadFeature = {
  type: "Feature";
  properties: { technicianId: string; interval: string; legIndex: number; seconds: number; meters: number };
  geometry: { type: "LineString"; coordinates: [number, number][] };
};
export type GeometryResponse = {
  type: "FeatureCollection";
  routingIdentity: string;
  serviceDate: string;
  phase: string;
  features: RoadFeature[];
  stops: Array<{ id: string; technicianId: string; sequence: number; plannedStart: string; lat: number; lng: number }>;
  endpoints?: Array<{ technicianId: string; departureLat: number; departureLng: number; returnLat: number; returnLng: number }>;
  segments?: Record<string, Array<{ departure: string; returnedAt: string; visitIds: string[] }>>;
};

function object(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null;
}
function coordinate(value: unknown, limit: number): value is number {
  return typeof value === "number" && Number.isFinite(value) && Math.abs(value) <= limit;
}
function nonnegative(value: unknown): value is number {
  return typeof value === "number" && Number.isFinite(value) && value >= 0;
}

// Validate the complete payload before any source or marker is replaced.
export function isDispatchGeometry(value: unknown, date: string, phase: string): value is GeometryResponse {
  if (!object(value) || value.type !== "FeatureCollection" || value.serviceDate !== date || value.phase !== phase ||
      typeof value.routingIdentity !== "string" || !value.routingIdentity ||
      !Array.isArray(value.features) || !Array.isArray(value.stops)) return false;
  if (value.endpoints !== undefined && (!Array.isArray(value.endpoints) || !value.endpoints.every((endpoint: unknown) =>
    object(endpoint) && typeof endpoint.technicianId === "string" && coordinate(endpoint.departureLat, 90) &&
    coordinate(endpoint.departureLng, 180) && coordinate(endpoint.returnLat, 90) && coordinate(endpoint.returnLng, 180)))) return false;
  if (!value.features.every((feature: unknown) => {
    if (!object(feature) || feature.type !== "Feature" || !object(feature.properties) || !object(feature.geometry)) return false;
    const { properties, geometry } = feature;
    return typeof properties.technicianId === "string" && typeof properties.interval === "string" &&
      nonnegative(properties.legIndex) && Number.isInteger(properties.legIndex) &&
      nonnegative(properties.seconds) && nonnegative(properties.meters) && geometry.type === "LineString" &&
      Array.isArray(geometry.coordinates) && geometry.coordinates.length >= 2 &&
      geometry.coordinates.every((point: unknown) => Array.isArray(point) && point.length === 2 &&
        coordinate(point[0], 180) && coordinate(point[1], 90));
  })) return false;
  if (value.segments !== undefined) {
    if (!object(value.segments) || Array.isArray(value.segments)) return false;
    for (const [technician, segments] of Object.entries(value.segments)) {
      if (!technician || !Array.isArray(segments)) return false;
      const seen = new Set<string>(); let previous = -Infinity;
      for (const segment of segments) {
        if (!object(segment) || typeof segment.departure !== "string" || typeof segment.returnedAt !== "string" ||
          !Array.isArray(segment.visitIds) || segment.visitIds.length === 0) return false;
        const departure = Date.parse(segment.departure), returnedAt = Date.parse(segment.returnedAt);
        if (!Number.isFinite(departure) || !Number.isFinite(returnedAt) || departure >= returnedAt || departure < previous) return false;
        for (const id of segment.visitIds) {
          if (typeof id !== "string" || !id || seen.has(id)) return false;
          const stop: unknown = value.stops.find((item: unknown) => object(item) && item.id === id && item.technicianId === technician);
          if (!object(stop) || typeof stop.plannedStart !== "string" || !Number.isFinite(Date.parse(stop.plannedStart)) ||
            Date.parse(stop.plannedStart) < departure || Date.parse(stop.plannedStart) >= returnedAt) return false;
          seen.add(id);
        }
        previous = returnedAt;
      }
      if (seen.size !== value.stops.filter((stop: unknown) => object(stop) && stop.technicianId === technician).length) return false;
    }
  }
  return value.stops.every((stop: unknown) => object(stop) && typeof stop.id === "string" &&
    typeof stop.technicianId === "string" && Number.isInteger(stop.sequence) &&
    typeof stop.plannedStart === "string" && Number.isFinite(Date.parse(stop.plannedStart)) &&
    coordinate(stop.lng, 180) && coordinate(stop.lat, 90)) &&
    (value.stops.length === 0 ? value.features.length === 0 : value.features.length > 0);
}
