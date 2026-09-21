import { required } from "./contracts";
import assert from "node:assert/strict";
import test from "node:test";
import { isDispatchGeometry } from "./dispatchGeometry";

function payload() {
  return {
    type: "FeatureCollection", routingIdentity: "roads", serviceDate: "2026-09-22", phase: "current",
    features: [{ type: "Feature", properties: { technicianId: "tech", interval: "tech:0", legIndex: 0, seconds: 120, meters: 800 },
      geometry: { type: "LineString", coordinates: [[-95.93, 41.25], [-95.94, 41.26], [-95.95, 41.27]] } }],
    stops: [{ id: "visit", technicianId: "tech", sequence: 0, plannedStart: "2026-09-22T15:00:00Z", lat: 41.27, lng: -95.95 }],
  };
}

test("accepts road coordinates and empty schedules", () => {
  assert.ok(isDispatchGeometry(payload(), "2026-09-22", "current"));
  assert.ok(isDispatchGeometry({ ...payload(), features: [], stops: [] }, "2026-09-22", "current"));
});

test("rejects Java tree metadata and malformed coordinates before map rendering", () => {
  for (const geometry of [
    { nodeType: "OBJECT", object: true, array: false },
    { type: "Point", coordinates: [-95.93, 41.25] },
    ...[[], [[1, 2]], [[1, 2], [NaN, 3]], [[1, 2], [181, 3]], [[1, 2], [3, 91]], [[1, 2], ["3", 4]], [[1, 2], [3]]]
      .map((coordinates) => ({ type: "LineString", coordinates })),
  ]) {
    const data = payload();
    assert.equal(isDispatchGeometry({ ...data, features: [{ ...data.features[0], geometry }] }, "2026-09-22", "current"), false);
  }
});

test("rejects stale dates, phases, missing routes, and invalid stop times", () => {
  assert.equal(isDispatchGeometry(payload(), "2026-09-23", "current"), false);
  assert.equal(isDispatchGeometry(payload(), "2026-09-22", "after"), false);
  assert.equal(isDispatchGeometry({ ...payload(), features: [] }, "2026-09-22", "current"), false);
  const data = payload();
  required(data.stops[0]).plannedStart = "invalid";
  assert.equal(isDispatchGeometry(data, "2026-09-22", "current"), false);
});
