import { addCalendarDays } from "./date";

export const FAKE_DATA_PREFIX = "fake-data:";
export const FAKE_DATA_VERSION = "v1";
export const OMAHA_METRO_ID = "metro-omaha";
export const OMAHA_TIMEZONE = "America/Chicago";
export const MAX_FAKE_DATA_DAYS = 31;
export const MAX_FAKE_DATA_CALLS = 100;
const MILES_PER_LATITUDE_DEGREE = 69;
const MAX_FAKE_LOCATION_OFFSET_MI = 3.5;

const OMAHA_STREET_NAMES = [
  "Briarwood",
  "Cedar",
  "Cottonwood",
  "Deer Creek",
  "Elm",
  "Fieldcrest",
  "Hawthorne",
  "Hickory",
  "Meadow",
  "Oak",
  "Prairie",
  "Redwood",
  "Ridgeview",
  "Stonegate",
  "Walnut",
  "Willow",
] as const;

const OMAHA_STREET_SUFFIXES = ["Ave", "Blvd", "Cir", "Ct", "Dr", "Ln", "Rd", "St", "Way"] as const;

export const FAKE_SERVICE_CODES = [
  "FILTER_SWAP",
  "SYSTEM_INSPECTION",
  "REPAIR_DIAGNOSTIC",
  "SOFTENER_INSTALL",
] as const;

export type FakeServiceCode = (typeof FAKE_SERVICE_CODES)[number];

export interface FakeLocation {
  slug: string;
  neighborhood: string;
  line1: string;
  line2?: string;
  city: string;
  state: string;
  postalCode: string;
  lat: number;
  lng: number;
}

export const OMAHA_FAKE_LOCATIONS: readonly FakeLocation[] = [
  { slug: "downtown", neighborhood: "Downtown", line1: "1001 Douglas St", city: "Omaha", state: "NE", postalCode: "68102", lat: 41.258554971264, lng: -95.92929138095 },
  { slug: "village-pointe", neighborhood: "Village Pointe", line1: "17305 Davenport St", city: "Omaha", state: "NE", postalCode: "68118", lat: 41.260875783104, lng: -96.184353353368 },
  { slug: "bellevue", neighborhood: "Bellevue", line1: "1111 Bellevue Blvd N", city: "Bellevue", state: "NE", postalCode: "68005", lat: 41.179330863328, lng: -95.918752793844 },
  { slug: "north-omaha", neighborhood: "North Omaha", line1: "2707 Redick Ave", city: "Omaha", state: "NE", postalCode: "68112", lat: 41.317551364281, lng: -95.951761691699 },
  { slug: "aksarben", neighborhood: "Aksarben", line1: "6700 Mercy Rd", city: "Omaha", state: "NE", postalCode: "68106", lat: 41.238908008583, lng: -96.014731773907 },
  { slug: "elkhorn", neighborhood: "Elkhorn", line1: "2100 Reading Plaza", city: "Elkhorn", state: "NE", postalCode: "68022", lat: 41.279594060013, lng: -96.233270653911 },
  { slug: "south-omaha", neighborhood: "South Omaha", line1: "4802 S 20th St", city: "Omaha", state: "NE", postalCode: "68107", lat: 41.21095483807, lng: -95.941850414474 },
  { slug: "northwest-omaha", neighborhood: "Northwest Omaha", line1: "7400 Military Ave", city: "Omaha", state: "NE", postalCode: "68134", lat: 41.299951260388, lng: -96.028087077189 },
  { slug: "midtown", neighborhood: "Midtown", line1: "3202 Leavenworth St", city: "Omaha", state: "NE", postalCode: "68105", lat: 41.252459046584, lng: -95.960343081419 },
  { slug: "papillion", neighborhood: "Papillion", line1: "12356 Ballpark Way", city: "Papillion", state: "NE", postalCode: "68046", lat: 41.150622313378, lng: -96.107073146709 },
  { slug: "council-bluffs", neighborhood: "Council Bluffs", line1: "1 Arena Way", city: "Council Bluffs", state: "IA", postalCode: "51501", lat: 41.238514725023, lng: -95.890479700891 },
] as const;

const FIRST_NAMES = [
  "Avery", "Blake", "Cameron", "Drew", "Emerson", "Finley", "Harper", "Jordan",
  "Kai", "Logan", "Morgan", "Parker", "Quinn", "Reese", "Rowan", "Sawyer",
] as const;

const LAST_NAMES = [
  "Bennett", "Campbell", "Diaz", "Edwards", "Foster", "Garcia", "Hayes", "Johnson",
  "Kim", "Lewis", "Mitchell", "Nguyen", "Owens", "Patel", "Rivera", "Sullivan",
] as const;

export interface FakeDataInput {
  startDate: string;
  endDate: string;
  totalCalls: number;
  seed?: number;
}

export interface ValidatedFakeDataInput extends FakeDataInput {
  weekdays: string[];
}

export interface PlannedFakeCall {
  ordinal: number;
  preferredDate: string;
  firstName: string;
  lastName: string;
  email: string;
  phone: string;
  serviceCode: FakeServiceCode;
}

function isCalendarDate(value: string): boolean {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(value)) return false;
  const [year, month, day] = value.split("-").map(Number);
  const parsed = new Date(Date.UTC(year ?? 0, (month ?? 1) - 1, day ?? 1));
  return parsed.toISOString().slice(0, 10) === value;
}

function calendarDayNumber(value: string): number {
  const [year, month, day] = value.split("-").map(Number);
  return Math.floor(Date.UTC(year ?? 0, (month ?? 1) - 1, day ?? 1) / 86_400_000);
}

export function isWeekday(value: string): boolean {
  const day = new Date(`${value}T00:00:00Z`).getUTCDay();
  return day >= 1 && day <= 5;
}

export function validateFakeDataInput(input: FakeDataInput): ValidatedFakeDataInput {
  if (!isCalendarDate(input.startDate) || !isCalendarDate(input.endDate)) {
    throw new Error("Start and end dates must be valid YYYY-MM-DD calendar dates.");
  }
  const rangeDays = calendarDayNumber(input.endDate) - calendarDayNumber(input.startDate) + 1;
  if (rangeDays <= 0) throw new Error("End date must be on or after start date.");
  if (rangeDays > MAX_FAKE_DATA_DAYS) {
    throw new Error(`Date range cannot exceed ${MAX_FAKE_DATA_DAYS} calendar days.`);
  }
  if (!Number.isInteger(input.totalCalls) || input.totalCalls < 1 || input.totalCalls > MAX_FAKE_DATA_CALLS) {
    throw new Error(`Total calls must be an integer from 1 to ${MAX_FAKE_DATA_CALLS}.`);
  }
  if (
    input.seed !== undefined &&
    (!Number.isInteger(input.seed) || input.seed < 0 || input.seed > 0xffffffff)
  ) {
    throw new Error("Seed must be an unsigned 32-bit integer.");
  }

  const weekdays = Array.from({ length: rangeDays }, (_, index) => addCalendarDays(input.startDate, index))
    .filter(isWeekday);
  if (weekdays.length === 0) throw new Error("Date range must include at least one weekday.");
  return { ...input, weekdays };
}

export function createSeededRandom(seed: number): () => number {
  let state = seed >>> 0;
  return () => {
    state = (state + 0x6d2b79f5) >>> 0;
    let value = state;
    value = Math.imul(value ^ (value >>> 15), value | 1);
    value ^= value + Math.imul(value ^ (value >>> 7), value | 61);
    return ((value ^ (value >>> 14)) >>> 0) / 0x100000000;
  };
}

export function shuffleSeeded<T>(values: readonly T[], random: () => number): T[] {
  const result = [...values];
  for (let index = result.length - 1; index > 0; index--) {
    const swapIndex = Math.floor(random() * (index + 1));
    [result[index], result[swapIndex]] = [result[swapIndex] as T, result[index] as T];
  }
  return result;
}

export function randomLocationForCall(location: FakeLocation, random: () => number): FakeLocation {
  const houseNumber = 100 + Math.floor(random() * 19_900);
  const streetName = OMAHA_STREET_NAMES[Math.floor(random() * OMAHA_STREET_NAMES.length)] as string;
  const streetSuffix = OMAHA_STREET_SUFFIXES[Math.floor(random() * OMAHA_STREET_SUFFIXES.length)] as string;
  const angle = random() * Math.PI * 2;
  const radiusMiles = Math.sqrt(random()) * MAX_FAKE_LOCATION_OFFSET_MI;
  const latitudeOffset = (radiusMiles * Math.cos(angle)) / MILES_PER_LATITUDE_DEGREE;
  const milesPerLongitudeDegree = MILES_PER_LATITUDE_DEGREE * Math.cos((location.lat * Math.PI) / 180);
  const longitudeOffset = (radiusMiles * Math.sin(angle)) / milesPerLongitudeDegree;

  return {
    ...location,
    slug: `${location.slug}-${Math.floor(random() * 0x100000000).toString(16).padStart(8, "0")}`,
    line1: `${houseNumber} ${streetName} ${streetSuffix}`,
    line2: undefined,
    lat: Number((location.lat + latitudeOffset).toFixed(6)),
    lng: Number((location.lng + longitudeOffset).toFixed(6)),
  };
}

export function serviceCodeForUnit(value: number): FakeServiceCode {
  if (value < 0 || value >= 1) throw new Error("Service selection value must be from 0 up to 1.");
  if (value < 0.45) return "FILTER_SWAP";
  if (value < 0.75) return "SYSTEM_INSPECTION";
  if (value < 0.95) return "REPAIR_DIAGNOSTIC";
  return "SOFTENER_INSTALL";
}

export function buildCallPlans(
  input: ValidatedFakeDataInput,
  random: () => number
): PlannedFakeCall[] {
  const plans = Array.from({ length: input.totalCalls }, (_, index) => {
    const firstName = FIRST_NAMES[Math.floor(random() * FIRST_NAMES.length)] as string;
    const lastName = LAST_NAMES[Math.floor(random() * LAST_NAMES.length)] as string;
    const serial = (index + 1).toString().padStart(3, "0");
    return {
      ordinal: index + 1,
      preferredDate: input.weekdays[Math.floor(random() * input.weekdays.length)] as string,
      firstName,
      lastName,
      email: `${firstName}.${lastName}.${serial}@fake.waterflex.invalid`.toLowerCase(),
      phone: `402-555-${(1000 + index).toString().slice(-4)}`,
      serviceCode: serviceCodeForUnit(random()),
    };
  });
  return shuffleSeeded(plans, random);
}

export function candidateDates(
  preferredDate: string,
  weekdays: readonly string[],
  random: () => number
): string[] {
  return [preferredDate, ...shuffleSeeded(weekdays.filter((date) => date !== preferredDate), random)];
}

export function fakeExternalId(date: string, seed: number, ordinal: number, attempt: number): string {
  return `${FAKE_DATA_PREFIX}${FAKE_DATA_VERSION}:${date}:${seed.toString(16).padStart(8, "0")}:${ordinal}:${attempt}`;
}

export function dateFromFakeExternalId(externalId: string | null): string | null {
  if (!externalId?.startsWith(FAKE_DATA_PREFIX)) return null;
  return externalId.split(":").find((part) => isCalendarDate(part)) ?? null;
}
