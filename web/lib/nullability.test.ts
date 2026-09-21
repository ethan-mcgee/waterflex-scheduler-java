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
import { availabilityRequest, readResponse, offersResponse, testInput, testAttempt } from "./contracts";
import { addCalendarDays, mondayOfWeek } from "./date";
import { searchAddress } from "./geocode";

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
});

test("calendar utilities reject missing or invalid calendar values", () => {
  for (const day of ["", "2026-02-30", "not-a-date"]) {
    assert.throws(() => addCalendarDays(day, 1));
    assert.throws(() => mondayOfWeek(day));
  }
  assert.throws(() => addCalendarDays("2026-09-21", NaN));
  assert.equal(addCalendarDays("2026-09-30", 1), "2026-10-01");
});
