import type { GeocodeResult } from "./geocode";

export const MAX_DEPOT_PIN_ADJUSTMENT_METERS = 250;

export function pinDistanceMeters(a: { lat: number; lng: number }, b: { lat: number; lng: number }): number {
  const radians = Math.PI / 180;
  const dLat = (b.lat - a.lat) * radians;
  const dLng = (b.lng - a.lng) * radians;
  const radius = 6371000;
  const arc = Math.sin(dLat / 2) ** 2 + Math.cos(a.lat * radians) * Math.cos(b.lat * radians) * Math.sin(dLng / 2) ** 2;
  return 2 * radius * Math.asin(Math.sqrt(arc));
}

export function nearbyCandidate(pin: { lat: number; lng: number }, candidates: GeocodeResult[]): GeocodeResult | null {
  const nearby = candidates.map(candidate => ({ candidate, distance: pinDistanceMeters(pin, candidate) }))
    .filter(item => item.distance <= MAX_DEPOT_PIN_ADJUSTMENT_METERS)
    .sort((a, b) => a.distance - b.distance);
  return nearby[0]?.candidate ?? null;
}
