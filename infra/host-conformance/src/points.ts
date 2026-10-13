// Where the kit's synthetic technicians, appointments and jobs stand. Against a real routing service the points are
// spread over the host's bounding box; against infra/fixture-routing.mjs they are the fixed Omaha points it knows,
// because it routes nothing else.

export interface Point { readonly lat: number; readonly lng: number }

/** The Omaha points of infra/fixture-routing.mjs (its indices 2 to 16), in its order. */
export const FIXTURE_POINTS: readonly Point[] = [
  { lat: 41.25855, lng: -95.92929 }, { lat: 41.26088, lng: -96.18435 }, { lat: 41.17933, lng: -95.91875 },
  { lat: 41.31755, lng: -95.95176 }, { lat: 41.23891, lng: -96.01473 }, { lat: 41.27959, lng: -96.23327 },
  { lat: 41.21095, lng: -95.94185 }, { lat: 41.29995, lng: -96.02809 }, { lat: 41.25246, lng: -95.96034 },
  { lat: 41.15062, lng: -96.10707 }, { lat: 41.23851, lng: -95.89048 }, { lat: 41.1544, lng: -96.0422 },
  { lat: 41.2619, lng: -95.8608 }, { lat: 41.2864, lng: -96.2345 }, { lat: 41.2524, lng: -95.998 },
];

export interface Area { readonly south: number; readonly west: number; readonly north: number; readonly east: number }

/** Parses `south,west,north,east` (minimum latitude, minimum longitude, maximum latitude, maximum longitude). */
export function parseArea(text: string): Area {
  const values = text.split(",").map(value => value.trim());
  if (values.length !== 4 || values.some(value => !/^-?\d+(\.\d+)?$/.test(value)))
    throw new Error(`--area takes four numbers south,west,north,east, got "${text}"`);
  const [south, west, north, east] = values.map(Number) as [number, number, number, number];
  if (south < -90 || north > 90 || west < -180 || east > 180) throw new Error("--area is outside latitude and longitude ranges");
  if (!(south < north && west < east)) throw new Error("--area must give south < north and west < east");
  if (north - south > 1 || east - west > 1) throw new Error("--area spans more than one degree; give a box inside one served metro");
  return { south, west, north, east };
}

/** The radical inverse of `index` in `base`: a low-discrepancy sequence in [0, 1). */
function halton(index: number, base: number): number {
  let result = 0;
  let fraction = 1 / base;
  for (let rest = index; rest > 0; rest = Math.floor(rest / base)) {
    result += (rest % base) * fraction;
    fraction /= base;
  }
  return result;
}

/**
 * `count` distinct points spread evenly over the area, the same on every run so a failure can be repeated. They keep a
 * tenth of the box as margin from its edges and are rounded to five decimals (about a metre).
 */
export function areaPoints(area: Area, count: number): Point[] {
  const round = (value: number) => Math.round(value * 1e5) / 1e5;
  const points: Point[] = [];
  for (let index = 1; points.length < count; index++) {
    if (index > count * 10) throw new Error("--area is too small to hold distinct points");
    const lat = round(area.south + (area.north - area.south) * (0.1 + 0.8 * halton(index, 2)));
    const lng = round(area.west + (area.east - area.west) * (0.1 + 0.8 * halton(index, 3)));
    if (!points.some(point => point.lat === lat && point.lng === lng)) points.push({ lat, lng });
  }
  return points;
}
