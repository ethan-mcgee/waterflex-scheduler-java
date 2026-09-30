import { GeocoderError, searchAddress, type AddressInput } from "./geocode";
import { prisma } from "./prisma";
import { isWithinMetroServiceArea } from "./serviceArea";
import { confirmedHomePin } from "./technicianHomePin";
import { todayInTz } from "./date";

export type HomePinProvenance = "GEOCODER_HOUSE" | "MANUALLY_CONFIRMED";

export interface HomeLocation {
  homeLat: number;
  homeLng: number;
  homePinProvenance: HomePinProvenance;
  homeAddressLine1: string;
  homeAddressCity: string;
  homeAddressState: string;
  homeAddressPostalCode: string;
}

export type HomeLocationResult = { ok: true; location: HomeLocation } | { ok: false; status: number; error: string };

export function geocoderStatus(error: GeocoderError): number {
  return error.kind === "timeout" ? 504 : error.kind === "malformed" ? 502 : 503;
}

// Geocodes the address, checks the confirmed pin against the candidates, and requires the pin to be
// inside the metro service area. Shared by technician creation and profile edits.
export async function resolveHomeLocation(input: {
  address: AddressInput;
  confirmedPin: { lat: number; lng: number };
  manuallyConfirmed: boolean;
  metroId: string;
}): Promise<HomeLocationResult> {
  let candidates;
  try { candidates = await searchAddress(input.address); }
  catch (error) {
    if (!(error instanceof GeocoderError)) throw error;
    return { ok: false, status: geocoderStatus(error), error: error.message };
  }
  if (!candidates.length) return { ok: false, status: 422, error: "No matching address found. Check the street, city, state, and ZIP." };
  const selected = confirmedHomePin(input.confirmedPin, candidates, input.manuallyConfirmed);
  if (!selected) return { ok: false, status: 422, error: "Confirm a home pin near the matching street" };
  if (!(await isWithinMetroServiceArea(input.confirmedPin.lat, input.confirmedPin.lng, input.metroId)))
    return { ok: false, status: 422, error: "The address is outside the selected depot's metro service area" };
  return { ok: true, location: {
    homeLat: input.confirmedPin.lat, homeLng: input.confirmedPin.lng,
    homePinProvenance: selected.precision === "ROOFTOP" && !input.manuallyConfirmed ? "GEOCODER_HOUSE" : "MANUALLY_CONFIRMED",
    homeAddressLine1: input.address.line1, homeAddressCity: input.address.city,
    homeAddressState: input.address.state, homeAddressPostalCode: input.address.postalCode,
  } };
}

// Metro of the depot the technician is assigned to today (America/Chicago), or null when none is in effect.
export async function currentMetroIdFor(technicianId: string, now = new Date()): Promise<string | null> {
  const today = new Date(`${todayInTz("America/Chicago", now)}T00:00:00Z`);
  const assignment = await prisma.technicianDepotAssignment.findFirst({
    where: { technicianId, effectiveDate: { lte: today } },
    orderBy: { effectiveDate: "desc" },
    select: { depot: { select: { metroId: true } } },
  });
  return assignment?.depot.metroId ?? null;
}
