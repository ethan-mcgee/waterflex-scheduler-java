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
 * Distinct points spread evenly over the area, the same sequence on every run so a failure can be repeated. They keep
 * a tenth of the box as margin from its edges and are rounded to five decimals (about a metre).
 */
export class AreaPoints {
  readonly #seen = new Set<string>();
  #index: number;

  /** `start` skips that many points of the sequence, so runs with different starts share no point. */
  constructor(readonly area: Area, start = 0) {
    if (!Number.isInteger(start) || start < 0) throw new Error("The point sequence start must be a whole number");
    this.#index = start;
  }

  next(): Point {
    const round = (value: number) => Math.round(value * 1e5) / 1e5;
    for (let attempts = 0; attempts < 1000; attempts++) {
      this.#index++;
      const lat = round(this.area.south + (this.area.north - this.area.south) * (0.1 + 0.8 * halton(this.#index, 2)));
      const lng = round(this.area.west + (this.area.east - this.area.west) * (0.1 + 0.8 * halton(this.#index, 3)));
      const key = `${lat},${lng}`;
      if (!this.#seen.has(key)) {
        this.#seen.add(key);
        return { lat, lng };
      }
    }
    throw new Error("--area is too small to hold more distinct points");
  }
}

/** The first `count` points of {@link AreaPoints}. */
export function areaPoints(area: Area, count: number): Point[] {
  const stream = new AreaPoints(area);
  return Array.from({ length: count }, () => stream.next());
}

/**
 * Points drawn from a list of known-routable locations (real job addresses), for runs whose generated days must route
 * the way a dealer's do: random points in a box land where no road is within the router's snapping distance. The list
 * is walked in a seeded order; after each full pass every point is moved by a small deterministic offset (under about
 * 90 metres, still within reach of its road), so a long cold run never sends the same point twice.
 */
export class PointPool {
  readonly #points: readonly Point[];
  readonly #seen = new Set<string>();
  #index: number;

  constructor(points: readonly Point[], start = 0) {
    if (points.length === 0) throw new Error("The point pool is empty");
    if (!Number.isInteger(start) || start < 0) throw new Error("The point pool start must be a whole number");
    // A fixed shuffle (a multiplicative stride coprime with the length), so consecutive points are not neighbours.
    const stride = PointPool.#coprimeStride(points.length);
    this.#points = Array.from({ length: points.length }, (_, i) => {
      const point = points[(i * stride) % points.length];
      if (point === undefined) throw new Error("Point pool index out of range");
      return point;
    });
    this.#index = start;
  }

  static #coprimeStride(length: number): number {
    const gcd = (a: number, b: number): number => (b === 0 ? a : gcd(b, a % b));
    let stride = Math.max(1, Math.floor(length * 0.618));
    while (gcd(stride, length) !== 1) stride++;
    return stride;
  }

  get size(): number { return this.#points.length; }

  next(): Point {
    const round = (value: number) => Math.round(value * 1e5) / 1e5;
    for (let attempts = 0; attempts < 1000; attempts++) {
      const position = this.#index++;
      const base = this.#points[position % this.#points.length];
      if (base === undefined) throw new Error("Point pool index out of range");
      const pass = Math.floor(position / this.#points.length);
      // Pass 0 is the point itself; later passes move it by up to 0.0008 degrees each way.
      const lat = pass === 0 ? base.lat : round(base.lat + 0.0016 * (halton(position, 2) - 0.5));
      const lng = pass === 0 ? base.lng : round(base.lng + 0.0016 * (halton(position, 3) - 0.5));
      const key = `${lat},${lng}`;
      if (!this.#seen.has(key)) {
        this.#seen.add(key);
        return { lat, lng };
      }
    }
    throw new Error("The point pool cannot give more distinct points");
  }
}

/** A points file ({"points": [{"lat", "lng"}, ...]}), every point checked; a bad entry is refused, never skipped. */
export function parsePointPool(json: unknown, source: string): Point[] {
  const fail = (why: string): never => { throw new Error(`${source}: ${why}`); };
  if (typeof json !== "object" || json === null || !Array.isArray((json as { points?: unknown }).points)) return fail("no points array");
  const points = (json as { points: unknown[] }).points;
  if (points.length === 0) return fail("the points array is empty");
  return points.map((item, index) => {
    const lat = (item as { lat?: unknown } | null)?.lat, lng = (item as { lng?: unknown } | null)?.lng;
    if (typeof lat !== "number" || typeof lng !== "number" || !Number.isFinite(lat) || !Number.isFinite(lng) || lat < -90 || lat > 90 || lng < -180 || lng > 180)
      return fail(`point ${index} is not a latitude and longitude`);
    return { lat, lng };
  });
}
