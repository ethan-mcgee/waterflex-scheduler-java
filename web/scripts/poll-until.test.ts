import assert from "node:assert/strict";
import { test } from "node:test";
import { pollUntil } from "./poll-until";

test("polling returns the observed terminal state", async () => {
  let reads = 0;
  assert.equal(await pollUntil("repair", async () => ++reads, value => value === 2, 1000, 1), 2);
});

test("deadline failures include the last observed state", async () => {
  await assert.rejects(pollUntil("repair", async () => ({ status: "ANALYZING" }), () => false, 0),
    /repair timed out.*last state:.*ANALYZING/);
});

test("read failures propagate instead of becoming fabricated states", async () => {
  await assert.rejects(pollUntil("repair", async () => { throw new Error("Missing report"); }, () => false), /Missing report/);
});
