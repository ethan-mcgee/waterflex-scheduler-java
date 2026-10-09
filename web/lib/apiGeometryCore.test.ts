import { test } from "node:test";
import assert from "node:assert/strict";
import { dispatchGeometryOf } from "./apiGeometryCore";
import { isDispatchGeometry } from "./dispatchGeometry";
import type { PublicSnapshot } from "./publicApi";
import type { RouteGeometry } from "./schedulerApi";

const DATE = "2026-10-14";
const location = (lat: number) => ({ lat, lng: -95.9 });
const window = { start: `${DATE}T14:00:00.000Z`, end: `${DATE}T18:00:00.000Z` };
const technicianDay = (technicianId: string) => ({ technicianId, serviceDate: DATE, lastModified: "2026-10-01T00:00:00.000Z", shift: { start: `${DATE}T13:00:00.000Z`, end: `${DATE}T22:00:00.000Z` },
  absences: [], start: location(41.1), end: location(41.2), maxPaidMinutes: 540 });
const appointment = (id: string, technicianId: string, sequence: number, plannedStart: string) => ({ id, technicianId, serviceDate: DATE, serviceId: "svc", durationMinutes: 45,
  window, location: { address: { line1: "1 Main St", city: "Omaha", state: "NE", postalCode: "68102" } }, sequence, plannedStart });
const snapshot: PublicSnapshot = { metroId: "omaha", timeZone: "America/Chicago",
  rates: { regularHourly: "20", overtimeHourly: "30", mileagePerMile: "0.5", travelBufferPct: "0", travelBufferMinutes: 0 }, policy: { fairnessBudget: "0.02" },
  technicians: [{ id: "a", qualifications: [] }, { id: "b", qualifications: [] }, { id: "c", qualifications: [] }],
  technicianDays: [technicianDay("a"), technicianDay("b"), technicianDay("c")],
  appointments: [appointment("x", "a", 0, `${DATE}T14:10:00.000Z`), appointment("y", "a", 1, `${DATE}T16:10:00.000Z`), appointment("z", "c", 0, `${DATE}T15:00:00.000Z`)] };
const leg = (segment: number, legIndex: number) => ({ segment, legIndex, seconds: 600, meters: 3000, coordinates: [[-95.9, 41.1], [-95.8, 41.3]] as Array<[number, number]> });
const drawn = (overrides: Partial<RouteGeometry> = {}): RouteGeometry => ({ routingIdentity: "omaha-map-v7", skippedTechnicianDays: [{ technicianId: "c", serviceDate: DATE, reason: "LOCATION_UNRESOLVED", message: "No match" }],
  routes: [{ technicianId: "a", serviceDate: DATE, stops: [{ appointmentId: "x", sequence: 0, lat: 41.3, lng: -95.8 }, { appointmentId: "y", sequence: 1, lat: 41.4, lng: -95.7 }],
    legs: [leg(0, 0), leg(0, 1), leg(1, 0), leg(1, 1)] }, { technicianId: "b", serviceDate: DATE, stops: [], legs: [] }], ...overrides });

test("the scheduler's drawing becomes the board's road features, stop markers and route endpoints", () => {
  const geometry = dispatchGeometryOf(DATE, snapshot, drawn());
  assert.ok(isDispatchGeometry(geometry, DATE, "current"), "The board accepts it");
  assert.deepEqual(geometry.features.map(feature => feature.properties.interval), ["a:0", "a:0", "a:1", "a:1"], "Each absence starts a new interval");
  assert.deepEqual(geometry.stops.map(stop => [stop.id, stop.plannedStart, stop.lat]), [["x", `${DATE}T14:10:00.000Z`, 41.3], ["y", `${DATE}T16:10:00.000Z`, 41.4]],
    "A stop sent by address is placed where the scheduler located it, at its planned start");
  assert.deepEqual(geometry.endpoints?.map(endpoint => endpoint.technicianId), ["a", "b"], "A skipped technician-day is not drawn");
  const empty = dispatchGeometryOf(DATE, snapshot, drawn({ routes: [] }));
  assert.ok(isDispatchGeometry(empty, DATE, "current"));
});

test("a drawing that does not match the snapshot is an error, never a partial map", () => {
  const route = drawn().routes[0];
  assert.ok(route !== undefined);
  assert.throws(() => dispatchGeometryOf(DATE, snapshot, drawn({ routes: [{ ...route, stops: route.stops.slice(0, 1) }] })), /different stops/);
  assert.throws(() => dispatchGeometryOf(DATE, snapshot, drawn({ routes: [{ ...route, technicianId: "d" }] })), /does not work/);
  assert.throws(() => dispatchGeometryOf(DATE, snapshot, drawn({ routes: [{ ...route, serviceDate: "2026-10-15" }] })), /not 2026-10-14/);
  const unlocated = { ...snapshot, technicianDays: [{ ...technicianDay("a"), start: { address: { line1: "1 Main St", city: "Omaha", state: "NE", postalCode: "68102" } } }] };
  assert.throws(() => dispatchGeometryOf(DATE, unlocated, drawn({ routes: [route] })), /no coordinates/);
});
