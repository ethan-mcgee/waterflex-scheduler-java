import { z } from "zod";
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
}
export interface PendingAddressCandidate extends AddressCandidate {
  ordinal: number;
  selectionUnit: number;
}
export interface CandidateBatch {
  candidates: PendingAddressCandidate[];
  randomState: number;
  roundRobinCursor: number;
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

const RANDOM_INCREMENT = 0x6d2b79f5;
function randomStep(state: number): { state: number; value: number } {
  const next = (state + RANDOM_INCREMENT) >>> 0;
  let value = next;
  value = Math.imul(value ^ (value >>> 15), value | 1);
  value ^= value + Math.imul(value ^ (value >>> 7), value | 61);
  return { state: next, value: ((value ^ (value >>> 14)) >>> 0) / 0x100000000 };
}

export function initialAddressRandomState(config: TestConfig): number {
  let state = config.seed >>> 0;
  for (let index = 0; index < config.count * 2; index++) state = randomStep(state).state;
  return state;
}

export function createAddressCandidateBatch(config: TestConfig, area: TestServiceArea, acceptedOrdinals: ReadonlySet<number>,
  randomState: number, roundRobinCursor: number, limit = 12): CandidateBatch {
  validateServiceArea(area);
  if (!Number.isInteger(randomState) || randomState < 0 || randomState > 0xffffffff) throw new Error("Invalid saved address random state.");
  if (!Number.isInteger(roundRobinCursor) || roundRobinCursor < 0 || roundRobinCursor >= config.count) throw new Error("Invalid saved address round-robin cursor.");
  const plans = generateTestInputPlans(config);
  const candidates: PendingAddressCandidate[] = [];
  let state = randomState;
  let cursor = roundRobinCursor;
  let inspected = 0;
  const random = () => { const next = randomStep(state); state = next.state; return next.value; };
  while (candidates.length < Math.min(limit, config.count - acceptedOrdinals.size) && inspected < config.count) {
    const plan = plans[cursor];
    cursor = (cursor + 1) % config.count;
    inspected++;
    if (!plan || acceptedOrdinals.has(plan.ordinal)) continue;
    const point = samplePoint(random, area);
    candidates.push({ id: String(plan.ordinal), ordinal: plan.ordinal, serviceCode: plan.serviceCode, selectionUnit: plan.selectionUnit, ...point });
  }
  return { candidates, randomState: state, roundRobinCursor: cursor };
}

function validateServiceArea(area: TestServiceArea) {
  if (!Number.isFinite(area.radiusMi) || area.radiusMi <= 0 || !/^[A-Z]{2}$/.test(area.stateCode) || area.depots.length === 0 ||
      area.depots.some(point => !Number.isFinite(point.lat) || !Number.isFinite(point.lng))) throw new Error("Invalid configured Omaha service area.");
}

export async function evaluateAddressCandidateBatch(batch: readonly PendingAddressCandidate[], area: TestServiceArea,
  collisions: AddressCollisionSets, dependencies: AddressGenerationDependencies) {
  validateServiceArea(area);
  const controller = new AbortController();
  const reversed = await Promise.all(batch.map(item => dependencies.reverse(item.lat, item.lng, controller.signal, area.stateCode)));
  const candidates = batch.flatMap((item, index) => {
    const location = reversed[index];
    if (!location || !area.depots.some(depot => haversineMiles(location.lat, location.lng, depot.lat, depot.lng) <= area.radiusMi)
        || collisions.addresses.has(addressKey(location)) || collisions.coordinates.has(coordinateKey(location))) return [];
    return [{ item, location, candidate: { id: item.id, serviceCode: item.serviceCode, lat: location.lat, lng: location.lng } }];
  });
  const routable = candidates.length ? await dependencies.routable(candidates.map(value => value.candidate), controller.signal) : new Set<string>();
  const accepted: Array<{ ordinal: number; serviceCode: string; selectionUnit: number; location: GeneratedTestLocation }> = [];
  const usedAddresses = new Set(collisions.addresses), usedCoordinates = new Set(collisions.coordinates);
  for (const value of candidates) {
    if (!routable.has(value.candidate.id)) continue;
    const address = addressKey(value.location), coordinate = coordinateKey(value.location);
    if (usedAddresses.has(address) || usedCoordinates.has(coordinate)) continue;
    usedAddresses.add(address); usedCoordinates.add(coordinate);
    accepted.push({ ordinal: value.item.ordinal, serviceCode: value.item.serviceCode, selectionUnit: value.item.selectionUnit, location: value.location });
  }
  return accepted;
}

export async function generateRealTestInputs(config: TestConfig, area: TestServiceArea, collisions: AddressCollisionSets,
  dependencies: AddressGenerationDependencies) {
  validateServiceArea(area);
  const accepted = new Map<number, Awaited<ReturnType<typeof evaluateAddressCandidateBatch>>[number]>();
  let state = initialAddressRandomState(config), cursor = 0;
  for (let attempt = 0; attempt < 100 && accepted.size < config.count; attempt++) {
    const batch = createAddressCandidateBatch(config, area, new Set(accepted.keys()), state, cursor, 12);
    state = batch.randomState; cursor = batch.roundRobinCursor;
    const found = await evaluateAddressCandidateBatch(batch.candidates, area, {
      addresses: new Set([...collisions.addresses, ...[...accepted.values()].map(value => addressKey(value.location))]),
      coordinates: new Set([...collisions.coordinates, ...[...accepted.values()].map(value => coordinateKey(value.location))]),
    }, dependencies);
    for (const value of found) if (!accepted.has(value.ordinal)) accepted.set(value.ordinal, value);
  }
  if (accepted.size !== config.count) throw new Error(`Could not create the run: generated ${accepted.size} of ${config.count} routable unique house addresses; shortfall ${config.count - accepted.size}. No run was saved.`);
  return [...accepted.values()].sort((left, right) => left.ordinal - right.ordinal);
}
