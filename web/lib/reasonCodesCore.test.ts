import { test } from "node:test";
import assert from "node:assert/strict";
import { allotTime, reasonCodesLabel, suggestedMinutes } from "./reasonCodesCore";

test("the suggestion sums each code's minutes and buffer and rounds once; one code suggests what one service did", () => {
  assert.equal(suggestedMinutes([{ estDurationMin: 60, bufferPct: 0.15 }]), 69);
  assert.equal(suggestedMinutes([{ estDurationMin: 45, bufferPct: 0.1 }, { estDurationMin: 30, bufferPct: 0.1 }]), 83);
  // 0.5 + 0.5 rounds once to 1 minute more, not twice.
  assert.equal(suggestedMinutes([{ estDurationMin: 5, bufferPct: 0.1 }, { estDurationMin: 5, bufferPct: 0.1 }]), 11);
});

test("no codes, or a code without usable minutes, gives no suggestion rather than zero", () => {
  assert.equal(suggestedMinutes([]), null);
  assert.equal(suggestedMinutes([{ estDurationMin: 0, bufferPct: 0.15 }]), null);
  assert.equal(suggestedMinutes([{ estDurationMin: 30, bufferPct: Number.NaN }]), null);
  assert.equal(suggestedMinutes([{ estDurationMin: 30, bufferPct: -0.2 }]), null);
  assert.equal(suggestedMinutes([{ estDurationMin: 30.5, bufferPct: 0 }]), null);
});

test("the time allotted is the suggestion, or the dispatcher's time, which is marked overridden only when it differs", () => {
  assert.deepEqual(allotTime(69, undefined), { durationMin: 69, suggestedMin: 69, overridden: false });
  assert.deepEqual(allotTime(69, 90), { durationMin: 90, suggestedMin: 69, overridden: true });
  assert.deepEqual(allotTime(69, 69), { durationMin: 69, suggestedMin: 69, overridden: false });
});

test("a missing suggestion, a time out of range and a suggestion over a day are refused", () => {
  assert.deepEqual(allotTime(null, 60), { refusal: { status: 503, error: "The reason codes' minutes are unavailable." } });
  assert.equal("refusal" in allotTime(69, 0), true);
  assert.equal("refusal" in allotTime(69, 721), true);
  assert.equal("refusal" in allotTime(69, 30.5), true);
  const long = allotTime(800, undefined);
  assert.ok("refusal" in long && long.refusal.status === 422 && long.refusal.error.includes("800 minutes"));
  // The dispatcher can still allot a time the scheduler can plan.
  assert.deepEqual(allotTime(800, 480), { durationMin: 480, suggestedMin: 800, overridden: true });
});

test("a card names the first code and counts the rest", () => {
  assert.equal(reasonCodesLabel([]), null);
  assert.equal(reasonCodesLabel(["Leaking pipe at the tank"]), "Leaking pipe at the tank");
  assert.equal(reasonCodesLabel(["Leaking pipe at the tank", "Salt bridge", "Low pressure"]), "Leaking pipe at the tank +2 more");
});
