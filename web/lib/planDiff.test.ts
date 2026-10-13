import { test } from "node:test";
import assert from "node:assert/strict";
import { changedAppointmentIds, changedRouteTechnicianIds, changeKind, formatDelta, formatDollars, formatDuration, formatMiles, totalsDelta,
  touchedTechnicianIds, type PlanChange } from "./planDiff";

const change = (appointmentId: string, from: string, to: string, fromSequence: number, toSequence: number): PlanChange =>
  ({ appointmentId, fromTechnicianId: from, toTechnicianId: to, fromSequence, toSequence });

test("a change is a reassignment before a resequence before a retime", () => {
  assert.equal(changeKind(change("x", "a", "b", 0, 2)), "Reassigned");
  assert.equal(changeKind(change("x", "a", "b", 1, 1)), "Reassigned");
  assert.equal(changeKind(change("x", "a", "a", 0, 2)), "Resequenced");
  assert.equal(changeKind(change("x", "a", "a", 1, 1)), "Retimed");
});

test("changed stops and routes come from the changes; a retime leaves the route's line alone", () => {
  const changes = [change("x", "a", "b", 0, 0), change("y", "c", "c", 0, 1), change("z", "d", "d", 2, 2)];
  assert.deepEqual([...changedAppointmentIds(changes)], ["x", "y", "z"]);
  assert.deepEqual([...changedRouteTechnicianIds(changes)].sort(), ["a", "b", "c"]);
  assert.deepEqual([...touchedTechnicianIds(changes)].sort(), ["a", "b", "c", "d"]);
});

test("a delta is null whenever either side is missing, never compared as zero", () => {
  const before = { seconds: 3600, meters: 10000, costCents: 50000 };
  assert.deepEqual(totalsDelta(before, { seconds: 3000, meters: 12000, costCents: 45000 }), { seconds: -600, meters: 2000, costCents: -5000 });
  assert.deepEqual(totalsDelta(before, { seconds: 3000, meters: 12000, costCents: null }), { seconds: -600, meters: 2000, costCents: null });
  assert.deepEqual(totalsDelta(null, before), { seconds: null, meters: null, costCents: null });
  assert.deepEqual(totalsDelta({ ...before, costCents: 0 }, { ...before, costCents: 0 }), { seconds: 0, meters: 0, costCents: 0 }, "A real zero cost stays a number");
});

test("tiles format drive time, miles and dollars, with signed deltas", () => {
  assert.equal(formatDuration(24120), "6h 42m");
  assert.equal(formatDuration(2520), "42 min");
  assert.equal(formatDuration(3600), "1h 00m");
  assert.equal(formatMiles(344400), "214 mi");
  assert.equal(formatMiles(8047), "5.0 mi");
  assert.equal(formatDollars(184612), "$1,846");
  assert.equal(formatDelta("seconds", -660), "−11 min");
  assert.equal(formatDelta("costCents", 5700), "+$57");
  assert.equal(formatDelta("meters", 10), "No change");
  assert.throws(() => formatDuration(-1));
  assert.throws(() => formatMiles(Number.NaN));
});
