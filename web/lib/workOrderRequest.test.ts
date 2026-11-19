import { test } from "node:test";
import assert from "node:assert/strict";
import { accountBookingRequest, bookingRequest, workOrderRequest } from "./contracts";
import { bookingFingerprint } from "./bookingIdentity";

const contact = { requestId: "work-order-request-1", firstName: "Ana", lastName: "Ruiz", email: "ana@example.invalid", phone: "8055550100" };
const typed = { ...contact, line1: "12 Main St", city: "Omaha", state: "NE", postalCode: "68102", serviceCode: "LEAK",
  confirmedPin: { lat: 41.25, lng: -95.93, manuallyConfirmed: true } };
const account = { ...contact, accountNumber: "1001", serviceCode: "LEAK" };

test("a work order names one to three distinct reason codes and may allot 1 to 720 minutes", () => {
  assert.equal(bookingRequest.safeParse({ ...typed, extraReasonCodes: ["SALT", "PRES"], durationMin: 90 }).success, true);
  for (const change of [{ extraReasonCodes: ["SALT", "PRES", "SOFT"] }, { extraReasonCodes: ["LEAK"] }, { extraReasonCodes: ["SALT", "SALT"] },
    { durationMin: 0 }, { durationMin: 721 }, { durationMin: 60.5 }, { workOrderType: "" }, { technicianMessage: "x".repeat(1001) }])
    assert.equal(bookingRequest.safeParse({ ...typed, ...change }).success, false, JSON.stringify(change));
});

test("an account work order carries no address or pin, and a typed one cannot name an account", () => {
  const parsed = workOrderRequest.parse({ ...account, extraReasonCodes: ["SALT"], callFirst: true });
  assert.equal(parsed.accountNumber, "1001");
  assert.equal(accountBookingRequest.safeParse({ ...account, line1: "12 Main St" }).success, false);
  assert.equal(accountBookingRequest.safeParse({ ...account, confirmedPin: typed.confirmedPin }).success, false);
  assert.equal(workOrderRequest.safeParse({ ...typed, accountNumber: "1001" }).success, false);
  assert.equal(workOrderRequest.parse(typed).accountNumber, undefined);
});

test("a retry with the same work order shares its key, and any change to what it asks for does not", () => {
  const base = bookingFingerprint(workOrderRequest.parse(account));
  assert.equal(bookingFingerprint(workOrderRequest.parse({ ...account, requestId: "another-request-id-2" })), base);
  for (const change of [{ accountNumber: "1002" }, { extraReasonCodes: ["SALT"] }, { durationMin: 90 }, { workOrderType: "Repair" },
    { purchaseOrder: "PO-7" }, { technicianMessage: "Gate code 1234" }, { customerRequest: "Mornings" }, { callFirst: false }, { followUp: true }])
    assert.notEqual(bookingFingerprint(workOrderRequest.parse({ ...account, ...change })), base, JSON.stringify(change));
  // The same codes in another order are another work order: the first code is the one qualifications match.
  const ordered = bookingFingerprint(workOrderRequest.parse({ ...account, extraReasonCodes: ["SALT"] }));
  assert.notEqual(bookingFingerprint(workOrderRequest.parse({ ...account, serviceCode: "SALT", extraReasonCodes: ["LEAK"] })), ordered);
  // An account and a typed address are never the same request.
  assert.notEqual(bookingFingerprint(workOrderRequest.parse(typed)), base);
});
