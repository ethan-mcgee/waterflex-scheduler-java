import test from "node:test";
import assert from "node:assert/strict";
import { NextRequest } from "next/server";
import { POST as confirm } from "../app/api/book/confirm/route";
import { POST as select } from "../app/api/book/select/route";
import { POST as preview } from "../app/api/dispatch/optimize/preview/route";
import { POST as book } from "../app/api/book/route";
import { POST as absence } from "../app/api/time-off/route";
import { POST as availability } from "../app/api/dispatch/availability/route";
import { POST as qualification } from "../app/api/dispatch/qualification/route";
import { EngineError, requestSlots } from "./engineClient";
import { availabilityRequest, readResponse, offersResponse, testInput, testAttempt, timeOffRequest, depotSetup, createTechnicianRequest, technicianDepotAssignment } from "./contracts";
import { addCalendarDays, mondayOfWeek, todayInTz } from "./date";
import { searchAddress } from "./geocode";
import { parseTimeOffReport, timeOffIntervalView } from "./timeOffView";
import { nearbyCandidate } from "./depotPin";

test("depot pin accepts nearby adjustment and rejects distant or missing geocoder candidates", () => {
  const candidate = { lat: 41.256, lng: -95.934, precision: "ROOFTOP" as const };
  assert.deepEqual(nearbyCandidate({ lat: 41.257, lng: -95.934 }, [candidate]), candidate);
  assert.equal(nearbyCandidate({ lat: 41.26, lng: -95.934 }, [candidate]), null);
  assert.equal(nearbyCandidate(candidate, []), null);
});

test("invalid request bodies fail before downstream requests or writes", async () => {
  const original = globalThis.fetch;
  globalThis.fetch = () => { throw new Error("Unexpected downstream request"); };
  try {
    for (const handler of [confirm, select, preview, book, absence, availability, qualification]) {
      for (const body of ["null", "[]", "false", '"text"', "{", "{}", '{"jobId":12,"holdId":false,"metroId":{},"date":"2026-02-30"}']) {
        const response = await handler(new NextRequest("http://localhost/api/test", { method: "POST", body }));
        assert.equal(response.status, 400, body);
      }
    }
  } finally { globalThis.fetch = original; }
});

test("depot ownership and technician assignment fields are required", () => {
  assert.equal(depotSetup.safeParse({ metroId: "metro", name: "Depot", departure: "HOME", returnTo: "HOME",
    address: { line1: "1 Main", city: "Lincoln", state: "NE", postalCode: "68501" }, confirmedPin: { lat: 40.8, lng: -96.7 } }).success, false);
  assert.equal(createTechnicianRequest.safeParse({ name: "Tech", metroId: "metro", dealershipId: "dealer" }).success, false);
  assert.equal(technicianDepotAssignment.safeParse({ depotId: "depot", effectiveDate: null }).success, false);
});

test("scheduler client preserves status and rejects malformed success and transport failures", async () => {
  const original = globalThis.fetch;
  try {
    for (const [body, status, expected] of [[null, 409, 409], [null, 200, 502], [{ jobId: "j", offers: null }, 200, 502]] as const) {
      globalThis.fetch = async () => Response.json(body, { status });
      await assert.rejects(requestSlots("j"), e => e instanceof EngineError && e.status === expected);
    }
    globalThis.fetch = async () => { throw new TypeError("offline"); };
    await assert.rejects(requestSlots("j"), e => e instanceof EngineError && e.status === 503);
  } finally { globalThis.fetch = original; }
});

test("geocoder rejects null, blank, nonfinite and out-of-range strings but retains valid zero", async () => {
  const original = globalThis.fetch;
  const address = { line1: "1 Main St", city: "Omaha", state: "NE", postalCode: "68102" };
  try {
    for (const lat of [null, "", " ", "abc", "Infinity", "91", "0x10", 0]) {
      globalThis.fetch = async () => Response.json([{ lat, lon: "0", address: { house_number: "1" } }]);
      assert.deepEqual(await searchAddress(address), []);
    }
    globalThis.fetch = async () => Response.json([{ lat: "0", lon: "0", address: { house_number: "1" } }]);
    assert.deepEqual(await searchAddress(address), [{ lat: 0, lng: 0, precision: "ROOFTOP" }]);
  } finally { globalThis.fetch = original; }
});

test("browser and journal decoders reject incomplete state while permitting unavailable shifts", async () => {
  assert.equal(availabilityRequest.safeParse({ technicianId: "t", date: "2026-09-22", available: false, shiftStartMin: null }).success, true);
  assert.equal(availabilityRequest.safeParse({ technicianId: "t", date: "2026-09-22", available: true }).success, false);
  assert.equal(testInput.safeParse({ location: null }).success, false);
  assert.equal(testAttempt.safeParse({ offers: null }).success, false);
  await assert.rejects(readResponse(Response.json({ offers: null }), offersResponse), /Invalid response/);
  await assert.rejects(readResponse(new Response(null, { status: 404 }), offersResponse), /Request failed \(404\)/);
  await assert.rejects(readResponse(new Response("not json", { status: 200 }), offersResponse), /Invalid response\. Reload before continuing\./);
  assert.deepEqual(await readResponse(Response.json({ jobId: "job-1", offers: [] }), offersResponse), { jobId: "job-1", offers: [] });
});

test("calendar utilities reject missing or invalid calendar values", () => {
  for (const day of ["", "2026-02-30", "not-a-date"]) {
    assert.throws(() => addCalendarDays(day, 1));
    assert.throws(() => mondayOfWeek(day));
  }
  assert.throws(() => addCalendarDays("2026-09-21", NaN));
  assert.equal(addCalendarDays("2026-09-30", 1), "2026-10-01");
  assert.equal(todayInTz("America/Chicago", new Date("2026-03-08T05:30:00Z")), "2026-03-07");
  assert.equal(todayInTz("America/Chicago", new Date("2026-03-08T07:30:00Z")), "2026-03-08");
});

test("time-off contracts reject invalid categories, intervals, and persisted reports", () => {
  const request = { technicianId: "tech", firstDate: "2026-10-01", lastDate: "2026-10-01", startMin: 480, endMin: 1020, reason: "Planned leave" };
  assert.equal(timeOffRequest.safeParse({ ...request, category: "Other" }).success, true);
  assert.equal(timeOffRequest.safeParse({ ...request, category: "Unvalidated" }).success, false);
  assert.equal(timeOffRequest.safeParse({ ...request, category: "Other", startMin: 1020, endMin: 480 }).success, false);
  assert.equal(timeOffIntervalView.safeParse({ date: "2026-10-01", startMin: 0, endMin: 0 }).success, false);
  assert.deepEqual(parseTimeOffReport(null), { kind: "missing" });
  assert.deepEqual(parseTimeOffReport({ days: null }), { kind: "malformed" });
  assert.deepEqual(parseTimeOffReport({ reason: "ROUTING_FAILURE" }), { kind: "failure", reason: "ROUTING_FAILURE" });
  const complete = parseTimeOffReport({ technician_id: "tech", days: [{ service_date: "2026-10-01", start_min: 480, end_min: 1020, status: "REPAIR_PREVIEW" }] });
  assert.equal(complete.kind, "complete");
});
