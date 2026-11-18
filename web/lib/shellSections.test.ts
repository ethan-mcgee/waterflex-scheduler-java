import { test } from "node:test";
import assert from "node:assert/strict";
import { isStandaloneSection } from "./shellSections";

test("the work orders section and its pages draw their own frame", () => {
  for (const path of ["/work-orders", "/work-orders/schedule", "/work-orders/recurring"]) assert.equal(isStandaloneSection(path), true, path);
});

test("every classic page keeps the classic shell, including look-alike paths", () => {
  for (const path of ["/", "/schedule", "/dispatch", "/book", "/technicians", "/work-ordersX", "/work-orders-old", "/dispatch/work-orders"])
    assert.equal(isStandaloneSection(path), false, path);
});

test("an unknown path keeps the classic shell", () => {
  assert.equal(isStandaloneSection(null), false);
});
