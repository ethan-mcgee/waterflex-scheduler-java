import { test } from "node:test";
import assert from "node:assert/strict";
import type { InsertionGap, InsertionTechnicianDay, RouteInsertions } from "./insertionContract";
import type { Original } from "./placementRules";
import { previewDrives, type Endpoints, type VisitDrive } from "./scheduleDrive";

const DATE = "2026-11-18";
const leg = (bufferedMinutes: number) => ({ seconds: bufferedMinutes * 50, meters: bufferedMinutes * 900, bufferedMinutes });
/** A gap with the leg into the job, the leg out of it, and the leg it replaces; `null` legs make it a gap with no road route. */
const gap = (after: string | undefined, before: string | undefined, legs: [number, number, number] | null): InsertionGap => ({
  ...(after === undefined ? {} : { afterAppointmentId: after }), ...(before === undefined ? {} : { beforeAppointmentId: before }),
  ...(legs === null ? { routable: false } : { routable: true, toJob: leg(legs[0]), fromJob: leg(legs[1]), replaced: leg(legs[2]),
    addedSeconds: 0, addedMeters: 0, addedBufferedMinutes: legs[0] + legs[1] - legs[2] }), starts: [] });
const day = (technicianId: string, gaps: InsertionGap[]): InsertionTechnicianDay => ({ technicianId, serviceDate: DATE, holdsAsGiven: true, qualified: true, gaps });

/** Route `a` is x, y, w from the depot to home; route `b` is v alone, a depot round trip. */
const visits = new Map<string, VisitDrive>([
  ["x", { before: { minutes: 14, from: "DEPOT" }, after: { minutes: 5, to: "NEXT" } }],
  ["y", { before: null, after: { minutes: 6, to: "NEXT" } }],
  ["w", { before: null, after: { minutes: 20, to: "HOME" } }],
  ["v", { before: { minutes: 10, from: "DEPOT" }, after: { minutes: 9, to: "DEPOT" } }],
]);
const endpoints: Endpoints = new Map([["a", { departure: "DEPOT", returnTo: "HOME" }], ["b", { departure: "DEPOT", returnTo: "DEPOT" }]]);
/** The answer for moving y, computed without it: on a, x now drives straight to w (11 minutes). */
const answer: RouteInsertions = { routingIdentity: "omaha", skippedTechnicianDays: [], technicianDays: [
  day("a", [gap(undefined, "x", [9, 13, 14]), gap("x", "w", [5, 7, 11]), gap("w", undefined, [8, 21, 20])]),
  day("b", [gap(undefined, "v", [7, 8, 10]), gap("v", undefined, [12, 13, 9])]),
] };
const movingY: Original = { appointmentId: "y", technicianId: "a", serviceDate: DATE, window: { start: `${DATE}T16:00:00Z`, end: `${DATE}T18:00:00Z` },
  plannedStart: `${DATE}T16:30:00Z`, afterAppointmentId: "x", beforeAppointmentId: "w" };

test("a moved visit leaves its place: the visit before it drives straight on to the visit after it", () => {
  const preview = previewDrives(visits, endpoints, answer, movingY, null);
  assert.equal(preview.visits.has("y"), false, "The moved visit is drawn without its old legs");
  assert.deepEqual(preview.visits.get("x")?.after, { minutes: 11, to: "NEXT" });
  assert.deepEqual(preview.visits.get("w"), visits.get("w"));
  assert.equal(preview.placed, null);
});

test("at its new place the visit before drives to it, and it drives on to the next stop or to the end point", () => {
  const afterV = previewDrives(visits, endpoints, answer, movingY, { technicianId: "b", serviceDate: DATE, afterAppointmentId: "v" });
  assert.deepEqual(afterV.visits.get("v"), { before: { minutes: 10, from: "DEPOT" }, after: { minutes: 12, to: "NEXT" } });
  assert.deepEqual(afterV.placed, { before: null, after: { minutes: 13, to: "DEPOT" } }, "b's day is a depot round trip");
  assert.deepEqual(afterV.visits.get("x")?.after, { minutes: 11, to: "NEXT" }, "The old place is closed at the same time");

  const beforeV = previewDrives(visits, endpoints, answer, movingY, { technicianId: "b", serviceDate: DATE, beforeAppointmentId: "v" });
  assert.deepEqual(beforeV.placed, { before: { minutes: 7, from: "DEPOT" }, after: { minutes: 8, to: "NEXT" } }, "First of the day, it drives from the depot");
  assert.deepEqual(beforeV.visits.get("v"), { before: null, after: { minutes: 9, to: "DEPOT" } }, "v is no longer first, so it no longer drives from the depot");
});

test("moving the first or last visit gives the start or end point's leg to its neighbor", () => {
  const firstAnswer: RouteInsertions = { ...answer, technicianDays: [day("a", [gap(undefined, "y", [9, 4, 16]), gap("y", "w", [6, 6, 6]), gap("w", undefined, [8, 21, 20])])] };
  const movingX: Original = { appointmentId: "x", technicianId: "a", serviceDate: DATE, window: movingY.window, plannedStart: movingY.plannedStart, beforeAppointmentId: "y" };
  assert.deepEqual(previewDrives(visits, endpoints, firstAnswer, movingX, null).visits.get("y")?.before, { minutes: 16, from: "DEPOT" });

  const lastAnswer: RouteInsertions = { ...answer, technicianDays: [day("a", [gap(undefined, "x", [9, 13, 14]), gap("x", "y", [5, 6, 5]), gap("y", undefined, [7, 20, 18])])] };
  const movingW: Original = { appointmentId: "w", technicianId: "a", serviceDate: DATE, window: movingY.window, plannedStart: movingY.plannedStart, afterAppointmentId: "y" };
  assert.deepEqual(previewDrives(visits, endpoints, lastAnswer, movingW, null).visits.get("y")?.after, { minutes: 18, to: "HOME" });
});

test("a leg the answer cannot give is not drawn: the neighbor goes without drive rather than keep a leg that is gone", () => {
  const unroutable: RouteInsertions = { ...answer, technicianDays: [day("a", [gap("x", "w", null)]), day("b", [gap("v", undefined, null)])] };
  const preview = previewDrives(visits, endpoints, unroutable, movingY, { technicianId: "b", serviceDate: DATE, afterAppointmentId: "v" });
  assert.equal(preview.visits.has("x"), false);
  assert.equal(preview.visits.has("v"), false);
  assert.equal(preview.placed, null);
  // A new job (no original) changes only its new place.
  const placing = previewDrives(visits, endpoints, answer, null, { technicianId: "a", serviceDate: DATE, afterAppointmentId: "x", beforeAppointmentId: "w" });
  assert.deepEqual(placing.visits.get("y"), visits.get("y"));
  assert.deepEqual(placing.visits.get("x")?.after, { minutes: 5, to: "NEXT" });
  assert.deepEqual(placing.placed, { before: null, after: { minutes: 7, to: "NEXT" } });
});
