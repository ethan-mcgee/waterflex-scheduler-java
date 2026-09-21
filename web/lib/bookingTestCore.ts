import { addCalendarDays, calendarDateInTz } from "./date";
import { createSeededRandom, FAKE_SERVICE_CODES, isWeekday, OMAHA_FAKE_LOCATIONS, OMAHA_TIMEZONE } from "./fakeDataCore";
import type { SlotOffer } from "./engineClient";

export const DEFAULT_TEST_CONFIG = { count: 20, seed: 1, policy: "earliest", weights: [1, 1, 1, 1] } as const;
export interface TestConfig { count: number; seed: number; policy: "earliest" | "first" | "random"; weights: number[] }
export function validateTestConfig(value: unknown): TestConfig {
  const input = value as TestConfig | null;
  if (!input || !Number.isInteger(input.count) || input.count < 1 || input.count > 100 ||
      !Number.isInteger(input.seed) || input.seed < 0 || input.seed > 0xffffffff ||
      !["earliest", "first", "random"].includes(input.policy) || !Array.isArray(input.weights) ||
      input.weights.length !== FAKE_SERVICE_CODES.length ||
      input.weights.some(w => typeof w !== "number" || !Number.isFinite(w) || w < 0 || w > 100) ||
      !input.weights.some(w => w > 0)) throw new Error("Use 1-100 requests, a uint32 seed, a selection policy, and four weights from 0-100 with at least one positive weight.");
  return { count: input.count, seed: input.seed, policy: input.policy, weights: [...input.weights] };
}
export function bookingHorizon(now = new Date()): string[] {
  const days: string[] = [];
  let day = calendarDateInTz(now, OMAHA_TIMEZONE);
  while (days.length < 10) {
    day = addCalendarDays(day, 1);
    if (isWeekday(day)) days.push(day);
  }
  return days;
}
export function generateTestInputs(config: TestConfig) {
  const random = createSeededRandom(config.seed);
  const total = config.weights.reduce((a, b) => a + b, 0);
  return Array.from({ length: config.count }, (_, ordinal) => {
    let weight = random() * total;
    const serviceIndex = config.weights.findIndex(w => { weight -= w; return weight < 0; });
    const location = OMAHA_FAKE_LOCATIONS[Math.floor(random() * OMAHA_FAKE_LOCATIONS.length)]!;
    return { ordinal, serviceCode: FAKE_SERVICE_CODES[serviceIndex]!, location, selectionUnit: random() };
  });
}
export function chooseTestOffer(offers: SlotOffer[], policy: TestConfig["policy"], unit: number): SlotOffer | null {
  if (!offers.length) return null;
  if (policy === "first") return offers[0]!;
  if (policy === "random") return offers[Math.floor(unit * offers.length)]!;
  return [...offers].sort((a, b) => a.windowStart.localeCompare(b.windowStart) || a.windowEnd.localeCompare(b.windowEnd))[0]!;
}
