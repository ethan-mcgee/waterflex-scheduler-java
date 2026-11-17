import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { load } from "js-yaml";
import { z } from "zod";
import { routeInsertions } from "./insertionContract";

const leg = { seconds: 840, meters: 9100, bufferedMinutes: 19 };
const totals = { driveMinutes: 95, meters: 61000, paidMinutes: 470, costCents: 21300, bufferSeconds: 0 };
const start = { plannedStart: "2026-11-18T15:00:00Z", window: { start: "2026-11-18T15:00:00Z", end: "2026-11-18T17:00:00Z" }, timed: totals };
const gap = { beforeAppointmentId: "A", routable: true, toJob: leg, fromJob: leg, replaced: leg, addedSeconds: 780, addedMeters: 8400, addedBufferedMinutes: 18, starts: [start] };
const day = { technicianId: "t1", serviceDate: "2026-11-18", holdsAsGiven: true, qualified: true, asGiven: totals, gaps: [gap] };
const answer = (days: unknown[]) => ({ routingIdentity: "omaha-2026-10", technicianDays: days, skippedTechnicianDays: [] });

test("the spec's example route insertion answer is read as the API sends it", () => {
  const spec = z.object({ components: z.object({ examples: z.object({ RouteInsertionResult: z.object({ value: z.unknown() }) }) }) })
    .parse(load(readFileSync(join("..", "docs", "api", "openapi-v1.yaml"), "utf8")));
  assert.equal(routeInsertions.safeParse(spec.components.examples.RouteInsertionResult.value).success, true);
});

test("a gap carries numbers only when it has a road route, and never a zero in place of a missing leg", () => {
  assert.equal(routeInsertions.safeParse(answer([day])).success, true);
  assert.equal(routeInsertions.safeParse(answer([{ ...day, gaps: [{ ...gap, addedSeconds: -120 }] }])).success, true, "a shorter route adds negative drive");
  const unroutable = { afterAppointmentId: "A", routable: false, starts: [] };
  assert.equal(routeInsertions.safeParse(answer([{ ...day, gaps: [unroutable] }])).success, true);
  assert.equal(routeInsertions.safeParse(answer([{ ...day, gaps: [{ ...unroutable, toJob: leg }] }])).success, false, "a leg without a route");
  assert.equal(routeInsertions.safeParse(answer([{ ...day, gaps: [{ ...unroutable, replaced: leg }] }])).success, false, "a replaced leg without a route");
  assert.equal(routeInsertions.safeParse(answer([{ ...day, gaps: [{ ...unroutable, starts: [start] }] }])).success, false, "a start without a route");
  assert.equal(routeInsertions.safeParse(answer([{ ...day, gaps: [{ ...gap, toJob: undefined }] }])).success, false, "a routable gap missing a leg");
  assert.equal(routeInsertions.safeParse(answer([{ ...day, gaps: [{ ...gap, toJob: null }] }])).success, false, "never null");
  const { replaced: _replaced, ...emptyDay } = gap;
  assert.equal(routeInsertions.safeParse(answer([{ ...day, gaps: [{ ...emptyDay, beforeAppointmentId: undefined }] }])).success, true, "an empty day replaces no leg");
});

test("a day that does not hold, or a technician not qualified, offers no start", () => {
  const broken = { ...day, holdsAsGiven: false, asGiven: undefined, gaps: [{ ...gap, starts: [] }] };
  assert.equal(routeInsertions.safeParse(answer([broken])).success, true);
  assert.equal(routeInsertions.safeParse(answer([{ ...broken, gaps: [gap] }])).success, false, "a start on a broken day");
  assert.equal(routeInsertions.safeParse(answer([{ ...broken, asGiven: totals }])).success, false, "totals for a day that does not hold");
  assert.equal(routeInsertions.safeParse(answer([{ ...day, asGiven: undefined }])).success, false, "a holding day without totals");
  assert.equal(routeInsertions.safeParse(answer([{ ...day, qualified: false }])).success, false, "a start for an unqualified technician");
  assert.equal(routeInsertions.safeParse(answer([{ ...day, gaps: [] }])).success, false, "every day has a gap");
});
