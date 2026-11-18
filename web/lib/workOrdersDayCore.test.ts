import { test } from "node:test";
import assert from "node:assert/strict";
import {
  calendarHours, cardMatches, clockLabel, durationLabel, hasFilters, hourLabel, initials, minuteOfDay, NO_FILTERS, pastelOf, placeLine, routeOf,
  TYPE_PASTELS, typePastels, waitingMatches, workOrderStatus, type WaitingWorkOrder, type WorkOrderCard, type WorkOrderTechnician,
} from "./workOrdersDayCore";

const card = (overrides: Partial<WorkOrderCard>): WorkOrderCard => ({
  appointmentId: "a1", jobId: "j1", technicianId: "t1", status: "SCHEDULED", jobStatus: "SCHEDULED", typeCode: "FILTER_SWAP", typeName: "Filter swap",
  customerName: "Ana Ruiz", street: "1 Main St", place: "Omaha, NE 68102", startMin: 540, endMin: 600,
  windowStart: "2026-10-07T14:00:00.000Z", windowEnd: "2026-10-07T16:00:00.000Z", plannedStart: "2026-10-07T14:00:00.000Z", plannedEnd: "2026-10-07T15:00:00.000Z",
  sequence: 0, durationMin: 60, lat: 41.2, lng: -95.9, geocodePrecision: "ROOFTOP", pinConfirmedAt: null, ...overrides,
});
const technician = (shift: WorkOrderTechnician["shift"]): WorkOrderTechnician =>
  ({ id: "t1", name: "Alex Rivera", color: "#dc2626", initials: "AR", homeLat: 41.3, homeLng: -95.9, shift, usualShift: { startMin: 480, endMin: 1020 } });
const waiting: WaitingWorkOrder = { jobId: "j9", typeCode: "REPAIR", typeName: "Repair", customerName: "Bo Li", place: "Bellevue, NE 68005", durationMin: 45, located: true };

test("types get the design's pastels in code order, whatever order they load in", () => {
  const pastels = typePastels(["REPAIR", "FILTER_SWAP"]);
  assert.equal(pastelOf(pastels, "FILTER_SWAP"), TYPE_PASTELS[0]);
  assert.equal(pastelOf(pastels, "REPAIR"), TYPE_PASTELS[1]);
  assert.deepEqual(typePastels(["FILTER_SWAP", "REPAIR"]), pastels);
});

test("past the palette the pastels repeat; duplicate and unknown types are refused, never colored by default", () => {
  const codes = Array.from({ length: TYPE_PASTELS.length + 1 }, (_, index) => `T${String(index).padStart(2, "0")}`);
  const pastels = typePastels(codes);
  assert.equal(pastelOf(pastels, `T${TYPE_PASTELS.length}`), pastelOf(pastels, "T00"));
  assert.throws(() => typePastels(["A", "A"]), /unique/);
  assert.throws(() => pastelOf(pastels, "MISSING"), /has no color/);
});

test("only statuses the portal keeps become work order statuses", () => {
  assert.equal(workOrderStatus("SCHEDULED"), "SCHEDULED");
  assert.equal(workOrderStatus("COMPLETED"), "COMPLETED");
  assert.equal(workOrderStatus("PENDING"), null);
  assert.equal(workOrderStatus("CANCELLED"), null);
});

test("places, initials and labels read as the design writes them", () => {
  assert.equal(placeLine("Omaha", "NE", "68102"), "Omaha, NE 68102");
  assert.equal(placeLine("Omaha", "NE", null), "Omaha, NE");
  assert.equal(initials("Ray Duckel"), "RD");
  assert.equal(initials("Lee"), "L");
  assert.equal(initials("Mary Ann Smith"), "MS");
  assert.equal(clockLabel(540), "9:00");
  assert.equal(clockLabel(750), "12:30");
  assert.equal(clockLabel(810), "1:30");
  assert.deepEqual(hourLabel(8, true), { hour: "8", suffix: "AM" });
  assert.deepEqual(hourLabel(9, false), { hour: "9", suffix: "00" });
  assert.deepEqual(hourLabel(12, false), { hour: "12", suffix: "PM" });
  assert.deepEqual(hourLabel(13, false), { hour: "1", suffix: "00" });
  assert.equal(durationLabel(45), "45m");
  assert.equal(durationLabel(120), "2h");
  assert.equal(durationLabel(375), "6h 15m");
});

test("local minutes follow the metro's time zone, including across daylight saving", () => {
  assert.equal(minuteOfDay("2026-10-07T14:15:00.000Z", "America/Chicago"), 9 * 60 + 15);
  assert.equal(minuteOfDay("2026-12-07T14:15:00.000Z", "America/Chicago"), 8 * 60 + 15);
  assert.equal(minuteOfDay("2026-10-07T05:00:00.000Z", "America/Chicago"), 0);
});

test("the calendar spans every shift and visit, and shows 8 AM to 5 PM on an empty day", () => {
  assert.deepEqual(calendarHours([], []), { start: 8, end: 17 });
  assert.deepEqual(calendarHours([technician({ startMin: 480, endMin: 1020 })], []), { start: 8, end: 17 });
  assert.deepEqual(calendarHours([technician({ startMin: 480, endMin: 1020 })], [card({ startMin: 450, endMin: 1090 })]), { start: 7, end: 19 });
  assert.deepEqual(calendarHours([technician(null)], [card({ startMin: 600, endMin: 645 })]), { start: 10, end: 11 });
});

test("filters match type and status exactly and the place as typed text", () => {
  assert.equal(hasFilters(NO_FILTERS), false);
  assert.equal(hasFilters({ ...NO_FILTERS, place: "  " }), false);
  assert.equal(cardMatches(card({}), NO_FILTERS), true);
  assert.equal(cardMatches(card({}), { ...NO_FILTERS, type: "REPAIR" }), false);
  assert.equal(cardMatches(card({}), { ...NO_FILTERS, status: "COMPLETED" }), false);
  assert.equal(cardMatches(card({ status: null }), { ...NO_FILTERS, status: "SCHEDULED" }), false);
  assert.equal(cardMatches(card({}), { ...NO_FILTERS, place: "68102" }), true);
  assert.equal(cardMatches(card({}), { ...NO_FILTERS, place: "omaha" }), true);
  assert.equal(cardMatches(card({}), { ...NO_FILTERS, place: "Lincoln" }), false);
});

test("waiting work orders have no status, so a status filter hides them", () => {
  assert.equal(waitingMatches(waiting, NO_FILTERS), true);
  assert.equal(waitingMatches(waiting, { ...NO_FILTERS, status: "SCHEDULED" }), false);
  assert.equal(waitingMatches(waiting, { ...NO_FILTERS, type: "REPAIR", place: "bellevue" }), true);
});

test("a route lists one technician's stops in order and spans first start to last end", () => {
  const cards = [card({ appointmentId: "b", sequence: 1, startMin: 700, endMin: 760 }), card({ appointmentId: "a", sequence: 0 }),
    card({ appointmentId: "c", technicianId: "t2", sequence: 0 })];
  const route = routeOf("t1", cards);
  assert.deepEqual(route.stops.map(stop => stop.appointmentId), ["a", "b"]);
  assert.equal(route.startMin, 540);
  assert.equal(route.endMin, 760);
  assert.deepEqual(routeOf("nobody", cards), { stops: [], startMin: null, endMin: null });
});
