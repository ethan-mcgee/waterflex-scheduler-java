import { pinDistanceMeters } from "./depotPin";
import type { GeocodeResult } from "./geocode";

const MAX_STREET_OFFSET_METERS = 100;

export function confirmedHomePin(pin: { lat: number; lng: number }, candidates: GeocodeResult[], manuallyConfirmed: boolean): GeocodeResult | null {
  return candidates.find(candidate => {
    if (candidate.precision === "ROOFTOP") return pinDistanceMeters(pin, candidate) <= 5;
    if (!manuallyConfirmed || !candidate.bounds) return false;
    const { south, north, west, east } = candidate.bounds;
    const nearest = { lat: Math.max(south, Math.min(north, pin.lat)), lng: Math.max(west, Math.min(east, pin.lng)) };
    return pinDistanceMeters(pin, nearest) <= MAX_STREET_OFFSET_METERS;
  }) ?? null;
}
