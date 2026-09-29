import { z } from "zod";
import { normalizeStreet } from "./streetNormalization";

export type GeocodePrecision = "ROOFTOP" | "APPROXIMATE";
export interface GeocodeResult { lat: number; lng: number; precision: GeocodePrecision; bounds?: { south: number; north: number; west: number; east: number } }
export interface AddressInput { line1: string; city: string; state: string; postalCode: string }
export class GeocoderError extends Error {
  constructor(public readonly kind: "timeout" | "unavailable" | "malformed") {
    super(kind === "timeout" ? "Address lookup timed out. Try again." : kind === "malformed" ? "Address lookup returned invalid data. Try again later." : "Address lookup is unavailable. Try again later.");
  }
}

const coordinateString = (limit: number) => z.string().trim().regex(/^[+-]?(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][+-]?\d+)?$/)
  .refine(v => Number.isFinite(Number(v)) && Math.abs(Number(v)) <= limit).transform(Number);
const resultSchema = z.object({
  lat: coordinateString(90), lon: coordinateString(180),
  address: z.object({ house_number: z.string().optional(), road: z.string().trim().min(1), city: z.string().optional(),
    town: z.string().optional(), village: z.string().optional(), hamlet: z.string().optional(),
    suburb: z.string().optional(), municipality: z.string().optional(), postcode: z.string().trim().min(1).optional(),
    state: z.string().optional(), country_code: z.string().trim().min(1).optional() }),
  boundingbox: z.tuple([coordinateString(90), coordinateString(90), coordinateString(180), coordinateString(180)])
    .refine(b => b[0] <= b[1] && b[2] <= b[3]).optional(),
});
const states: Record<string, string> = {
  AL: "alabama", AK: "alaska", AZ: "arizona", AR: "arkansas", CA: "california", CO: "colorado",
  CT: "connecticut", DE: "delaware", FL: "florida", GA: "georgia", HI: "hawaii", ID: "idaho",
  IL: "illinois", IN: "indiana", IA: "iowa", KS: "kansas", KY: "kentucky", LA: "louisiana",
  ME: "maine", MD: "maryland", MA: "massachusetts", MI: "michigan", MN: "minnesota",
  MS: "mississippi", MO: "missouri", MT: "montana", NE: "nebraska", NV: "nevada",
  NH: "new hampshire", NJ: "new jersey", NM: "new mexico", NY: "new york",
  NC: "north carolina", ND: "north dakota", OH: "ohio", OK: "oklahoma", OR: "oregon",
  PA: "pennsylvania", RI: "rhode island", SC: "south carolina", SD: "south dakota",
  TN: "tennessee", TX: "texas", UT: "utah", VT: "vermont", VA: "virginia",
  WA: "washington", WV: "west virginia", WI: "wisconsin", WY: "wyoming", DC: "district of columbia",
};
function normalize(value: string): string {
  return value.toLowerCase().replace(/[^a-z0-9 ]/g, " ").trim().split(/\s+/).join(" ");
}
function streetName(line1: string): string { return line1.replace(/^\s*\d+[a-z]?\s+/i, ""); }
function matches(value: string | undefined, expected: string): boolean { return value != null && normalize(value) === normalize(expected); }

export interface SearchBounds { south: number; north: number; west: number; east: number }
async function query(address: AddressInput, stage: number, signal: AbortSignal, envelope?: SearchBounds): Promise<GeocodeResult[]> {
  const url = new URL("/search", process.env.NOMINATIM_URL ?? "http://localhost:8082");
  url.searchParams.set("street", stage === 2 ? streetName(address.line1) : address.line1);
  if (stage < 2) url.searchParams.set("city", address.city);
  if (stage === 0) url.searchParams.set("state", address.state);
  if (envelope) {
    url.searchParams.set("viewbox", `${envelope.west},${envelope.north},${envelope.east},${envelope.south}`);
    url.searchParams.set("bounded", "1");
  }
  url.searchParams.set("postalcode", address.postalCode);
  url.searchParams.set("country", "United States");
  url.searchParams.set("format", "jsonv2");
  url.searchParams.set("addressdetails", "1");
  url.searchParams.set("limit", "5");
  let response: Response;
  try { response = await fetch(url, { signal, cache: "no-store" }); }
  catch (error) { throw new GeocoderError(error instanceof Error && (error.name === "TimeoutError" || error.name === "AbortError") ? "timeout" : "unavailable"); }
  if (!response.ok) throw new GeocoderError("unavailable");
  let raw: unknown;
  try { raw = await response.json(); } catch { throw new GeocoderError(signal.aborted ? "timeout" : "malformed"); }
  if (!Array.isArray(raw)) throw new GeocoderError("malformed");
  if (!raw.length) return [];
  const parsed = raw.map(item => resultSchema.safeParse(item));
  if (parsed.some(item => !item.success)) throw new GeocoderError("malformed");
  const requestedNumber = address.line1.match(/^\s*(\d+[A-Za-z]?)\b/)?.[1];
  return parsed.flatMap(item => {
    if (!item.success) return [];
    const result = item.data, detail = result.address;
    const expectedState = states[address.state.trim().toUpperCase()] ?? address.state;
    const localities = [detail.city, detail.town, detail.village, detail.hamlet, detail.suburb, detail.municipality].filter(v => v?.trim());
    const localityMatches = localities.some(city => matches(city, address.city));
    if ((detail.country_code != null && !matches(detail.country_code, "us")) || (detail.postcode != null && !matches(detail.postcode, address.postalCode)) ||
      (localities.length > 0 && !localityMatches) ||
      (detail.state != null && !matches(detail.state, expectedState) && !matches(detail.state, address.state)) ||
      normalizeStreet(detail.road) !== normalizeStreet(streetName(address.line1)) ||
      (detail.house_number != null && !matches(detail.house_number, requestedNumber ?? ""))) return [];
    const bounds = result.boundingbox;
    const validBounds = bounds && bounds[0] <= bounds[1] && bounds[2] <= bounds[3];
    if (envelope && (result.lat < envelope.south || result.lat > envelope.north || result.lon < envelope.west || result.lon > envelope.east)) return [];
    const precision = requestedNumber && matches(detail.house_number, requestedNumber) && localityMatches &&
      matches(detail.postcode, address.postalCode) && matches(detail.country_code, "us") ? "ROOFTOP" as const : "APPROXIMATE" as const;
    if (precision === "APPROXIMATE" && !validBounds) throw new GeocoderError("malformed");
    return [{ lat: result.lat, lng: result.lon, precision, ...(validBounds ? { bounds: { south: bounds[0], north: bounds[1], west: bounds[2], east: bounds[3] } } : {}) }];
  });
}

export async function searchAddress(address: AddressInput, bounds?: SearchBounds): Promise<GeocodeResult[]> {
  const signal = AbortSignal.timeout(8000);
  const candidates = new Map<string, GeocodeResult>();
  for (let stage = 0; stage < 3; stage++) {
    for (const result of await query(address, stage, signal, bounds)) {
      const key = `${result.lat},${result.lng}`;
      if (!candidates.has(key) || result.precision === "ROOFTOP") candidates.set(key, result);
    }
    if ([...candidates.values()].some(result => result.precision === "ROOFTOP")) break;
  }
  return [...candidates.values()].sort((a, b) => Number(b.precision === "ROOFTOP") - Number(a.precision === "ROOFTOP"));
}

export async function geocodeAddress(address: AddressInput): Promise<GeocodeResult | null> {
  return (await searchAddress(address))[0] ?? null;
}
