import test from "node:test";
import assert from "node:assert/strict";
import { currentRouteTiming } from "./currentRouteTiming";

const appointments = [{ id: "one", plannedStart: new Date("2026-10-26T14:10:00Z"), plannedEnd: new Date("2026-10-26T15:10:00Z") }];
const segment = { departure: "2026-10-26T14:00:00Z", returnedAt: "2026-10-26T15:20:00Z", appointmentIds: ["one"] };
const saved = { format: 1, scheduleVersion: 4, routingIdentity: "roads", segments: [segment] };
test("current timing requires matching versions, exact confirmed coverage and valid intervals", () => {
  assert.deepEqual(currentRouteTiming(saved, 4, appointments), { status: "AVAILABLE", segments: [segment] });
  assert.deepEqual(currentRouteTiming(null, 4, appointments), { status: "UNAVAILABLE" });
  assert.deepEqual(currentRouteTiming(saved, 5, appointments), { status: "UNAVAILABLE" });
  for (const raw of [undefined, false, [], {}, { ...saved, routingIdentity: null }, { ...saved, segments: [] },
    { ...saved, segments: [{ ...segment, appointmentIds: ["hold"] }] }, { ...saved, segments: [segment, segment] },
    { ...saved, segments: [{ ...segment, departure: "2026-10-26T14:20:00Z" }] },
    { ...saved, segments: [{ ...segment, returnedAt: "2026-10-26T15:00:00Z" }] }])
    assert.deepEqual(currentRouteTiming(raw, 4, appointments), { status: "INVALID" });
  assert.deepEqual(currentRouteTiming(saved, 4, [...appointments, ...appointments]), { status: "INVALID" });
  assert.deepEqual(currentRouteTiming({ ...saved, routingIdentity: null, segments: [] }, 4, []), { status: "AVAILABLE", segments: [] });
});
