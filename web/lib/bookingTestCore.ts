import { testConfig, required } from "./contracts";
import { addCalendarDays, calendarDateInTz } from "./date";
import { createSeededRandom, FAKE_SERVICE_CODES, isWeekday, OMAHA_FAKE_LOCATIONS, OMAHA_TIMEZONE } from "./fakeDataCore";
import type { SlotOffer } from "./engineClient";

export const DEFAULT_TEST_CONFIG = { count: 20, seed: 1, policy: "earliest", weights: [1, 1, 1, 1] } as const;
export interface TestConfig { count: number; seed: number; policy: "earliest" | "first" | "random"; weights: number[] }
export function validateTestConfig(value: unknown): TestConfig {
  return testConfig.parse(value);
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
    const location = required(OMAHA_FAKE_LOCATIONS[Math.floor(random() * OMAHA_FAKE_LOCATIONS.length)], "Test location");
    return { ordinal, serviceCode: required(FAKE_SERVICE_CODES[serviceIndex], "Test service"), location, selectionUnit: random() };
  });
}
export function chooseTestOffer(offers: SlotOffer[], policy: TestConfig["policy"], unit: number): SlotOffer | null {
  if (!offers.length) return null;
  if (policy === "first") return required(offers[0]);
  if (policy === "random") return required(offers[Math.floor(unit * offers.length)], "Random offer");
  return required([...offers].sort((a, b) => a.windowStart.localeCompare(b.windowStart) || a.windowEnd.localeCompare(b.windowEnd))[0]);
}
