import { test } from "node:test";
import assert from "node:assert/strict";
import { retryAfterMs } from "./schedulerApi";

const reply = (status: number, retryAfter?: string) => ({ status, headers: new Headers(retryAfter === undefined ? {} : { "Retry-After": retryAfter }) });

test("a 429's Retry-After is read in whole seconds, and anything else is not a wait", () => {
  assert.equal(retryAfterMs(reply(429, "42")), 42_000);
  assert.equal(retryAfterMs(reply(429, "300")), 300_000);
  assert.equal(retryAfterMs(reply(429)), null, "no header");
  for (const value of ["0", "-1", "1.5", "soon", "Wed, 21 Oct 2026 07:28:00 GMT", ""])
    assert.equal(retryAfterMs(reply(429, value)), null, `not a positive whole number of seconds: ${value}`);
  assert.equal(retryAfterMs(reply(503, "5")), null, "only a 429 carries a wait");
});
