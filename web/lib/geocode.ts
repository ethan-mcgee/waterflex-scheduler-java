export type GeocodePrecision = "ROOFTOP" | "APPROXIMATE";

export interface GeocodeResult { lat: number; lng: number; precision: GeocodePrecision }
export interface AddressInput { line1: string; city: string; state: string; postalCode: string }

interface NominatimResult {
  lat: string;
  lon: string;
  address?: { house_number?: string };
}

export async function searchAddress(address: AddressInput): Promise<GeocodeResult[]> {
  const url = new URL("/search", process.env.NOMINATIM_URL ?? "http://localhost:8082");
  url.searchParams.set("street", address.line1);
  url.searchParams.set("city", address.city);
  url.searchParams.set("state", address.state);
  url.searchParams.set("postalcode", address.postalCode);
  url.searchParams.set("country", "United States");
  url.searchParams.set("format", "jsonv2");
  url.searchParams.set("addressdetails", "1");
  url.searchParams.set("limit", "5");
  try {
    const response = await fetch(url, { signal: AbortSignal.timeout(8000), cache: "no-store" });
    if (!response.ok) return [];
    const results = (await response.json()) as NominatimResult[];
    const first = results[0];
    if (!first) return [];
    const requestedNumber = address.line1.match(/^\s*(\d+[A-Za-z]?)\b/)?.[1]?.toLowerCase();
    const exact = results.length === 1 && requestedNumber && first.address?.house_number?.toLowerCase() === requestedNumber;
    return results.map((result, index) => ({
      lat: Number(result.lat), lng: Number(result.lon),
      precision: index === 0 && exact ? "ROOFTOP" as const : "APPROXIMATE" as const,
    })).filter((result) => Number.isFinite(result.lat) && Number.isFinite(result.lng)
      && Math.abs(result.lat) <= 90 && Math.abs(result.lng) <= 180);
  } catch { return []; }
}

export async function geocodeAddress(address: AddressInput): Promise<GeocodeResult | null> {
  return (await searchAddress(address))[0] ?? null;
}
