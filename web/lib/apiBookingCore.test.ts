import { test } from "node:test";
import assert from "node:assert/strict";
import { bookingHorizon, checkReceipt, instantMicros, offersView, sameInstant } from "./apiBookingCore";
import { offersResponse, required } from "./contracts";

test("the booking horizon runs from tomorrow through the last counted weekday, weekends between included", () => {
  assert.deepEqual(bookingHorizon("2026-10-09", 2), ["2026-10-10", "2026-10-11", "2026-10-12", "2026-10-13"], "Friday: the weekend, then two weekdays");
  assert.deepEqual(bookingHorizon("2026-10-11", 1), ["2026-10-12"]);
  assert.equal(bookingHorizon("2026-10-11", 10).length, 12, "Ten weekdays from a Monday span twelve dates");
  assert.ok(bookingHorizon("2026-10-09", 15).length <= 21, "The longest horizon stays within the API's 21 dates");
  for (const weekdays of [0, 16, 2.5]) assert.throws(() => bookingHorizon("2026-10-09", weekdays));
});

test("instants compare exactly to the microsecond, however they are written", () => {
  assert.ok(sameInstant("2026-10-11T21:04:17Z", "2026-10-11T21:04:17.000000Z"));
  assert.ok(sameInstant("2026-10-11T21:04:17.1234Z", "2026-10-11T21:04:17.123400Z"));
  assert.ok(sameInstant("2026-10-11T16:04:17.5-05:00", "2026-10-11T21:04:17.500000Z"));
  assert.ok(!sameInstant("2026-10-11T21:04:17.123456Z", "2026-10-11T21:04:17.123457Z"));
  assert.equal(instantMicros("1970-01-01T00:00:01.000001Z"), 1_000_001n);
  assert.throws(() => instantMicros("2026-10-11T21:04:17.1234567Z"), "Finer than a microsecond cannot match the database");
  assert.throws(() => instantMicros("2026-10-11 21:04:17Z"));
});

const receipt = {
  receiptId: "rcpt-1",
  assignments: [
    { appointmentId: "job-1", technicianId: "tech-a", serviceDate: "2026-10-12", sequence: 1, plannedStart: "2026-10-12T15:00:00Z", plannedEnd: "2026-10-12T16:00:00Z" },
    { appointmentId: "appt-2", technicianId: "tech-a", serviceDate: "2026-10-12", sequence: 0, plannedStart: "2026-10-12T14:00:00Z", plannedEnd: "2026-10-12T14:45:00Z" },
  ],
  technicianDays: [
    { technicianId: "tech-b", serviceDate: "2026-10-12", lastModified: "2026-10-08T12:00:00Z" },
    { technicianId: "tech-a", serviceDate: "2026-10-12", lastModified: "2026-10-08T12:00:00.123456Z" },
  ],
};

test("a receipt is checked before anything is written", () => {
  assert.deepEqual(checkReceipt(receipt, "job-1", "2026-10-12").map(day => day.technicianId), ["tech-a", "tech-b"], "Sorted in lock order");
  assert.throws(() => checkReceipt(receipt, "job-9", "2026-10-12"), /does not assign the booked job/);
  assert.throws(() => checkReceipt(receipt, "job-1", "2026-10-13"), /booking is on/);
  assert.throws(() => checkReceipt({ ...receipt, technicianDays: [...receipt.technicianDays, { technicianId: "tech-a", serviceDate: "2026-10-12", lastModified: "2026-10-08T12:00:00Z" }] }, "job-1", "2026-10-12"), /twice/);
  assert.throws(() => checkReceipt({ ...receipt, technicianDays: receipt.technicianDays.slice(0, 1) }, "job-1", "2026-10-12"), /does not list/);
  const [booked, moved] = [required(receipt.assignments[0]), required(receipt.assignments[1])];
  assert.throws(() => checkReceipt({ ...receipt, assignments: [booked, moved, moved] }, "job-1", "2026-10-12"), /twice/);
  assert.throws(() => checkReceipt({ ...receipt, assignments: [{ ...booked, plannedEnd: "2026-10-12T15:00:00Z" }, moved] }, "job-1", "2026-10-12"), /ends before/);
});

test("offer sets become the booking page's search results", () => {
  const set = { offerSetId: "set-1", expiresAt: "2026-10-12T15:10:00-05:00", searchComplete: true, skippedTechnicianDays: [],
    offers: [{ offerId: "offer-1", serviceDate: "2026-10-12", window: { start: "2026-10-12T10:00:00-05:00", end: "2026-10-12T12:00:00-05:00" } }] };
  const available = offersResponse.parse(offersView("job-1", set, 40));
  assert.equal(available.search.outcome, "AVAILABLE");
  assert.deepEqual(available.offers[0], { offerId: "offer-1", date: "2026-10-12", windowStart: "2026-10-12T15:00:00.000Z",
    windowEnd: "2026-10-12T17:00:00.000Z", expiresAt: "2026-10-12T20:10:00.000Z" });
  assert.equal(offersResponse.parse(offersView("job-1", { ...set, offers: [] }, 40)).search.outcome, "NO_CANDIDATE_FOUND");
  const incomplete = offersResponse.parse(offersView("job-1", { ...set, offers: [], searchComplete: false }, 40));
  assert.equal(incomplete.search.outcome, "SEARCH_INCOMPLETE", "An empty incomplete search does not claim there is no capacity");
  assert.equal(incomplete.search.retryable, true);
});
