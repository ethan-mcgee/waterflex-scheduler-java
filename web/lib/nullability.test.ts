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
import { availabilityRequest, readResponse, offersResponse, testInput, testAttempt, timeOffRequest, depotSetup, createTechnicianRequest, technicianDepotAssignment, travelBreakdown } from "./contracts";
import { addCalendarDays, mondayOfWeek, todayInTz } from "./date";
import { searchAddress } from "./geocode";
import { parseTimeOffReport, timeOffIntervalView, additionalRepairOvertime } from "./timeOffView";
import { nearbyCandidate } from "./depotPin";

test("road travel and buffer reporting rejects missing or inconsistent components", () => {
  const valid = { road_seconds: 180, configured_buffer_seconds: 636, rounding_seconds: 84, modeled_travel_minutes: 15, leg_count: 2 };
  assert.deepEqual(travelBreakdown.parse(valid), valid);
  for (const value of [null, {}, { ...valid, road_seconds: null }, { ...valid, rounding_seconds: -1 },
    { ...valid, configured_buffer_seconds: Infinity }, { ...valid, modeled_travel_minutes: 14 }])
    assert.equal(travelBreakdown.safeParse(value).success, false);
});

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
      globalThis.fetch = async input => String(input).endsWith("/cancel-search") ? Response.json({ success: true }) : Response.json(body, { status });
      await assert.rejects(requestSlots("j"), e => e instanceof EngineError && e.status === expected);
    }
    globalThis.fetch = async input => { if (String(input).endsWith("/cancel-search")) return Response.json({ success: true }); throw new TypeError("offline"); };
    await assert.rejects(requestSlots("j"), e => e instanceof EngineError && e.status === 503);
  } finally { globalThis.fetch = original; }
});

test("geocoder rejects null, blank, nonfinite and out-of-range strings but retains valid zero", async () => {
  const original = globalThis.fetch;
  const address = { line1: "1 Main St", city: "Omaha", state: "NE", postalCode: "68102" };
  const detail = { road: "Main Street", city: "Omaha", postcode: "68102", country_code: "us", house_number: "1" };
  try {
    for (const lat of [null, "", " ", "abc", "Infinity", "91", "0x10", 0]) {
      globalThis.fetch = async () => Response.json([{ lat, lon: "0", address: detail }]);
      await assert.rejects(searchAddress(address));
    }
    globalThis.fetch = async () => Response.json([{ lat: "0", lon: "0", address: detail }]);
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
  const result = { jobId: "job-1", offers: [], search: { outcome: "SEARCH_INCOMPLETE", prescribedSearchCompleted: false, elapsedMs: 12, retryable: true } };
  assert.deepEqual(await readResponse(Response.json(result), offersResponse), result);
});

test("appointment outcomes reject missing or contradictory search evidence", () => {
  const result = { jobId: "job", offers: [], search: { outcome: "SEARCH_INCOMPLETE", prescribedSearchCompleted: false, elapsedMs: 12, retryable: true } };
  for (const search of [null, undefined, {}, { ...result.search, elapsedMs: null }, { ...result.search, outcome: "NO_CANDIDATE_FOUND" },
    { ...result.search, outcome: "AVAILABLE" }, { ...result.search, prescribedSearchCompleted: true }, { ...result.search, retryable: false }])
    assert.equal(offersResponse.safeParse({ ...result, search }).success, false);
  for (const outcome of ["ROUTING_UNAVAILABLE", "SERVICE_BUSY", "SCHEDULE_CONFLICT", "SEARCH_INCOMPLETE"])
    assert.equal(offersResponse.safeParse({ ...result, search: { ...result.search, outcome } }).success, true);
});

test("scheduler search timing is kept separate from client transport duration", async () => {
  const original = globalThis.fetch;
  try {
    globalThis.fetch = async () => Response.json({ jobId: "job", offers: [],
      search: { outcome: "ROUTING_UNAVAILABLE", prescribedSearchCompleted: false, elapsedMs: 11, retryable: true } });
    const result = await requestSlots("job");
    assert.equal(result.search.elapsedMs, 11);
    assert.equal(result.search.outcome, "ROUTING_UNAVAILABLE");
    assert.ok(result.search.apiElapsedMs !== undefined && result.search.apiElapsedMs >= 0);
  } finally { globalThis.fetch = original; }
});

test("selection conflicts preserve retryable refresh outcomes and transport failures", async () => {
  const original = globalThis.fetch;
  try {
    let calls = 0;
    globalThis.fetch = async () => ++calls === 1 ? Response.json({ detail: "Changed" }, { status: 409 })
      : Response.json({ jobId: "job", offers: [], search: { outcome: "SERVICE_BUSY", prescribedSearchCompleted: false, elapsedMs: 20, retryable: true } });
    const response = await select(new NextRequest("http://localhost/api/book/select", { method: "POST", body: JSON.stringify({ jobId: "job", offerId: "offer" }) }));
    assert.equal(response.status, 409);
    const result: unknown = await response.json();
    assert.equal(offersResponse.parse(result).search.outcome, "SERVICE_BUSY");
    calls = 0;
    globalThis.fetch = async input => {
      if (String(input).endsWith("/cancel-search")) return Response.json({ success: true });
      if (++calls === 1) return Response.json({ detail: "Changed" }, { status: 409 });
      throw new TypeError("offline");
    };
    const unavailable = await select(new NextRequest("http://localhost/api/book/select", { method: "POST", body: JSON.stringify({ jobId: "job", offerId: "offer" }) }));
    assert.equal(unavailable.status, 503);
    assert.deepEqual(await unavailable.json(), { error: "Scheduling service unavailable" });
  } finally { globalThis.fetch = original; }
});

test("an already abandoned search sends only durable cancellation with its own live signal", async () => {
  const original = globalThis.fetch;
  try {
    const calls: string[] = [];
    globalThis.fetch = async (input, init) => {
      calls.push(String(input));
      assert.equal(init?.signal?.aborted, false);
      const body: unknown = JSON.parse(String(init?.body));
      assert.ok(typeof body === "object" && body !== null && "searchRequestId" in body && typeof body.searchRequestId === "string");
      return Response.json({ success: true });
    };
    const controller = new AbortController(); controller.abort();
    await assert.rejects(requestSlots("job", false, 5000, controller.signal), /cancelled/);
    assert.equal(calls.length, 1); assert.ok(calls[0]?.endsWith("/cancel-search"));
  } finally { globalThis.fetch = original; }
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
  assert.equal(additionalRepairOvertime(complete), null, "Missing metrics must not become zero overtime");
});

test("repair approval sums increases by day without cancelling them against another day's reduction", () => {
  const metric = (overtime: number) => ({ route_minutes: 100, overtime_minutes: overtime, drive_minutes: 20,
    waiting_minutes: 0, distance_meters: 3000, modeled_cost_cents: 5000 });
  const report = parseTimeOffReport({ technician_id: "tech", days: [
    { service_date: "2026-10-01", start_min: 480, end_min: 1020, status: "REPAIR_PREVIEW", daily_before: metric(0), daily_after: metric(30) },
    { service_date: "2026-10-02", start_min: 480, end_min: 1020, status: "REPAIR_PREVIEW", daily_before: metric(60), daily_after: metric(0) },
    { service_date: "2026-10-03", start_min: 480, end_min: 1020, status: "NO_SHIFT" },
  ] });
  assert.equal(additionalRepairOvertime(report), 30);
});
