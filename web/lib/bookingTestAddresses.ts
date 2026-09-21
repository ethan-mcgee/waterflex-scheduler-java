import { z } from "zod";
import { createSeededRandom } from "./fakeDataCore";
import { generateTestInputPlans, type TestConfig } from "./bookingTestCore";
import { haversineMiles } from "./geo";

export interface TestDepot { lat: number; lng: number }
export interface TestServiceArea { radiusMi: number; stateCode: string; depots: TestDepot[] }
export interface GeneratedTestLocation {
  slug: string; neighborhood: string; line1: string; city: string; state: string; postalCode: string; lat: number; lng: number;
}
export interface AddressCollisionSets { addresses: Set<string>; coordinates: Set<string> }
export interface AddressCandidate { id: string; serviceCode: string; lat: number; lng: number }
export interface AddressGenerationDependencies {
  reverse(lat: number, lng: number, signal: AbortSignal, stateCode: string): Promise<GeneratedTestLocation | null>;
  routable(candidates: AddressCandidate[], signal: AbortSignal): Promise<Set<string>>;
  timeoutMs?: number;
}

const coordinateString = (limit: number) => z.string().trim().regex(/^[+-]?(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][+-]?\d+)?$/)
  .refine(value => Number.isFinite(Number(value)) && Math.abs(Number(value)) <= limit).transform(Number);
const reverseResult = z.object({
  lat: coordinateString(90), lon: coordinateString(180),
  address: z.object({
    house_number: z.string().trim().min(1), road: z.string().trim().min(1),
    city: z.string().trim().min(1).optional(), town: z.string().trim().min(1).optional(), village: z.string().trim().min(1).optional(),
    municipality: z.string().trim().min(1).optional(), state: z.string().trim().min(1).optional(), postcode: z.string().trim().min(1),
    country_code: z.string().trim().toLowerCase(), neighbourhood: z.string().trim().min(1).optional(),
    suburb: z.string().trim().min(1).optional(), city_district: z.string().trim().min(1).optional(),
  }),
});

export function addressKey(location: Pick<GeneratedTestLocation, "line1" | "city" | "state" | "postalCode">): string {
  return [location.line1, location.city, location.state, location.postalCode].map(value => value.trim().toLowerCase().replace(/\s+/g, " ")).join("|");
}
export function coordinateKey(location: Pick<GeneratedTestLocation, "lat" | "lng">): string {
  return `${location.lat.toFixed(5)},${location.lng.toFixed(5)}`;
}

export async function reverseTestAddress(lat: number, lng: number, signal: AbortSignal, stateCode: string): Promise<GeneratedTestLocation | null> {
  const url = new URL("/reverse", process.env.NOMINATIM_URL ?? "http://localhost:8082");
  url.searchParams.set("lat", String(lat)); url.searchParams.set("lon", String(lng));
  url.searchParams.set("format", "jsonv2"); url.searchParams.set("addressdetails", "1"); url.searchParams.set("zoom", "18");
  try {
    const response = await fetch(url, { cache: "no-store", signal: AbortSignal.any([signal, AbortSignal.timeout(8_000)]),
      headers: { "User-Agent": "WaterFlex local sequential booking test" } });
    if (!response.ok) return null;
    const parsed = reverseResult.safeParse(await response.json() as unknown);
    if (!parsed.success || parsed.data.address.country_code !== "us") return null;
    const address = parsed.data.address;
    const city = address.city ?? address.town ?? address.village ?? address.municipality;
    if (!city) return null;
    const normalizedState = address.state?.trim().toLowerCase();
    if (normalizedState && ![stateCode.toLowerCase(), `us-${stateCode.toLowerCase()}`, ...(stateCode === "NE" ? ["nebraska"] : [])].includes(normalizedState)) return null;
    const line1 = `${address.house_number} ${address.road}`;
    const neighborhood = address.neighbourhood ?? address.suburb ?? address.city_district ?? city;
    return { slug: addressKey({ line1, city, state: stateCode, postalCode: address.postcode }), neighborhood, line1, city, state: stateCode,
      postalCode: address.postcode, lat: parsed.data.lat, lng: parsed.data.lon };
  } catch { return null; }
}

function samplePoint(random: () => number, area: TestServiceArea): { lat: number; lng: number } {
  const depot = area.depots[Math.min(area.depots.length - 1, Math.floor(random() * area.depots.length))];
  if (!depot) throw new Error("The Omaha service area has no depot.");
  const radius = Math.sqrt(random()) * area.radiusMi;
  const angle = random() * Math.PI * 2;
  const lat = depot.lat + (radius * Math.cos(angle)) / 69;
  const lng = depot.lng + (radius * Math.sin(angle)) / (69 * Math.cos(depot.lat * Math.PI / 180));
  if (!Number.isFinite(lat) || !Number.isFinite(lng) || Math.abs(lat) > 90 || Math.abs(lng) > 180) throw new Error("Invalid configured Omaha service area.");
  return { lat, lng };
}

export async function generateRealTestInputs(config: TestConfig, area: TestServiceArea, collisions: AddressCollisionSets,
  dependencies: AddressGenerationDependencies) {
  if (!Number.isFinite(area.radiusMi) || area.radiusMi <= 0 || !/^[A-Z]{2}$/.test(area.stateCode) || area.depots.length === 0 ||
      area.depots.some(point => !Number.isFinite(point.lat) || !Number.isFinite(point.lng))) throw new Error("Invalid configured Omaha service area.");
  const plans = generateTestInputPlans(config);
  const random = createSeededRandom(config.seed);
  // Consume the same service and selection draws before coordinate sampling.
  for (let index = 0; index < plans.length * 2; index++) random();
  const accepted = new Map<number, GeneratedTestLocation>();
  const usedAddresses = new Set(collisions.addresses), usedCoordinates = new Set(collisions.coordinates);
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), dependencies.timeoutMs ?? 150_000);
  try {
    attempts: for (let attempt = 0; attempt < 100 && accepted.size < plans.length; attempt++) {
      const pending = plans.filter(plan => !accepted.has(plan.ordinal)).map(plan => ({ plan, point: samplePoint(random, area) }));
      for (let offset = 0; offset < pending.length; offset += 12) {
        if (controller.signal.aborted) break;
        const chunk = pending.slice(offset, offset + 12);
        const reversed = await Promise.all(chunk.map(item => dependencies.reverse(item.point.lat, item.point.lng, controller.signal, area.stateCode)));
        const candidates = chunk.flatMap((item, index) => {
          const location = reversed[index];
          if (!location || !area.depots.some(depot => haversineMiles(location.lat, location.lng, depot.lat, depot.lng) <= area.radiusMi)
              || usedAddresses.has(addressKey(location)) || usedCoordinates.has(coordinateKey(location))) return [];
          return [{ item, location, candidate: { id: String(item.plan.ordinal), serviceCode: item.plan.serviceCode, lat: location.lat, lng: location.lng } }];
        });
        let routable = new Set<string>();
        try { if (candidates.length) routable = await dependencies.routable(candidates.map(value => value.candidate), controller.signal); }
        catch (error) { if (controller.signal.aborted) break attempts; throw error; }
        for (const value of candidates) {
          if (!routable.has(value.candidate.id) || accepted.has(value.item.plan.ordinal)) continue;
          const address = addressKey(value.location), coordinate = coordinateKey(value.location);
          if (usedAddresses.has(address) || usedCoordinates.has(coordinate)) continue;
          usedAddresses.add(address); usedCoordinates.add(coordinate); accepted.set(value.item.plan.ordinal, value.location);
        }
      }
    }
  } finally { clearTimeout(timer); }
  if (accepted.size !== config.count) throw new Error(`Could not create the run: generated ${accepted.size} of ${config.count} routable unique house addresses; shortfall ${config.count - accepted.size}. No run was saved.`);
  return plans.map(plan => ({ ...plan, location: accepted.get(plan.ordinal) ?? (() => { throw new Error("Generated address missing"); })() }));
}
