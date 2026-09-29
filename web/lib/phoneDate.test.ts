import assert from "node:assert/strict";
import test from "node:test";
import { earliestMoveDate, formatUsDate, maskUsDate, parseUsDate } from "./date";
import { formatPhone, isValidPhone } from "./phone";
import { technicianProfileRequest } from "./contracts";

test("formatPhone formats as the number is typed", () => {
  assert.equal(formatPhone(""), "");
  assert.equal(formatPhone("5"), "(5");
  assert.equal(formatPhone("512"), "(512");
  assert.equal(formatPhone("5125"), "(512) 5");
  assert.equal(formatPhone("512555"), "(512) 555");
  assert.equal(formatPhone("5125550"), "(512) 555-0");
  assert.equal(formatPhone("5125550142"), "(512) 555-0142");
});

test("formatPhone accepts pasted country codes and keeps extra text", () => {
  assert.equal(formatPhone("+1 512-555-0142"), "(512) 555-0142");
  assert.equal(formatPhone("15125550142"), "(512) 555-0142");
  assert.equal(formatPhone("(512) 555-0142 x12"), "(512) 555-0142 x12");
  assert.equal(formatPhone("5125550142x12"), "(512) 555-0142x12");
  assert.equal(formatPhone("ext 12"), "ext 12");
});

test("formatPhone lets backspace remove the last digit", () => {
  assert.equal(formatPhone("(512) 5"), "(512) 5");
  assert.equal(formatPhone("(512) "), "(512");
  assert.equal(formatPhone("(512"), "(512");
});

test("isValidPhone requires seven digits", () => {
  assert.equal(isValidPhone("(512) 55"), false);
  assert.equal(isValidPhone("555-0100"), true);
});

test("maskUsDate inserts slashes and leaves other text alone", () => {
  assert.equal(maskUsDate("1"), "1");
  assert.equal(maskUsDate("10"), "10");
  assert.equal(maskUsDate("102"), "10/2");
  assert.equal(maskUsDate("1020"), "10/20");
  assert.equal(maskUsDate("10/202026"), "10/20/2026");
  assert.equal(maskUsDate("10/20/"), "10/20");
  assert.equal(maskUsDate("10/2x/2026"), "10/2x/2026");
  assert.equal(maskUsDate("123456789"), "123456789");
});

test("parseUsDate only accepts real calendar dates", () => {
  assert.equal(parseUsDate("10/20/2026"), "2026-10-20");
  assert.equal(parseUsDate("02/29/2028"), "2028-02-29");
  assert.equal(parseUsDate("02/29/2026"), null);
  assert.equal(parseUsDate("02/30/2026"), null);
  assert.equal(parseUsDate("13/01/2026"), null);
  assert.equal(parseUsDate("10/2x/2026"), null);
  assert.equal(parseUsDate("1/2/2026"), null);
  assert.equal(formatUsDate("2026-10-20"), "10/20/2026");
});

test("earliestMoveDate mirrors the 06:00 Chicago freeze", () => {
  assert.equal(earliestMoveDate(new Date("2026-10-20T10:59:00Z")), "2026-10-20");
  assert.equal(earliestMoveDate(new Date("2026-10-20T11:00:00Z")), "2026-10-21");
});

test("technician profile address fields are supplied together", () => {
  const base = { name: "Tech", color: "#2563eb" };
  const address = { line1: "1 Main St", city: "Omaha", state: "NE", postalCode: "68102" };
  assert.equal(technicianProfileRequest.safeParse(base).success, true);
  assert.equal(technicianProfileRequest.safeParse({ ...base, address, confirmedPin: { lat: 41, lng: -96 }, manuallyConfirmed: false }).success, true);
  assert.equal(technicianProfileRequest.safeParse({ ...base, address }).success, false);
  assert.equal(technicianProfileRequest.safeParse({ ...base, address: null, confirmedPin: null, manuallyConfirmed: null }).success, false);
  assert.equal(technicianProfileRequest.safeParse({ ...base, phone: "(512) 55" }).success, false);
  assert.equal(technicianProfileRequest.safeParse({ ...base, phone: "(512) 555-0142" }).success, true);
});

test("formatStoredPhone only reformats ten digit numbers", async () => {
  const { formatStoredPhone } = await import("./phone");
  assert.equal(formatStoredPhone("4025550100"), "(402) 555-0100");
  assert.equal(formatStoredPhone("402-555-0100"), "(402) 555-0100");
  assert.equal(formatStoredPhone("555-0100"), "555-0100");
  assert.equal(formatStoredPhone("402-555-0100 x5"), "402-555-0100 x5");
});
