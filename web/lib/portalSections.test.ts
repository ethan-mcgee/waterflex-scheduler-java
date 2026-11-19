import { test } from "node:test";
import assert from "node:assert/strict";
import { locate, PORTAL_SECTIONS, sectionHome, visibleSections } from "./portalSections";

const all = visibleSections(true);
const where = (path: string | null) => { const found = locate(path, all); return [found.section.key, found.link?.href ?? null]; };

test("every page belongs to the section whose link matches the most of its path", () => {
  assert.deepEqual(where("/work-orders/schedule"), ["work-orders", "/work-orders/schedule"]);
  assert.deepEqual(where("/work-orders/schedule/anything"), ["work-orders", "/work-orders/schedule"]);
  assert.deepEqual(where("/work-orders/new"), ["work-orders", "/work-orders/new"]);
  assert.deepEqual(where("/dispatch"), ["dispatch", "/dispatch"]);
  assert.deepEqual(where("/work-orders/follow-up"), ["work-orders", "/work-orders/follow-up"]);
  assert.deepEqual(where("/dispatch/testing"), ["testing", "/dispatch/testing"]);
  assert.deepEqual(where("/technicians"), ["technicians", "/technicians"]);
  assert.deepEqual(where("/technicians/time-off"), ["technicians", "/technicians/time-off"]);
  assert.deepEqual(where("/branches"), ["branches", "/branches"]);
  assert.deepEqual(where("/settings/solver"), ["settings", "/settings/solver"]);
  assert.deepEqual(where("/settings/reason-codes"), ["settings", "/settings/reason-codes"]);
  assert.deepEqual(where("/settings/accounts"), ["settings", "/settings/accounts"]);
});

test("look-alike paths do not match a link, and unknown paths fall back to work orders with no link marked", () => {
  assert.deepEqual(where("/work-orders/schedules"), ["work-orders", null]);
  assert.deepEqual(where("/"), ["work-orders", null]);
  assert.deepEqual(where(null), ["work-orders", null]);
  assert.deepEqual(where("/dispatch/proposals/123"), ["dispatch", "/dispatch"]);
});

test("the booking test section shows only where booking tests are enabled; without it, its page falls to dispatch", () => {
  assert.equal(visibleSections(false).some(section => section.key === "testing"), false);
  assert.equal(visibleSections(true).some(section => section.key === "testing"), true);
  const found = locate("/dispatch/testing", visibleSections(false));
  assert.deepEqual([found.section.key, found.link?.href], ["dispatch", "/dispatch"]);
});

test("each section's rail icon leads to its first page, and every link is unique", () => {
  assert.equal(sectionHome(locate("/", all).section), "/work-orders/schedule");
  assert.equal(sectionHome(locate("/technicians/time-off", all).section), "/technicians");
  const hrefs = PORTAL_SECTIONS.flatMap(section => section.links.map(link => link.href));
  assert.equal(new Set(hrefs).size, hrefs.length);
});
