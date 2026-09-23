import { z } from "zod";

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
    town: z.string().optional(), village: z.string().optional(), postcode: z.string().trim().min(1),
    state: z.string().optional(), country_code: z.string().trim().min(1) })
    .refine(detail => [detail.city, detail.town, detail.village].some(value => value?.trim()), "Missing city or town"),
  boundingbox: z.tuple([coordinateString(90), coordinateString(90), coordinateString(180), coordinateString(180)]).optional(),
});
const suffixes: Record<string, string> = { dr: "drive", st: "street", rd: "road", ave: "avenue", blvd: "boulevard", ln: "lane", ct: "court", pl: "place", cir: "circle", pkwy: "parkway" };
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
  return value.toLowerCase().replace(/[^a-z0-9 ]/g, " ").trim().split(/\s+/).map(word => suffixes[word] ?? word).join(" ");
}
function streetName(line1: string): string { return line1.replace(/^\s*\d+[a-z]?\s+/i, ""); }
function matches(value: string | undefined, expected: string): boolean { return value != null && normalize(value) === normalize(expected); }

async function query(address: AddressInput, withState: boolean): Promise<GeocodeResult[]> {
  const url = new URL("/search", process.env.NOMINATIM_URL ?? "http://localhost:8082");
  url.searchParams.set("street", address.line1);
  url.searchParams.set("city", address.city);
  if (withState) url.searchParams.set("state", address.state);
  url.searchParams.set("postalcode", address.postalCode);
  url.searchParams.set("country", "United States");
  url.searchParams.set("format", "jsonv2");
  url.searchParams.set("addressdetails", "1");
  url.searchParams.set("limit", "5");
  let response: Response;
  try { response = await fetch(url, { signal: AbortSignal.timeout(8000), cache: "no-store" }); }
  catch (error) { throw new GeocoderError(error instanceof Error && (error.name === "TimeoutError" || error.name === "AbortError") ? "timeout" : "unavailable"); }
  if (!response.ok) throw new GeocoderError("unavailable");
  let raw: unknown;
  try { raw = await response.json(); } catch { throw new GeocoderError("malformed"); }
  if (!Array.isArray(raw)) throw new GeocoderError("malformed");
  if (!raw.length) return [];
  const parsed = raw.map(item => resultSchema.safeParse(item));
  if (parsed.every(item => !item.success)) throw new GeocoderError("malformed");
  const requestedNumber = address.line1.match(/^\s*(\d+[A-Za-z]?)\b/)?.[1];
  return parsed.flatMap(item => {
    if (!item.success) return [];
    const result = item.data, detail = result.address;
    const expectedState = states[address.state.trim().toUpperCase()] ?? address.state;
    if (!matches(detail.country_code, "us") || !matches(detail.postcode, address.postalCode) ||
      ![detail.city, detail.town, detail.village].some(city => matches(city, address.city)) ||
      (detail.state != null && !matches(detail.state, expectedState) && !matches(detail.state, address.state)) ||
      !matches(detail.road, streetName(address.line1)) ||
      (detail.house_number != null && !matches(detail.house_number, requestedNumber ?? ""))) return [];
    const bounds = result.boundingbox;
    const validBounds = bounds && bounds[0] <= bounds[1] && bounds[2] <= bounds[3];
    const precision = requestedNumber && matches(detail.house_number, requestedNumber) ? "ROOFTOP" as const : "APPROXIMATE" as const;
    if (precision === "APPROXIMATE" && !validBounds) throw new GeocoderError("malformed");
    return [{ lat: result.lat, lng: result.lon, precision, ...(validBounds ? { bounds: { south: bounds[0], north: bounds[1], west: bounds[2], east: bounds[3] } } : {}) }];
  });
}

export async function searchAddress(address: AddressInput): Promise<GeocodeResult[]> {
  const first = await query(address, true);
  return first.length ? first : query(address, false);
}

export async function geocodeAddress(address: AddressInput): Promise<GeocodeResult | null> {
  return (await searchAddress(address))[0] ?? null;
}
