import { test } from "node:test";
import assert from "node:assert/strict";
import { connectionReply, statusOf } from "./apiConnectionCore";

test("only a verified token is connected; every failure keeps its reason", () => {
  assert.deepEqual(statusOf({ code: "NOT_CONNECTED", message: "Client default has no scheduling API token" }),
    { state: "NOT_CONNECTED", reason: "Client default has no scheduling API token" });
  assert.deepEqual(statusOf({ code: "TRANSPORT", message: "The scheduling service is unreachable" }),
    { state: "UNREACHABLE", reason: "The scheduling service is unreachable" });
  assert.equal(statusOf({ code: "MALFORMED", message: "unexpected" }).state, "UNREACHABLE");
  for (const code of ["BUSY", "INVALID_REQUEST", "NOT_FOUND", "anything"])
    assert.equal(statusOf({ code, message: "refused" }).state, "NOT_CONNECTED", `${code} is never shown as connected`);
});

test("a connection reply names its client and carries a reason exactly when not connected", () => {
  assert.ok(connectionReply.safeParse({ clientId: "default", state: "CONNECTED" }).success);
  assert.ok(connectionReply.safeParse({ clientId: "default", state: "NOT_CONNECTED", reason: "No token" }).success);
  assert.ok(!connectionReply.safeParse({ clientId: "default", state: "NOT_CONNECTED" }).success, "A failure needs its reason");
  assert.ok(!connectionReply.safeParse({ clientId: "default", state: "CONNECTED", reason: "x" }).success);
  assert.ok(!connectionReply.safeParse({ state: "CONNECTED" }).success, "The client is required");
  assert.ok(!connectionReply.safeParse(null).success);
});
