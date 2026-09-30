import assert from "node:assert/strict";
import { z } from "zod";
import { bookingHorizon } from "../lib/bookingTestCore";

export function experimentDates(saved: string | undefined, now = new Date()): string[] {
  const current = bookingHorizon(now);
  if (saved == null) return current;
  const dates = z.array(z.iso.date()).length(10).parse(JSON.parse(saved));
  assert.deepEqual(dates, current, "Frozen booking horizon expired; start a new comparison");
  return dates;
}

export function verifyExperimentSettings(configuration: Record<string, string> | null,
  expected: string | undefined): void {
  if (expected == null) return;
  const variant = z.enum(["INSERTION", "BOUNDED", "EXPANDED", "RUIN_RECREATE", "SHARED"]).parse(expected);
  assert.ok(configuration, "Experiment requires server statistics");
  assert.equal(configuration["booking.search.variant"], variant, "Actual search variant differs");
  assert.equal(configuration["booking.search.bounded"], "true", "Bounded feature gate must be enabled; INSERTION bypasses refinement explicitly");
  assert.equal(configuration["booking.reservations.enabled"], "true");
}
