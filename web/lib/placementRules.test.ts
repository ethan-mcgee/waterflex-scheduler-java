import { test } from "node:test";
import assert from "node:assert/strict";
import type { InsertionGap, InsertionStart, InsertionTechnicianDay, RouteInsertions, RouteTotals } from "./insertionContract";
import { bestOptions, comparisonSentence, previewTimes, rankedFits, recommendation, relativeToBest, spotAt, spotKey, verdict, whyNot, withTimedDays, type Original } from "./placementRules";

const DATE = "2026-11-18";
const totals = (driveMinutes: number, costCents: number, meters = driveMinutes * 1000, bufferSeconds = 0): RouteTotals =>
  ({ driveMinutes, meters, paidMinutes: 480, costCents, bufferSeconds });
const at = (hour: number, minute = 0) => new Date(Date.UTC(2026, 10, 18, hour, minute)).toISOString();
const start = (hour: number, minute: number, timed: RouteTotals, keepsWindow?: boolean): InsertionStart =>
  ({ plannedStart: at(hour, minute), window: { start: at(hour), end: at(hour + 2) }, timed, ...(keepsWindow === undefined ? {} : { keepsWindow }) });
const leg = { seconds: 600, meters: 6000, bufferedMinutes: 14 };
const gap = (after: string | undefined, before: string | undefined, added: number, starts: InsertionStart[]): InsertionGap =>
  ({ ...(after === undefined ? {} : { afterAppointmentId: after }), ...(before === undefined ? {} : { beforeAppointmentId: before }),
    routable: true, toJob: leg, fromJob: leg, replaced: leg, addedSeconds: added * 60, addedMeters: added * 900, addedBufferedMinutes: added, starts });
const day = (technicianId: string, asGiven: RouteTotals, gaps: InsertionGap[], change: Partial<InsertionTechnicianDay> = {}): InsertionTechnicianDay =>
  ({ technicianId, serviceDate: DATE, holdsAsGiven: true, qualified: true, asGiven, gaps, ...change });

/**
 * `a` had the moved appointment `m` between a1 and a2; the answer was computed without it. `b` has one stop, b1.
 * Moving `m` to the end of b's day keeps its 15:00 to 17:00 window at 15:30 and is cheaper; 18:00 changes the window.
 */
const answer: RouteInsertions = {
  routingIdentity: "omaha-2026-10",
  technicianDays: [
    day("a", totals(80, 20000), [gap(undefined, "a1", 25, []), gap("a1", "a2", 9, [start(15, 0, totals(95, 21500), true), start(15, 30, totals(96, 21700), true)]),
      gap("a2", undefined, 30, [])]),
    day("b", totals(60, 15000), [gap(undefined, "b1", 40, []), gap("b1", undefined, 5, [start(15, 30, totals(66, 15900), true), start(18, 0, totals(66, 16100), false)])]),
  ],
  skippedTechnicianDays: [{ technicianId: "c", serviceDate: DATE, reason: "LOCATION_UNRESOLVED", message: "Stop c1 has no location." }],
};
const original: Original = { appointmentId: "m", technicianId: "a", serviceDate: DATE, window: { start: at(15), end: at(17) },
  plannedStart: at(15, 10), afterAppointmentId: "a1", beforeAppointmentId: "a2" };
const endOfB = { technicianId: "b", serviceDate: DATE, afterAppointmentId: "b1" };

test("the best fits are the gaps with a start, least added drive first", () => {
  const fits = rankedFits(answer);
  assert.deepEqual(fits.map(fit => fit.key), [spotKey(endOfB), spotKey({ technicianId: "a", serviceDate: DATE, afterAppointmentId: "a1", beforeAppointmentId: "a2" })]);
  assert.equal(fits[0]?.addedBufferedMinutes, 5);
  assert.equal(fits[0]?.firstStart.plannedStart, at(15, 30));
  assert.equal(spotKey({ technicianId: "a", serviceDate: DATE }), "a|2026-11-18|start|end", "an empty day's only gap");
});

test("the recommendation is the best fit; a move is recommended only where it keeps its promise, from the first start that does", () => {
  assert.equal(recommendation(answer, false)?.key, spotKey(endOfB));
  assert.equal(recommendation(answer, true)?.firstStart.plannedStart, at(15, 30));
  // When the cheapest place offers only a changed window, the move is recommended to the cheapest place that keeps it.
  const changing: RouteInsertions = { ...answer, technicianDays: answer.technicianDays.map(item => item.technicianId !== "b" ? item
    : day("b", totals(60, 15000), [gap("b1", undefined, 5, [start(18, 0, totals(66, 16100), false)])])) };
  assert.equal(recommendation(changing, false)?.key, spotKey(endOfB));
  assert.equal(recommendation(changing, true)?.key, spotKey({ technicianId: "a", serviceDate: DATE, afterAppointmentId: "a1", beforeAppointmentId: "a2" }));
  const nothingKeeps: RouteInsertions = { ...answer, technicianDays: [day("b", totals(60, 15000), [gap("b1", undefined, 5, [start(18, 0, totals(66, 16100), false)])])] };
  assert.equal(recommendation(nothingKeeps, true), null);
  assert.equal(recommendation({ ...answer, technicianDays: [] }, false), null);
});

test("a gap's drive is said against the recommendation's", () => {
  const best = recommendation(answer, false);
  assert.ok(best !== null);
  assert.equal(relativeToBest(5, best), "Same as best");
  assert.equal(relativeToBest(9, best), "+4 min vs best");
  assert.equal(relativeToBest(2, best), "3 min less than best");
});

test("a drop that cannot hold says why, and never pretends a missing route is a short one", () => {
  assert.match(whyNot(answer, "c", DATE) ?? "", /could not be placed on the map: Stop c1 has no location/);
  assert.match(whyNot(answer, "z", DATE) ?? "", /not working that day/);
  const unqualified = { ...answer, technicianDays: [day("a", totals(80, 20000), [gap("a1", "a2", 9, [])], { qualified: false })] };
  assert.match(whyNot(unqualified, "a", DATE) ?? "", /not qualified/);
  const broken = { ...answer, technicianDays: [day("a", totals(80, 20000), [gap("a1", "a2", 9, [])], { holdsAsGiven: false, asGiven: undefined })] };
  assert.match(whyNot(broken, "a", DATE) ?? "", /already misses/);
  assert.match(whyNot(answer, "a", DATE, { afterAppointmentId: "a2", routable: false, starts: [] }) ?? "", /no road route/);
  assert.match(whyNot(answer, "a", DATE, gap("a2", undefined, 30, [])) ?? "", /No start here keeps/);
  assert.equal(whyNot(answer, "b", DATE), null, "a day with a start somewhere");
  const dropped = verdict(answer, original, { technicianId: "a", serviceDate: DATE, afterAppointmentId: "a2" }, null);
  assert.equal(dropped.kind, "CANNOT_HOLD");
});

test("a new job is a placement; a move inside its window keeps the promise and is compared with where it was", () => {
  assert.deepEqual(verdict({ ...answer }, null, endOfB, null), { kind: "NEW", start: start(15, 30, totals(66, 15900), true) });
  const kept = verdict(answer, original, endOfB, at(15, 30));
  assert.equal(kept.kind, "KEEPS_PROMISE");
  if (kept.kind !== "KEEPS_PROMISE") return;
  assert.equal(kept.samePlace, false);
  // Before: a with m at 15:00, the stored 15:10's nearest start, plus b as given. After: a as given plus b with m.
  assert.deepEqual(kept.comparison?.previous, { ...totals(155, 36500, 155000), paidMinutes: 960 }, "two days of 480 paid minutes");
  assert.deepEqual(kept.comparison?.next, { ...totals(146, 35900, 146000), paidMinutes: 960 });
  assert.equal(kept.comparison?.better, "NEW");
  assert.equal(kept.comparison?.sentence, "The new assignment is better: with the move, the routes drive 9 minutes less, cover 5.6 miles less and cost $6.00 less.");
  const home = verdict(answer, original, { technicianId: "a", serviceDate: DATE, afterAppointmentId: "a1", beforeAppointmentId: "a2" }, at(15, 0));
  assert.equal(home.kind === "KEEPS_PROMISE" && home.samePlace, true, "its own place");
  assert.equal(home.kind === "KEEPS_PROMISE" && home.comparison?.better, "SAME");
});

test("a move outside the promised window, or to another day, changes the promise and shows what it was", () => {
  const late = verdict(answer, original, endOfB, at(18, 0));
  assert.equal(late.kind, "CHANGES_PROMISE");
  if (late.kind !== "CHANGES_PROMISE") return;
  assert.deepEqual(late.previous, { serviceDate: DATE, window: { start: at(15), end: at(17) } });
  assert.equal(late.start.window.start, at(18));
  const nextDay = { ...answer, technicianDays: answer.technicianDays.map(item => item.technicianId === "b" ? { ...item, serviceDate: "2026-11-19" } : item) };
  assert.equal(verdict(nextDay, original, { ...endOfB, serviceDate: "2026-11-19" }, at(15, 30)).kind, "CHANGES_PROMISE",
    "another day changes the promise even when the scheduler kept the clock times");
  assert.equal(verdict(answer, original, endOfB, at(16, 0)).kind, "CANNOT_HOLD", "a start the answer does not offer");
});

test("the comparison names only differences that show in their unit, and cost decides before drive time", () => {
  assert.deepEqual(comparisonSentence(totals(60, 15000), totals(60, 15000, 60040)),
    { better: "SAME", sentence: "Both assignments cost the same: the routes drive, cost and use buffer the same either way." });
  assert.deepEqual(comparisonSentence(totals(60, 15000), totals(50, 15100, 60000, 600)),
    { better: "PREVIOUS", sentence: "The previous assignment was better: with the move, the routes drive 10 minutes less, cost $1.00 more and use 10 minutes more of late-arrival buffer." });
  assert.equal(comparisonSentence(totals(60, 15000), totals(61, 15000, 60000)).better, "PREVIOUS", "a cost tie goes to drive time");
});

test("a drop lands in the gap after every stop that starts before it, at the nearest start", () => {
  const minutes = new Map([["a1", 13 * 60], ["a2", 18 * 60], ["b1", 13 * 60]]);
  const stopMinute = (id: string) => minutes.get(id);
  const startMinute = (item: InsertionStart) => new Date(item.plannedStart).getUTCHours() * 60 + new Date(item.plannedStart).getUTCMinutes();
  assert.deepEqual(spotAt(answer, "a", DATE, 15 * 60 + 20, stopMinute, startMinute),
    { spot: { technicianId: "a", serviceDate: DATE, afterAppointmentId: "a1", beforeAppointmentId: "a2" }, plannedStart: at(15, 30) });
  assert.deepEqual(spotAt(answer, "a", DATE, 12 * 60, stopMinute, startMinute),
    { spot: { technicianId: "a", serviceDate: DATE, beforeAppointmentId: "a1" }, plannedStart: null }, "before the first stop, where nothing fits");
  assert.deepEqual(spotAt(answer, "b", DATE, 19 * 60, stopMinute, startMinute), { spot: endOfB, plannedStart: at(18, 0) });
  assert.deepEqual(spotAt(answer, "z", DATE, 10 * 60, stopMinute, startMinute), { spot: { technicianId: "z", serviceDate: DATE }, plannedStart: null },
    "a technician the answer does not list");
  assert.equal(verdict(answer, original, { technicianId: "z", serviceDate: DATE }, null).kind, "CANNOT_HOLD");
});

test("a preview takes every later stop's times from the answer: the moved visit's day without it, the chosen day with it", () => {
  const stop = (appointmentId: string, sequence: number, hour: number, minute: number, minutes: number) =>
    ({ appointmentId, sequence, plannedStart: at(hour, minute), plannedEnd: new Date(Date.parse(at(hour, minute)) + minutes * 60000).toISOString() });
  const timedStart = { ...start(15, 30, totals(66, 15900), true), stops: [stop("b1", 0, 14, 0, 45), stop("m", 1, 15, 30, 60)] };
  const timed: RouteInsertions = { ...answer, technicianDays: [
    day("a", totals(80, 20000), [gap("a1", "a2", 9, [])], { stops: [stop("a1", 0, 14, 0, 45), stop("a2", 1, 15, 0, 45)] }),
    day("b", totals(60, 15000), [gap("b1", undefined, 5, [timedStart])]),
  ] };
  const moved = previewTimes(timed, original, endOfB, at(15, 30));
  assert.equal(moved.get("a2")?.plannedStart, at(15, 0), "a2 closes up on a's day once m has left");
  assert.equal(moved.get("m")?.plannedStart, at(15, 30), "The moved visit is listed at its new start");
  assert.equal(moved.get("b1")?.plannedEnd, at(14, 45));
  assert.deepEqual([...previewTimes(timed, original, null, null).keys()], ["a1", "a2"], "With no place chosen, only the day it leaves is re-timed");
  assert.equal(previewTimes(timed, null, endOfB, at(18, 0)).size, 0, "A start the answer does not offer times nothing");
  assert.equal(previewTimes(answer, original, endOfB, at(15, 30)).size, 0, "An answer without timed stops leaves every stop as booked");
});

test("a new job is offered its best distinct arrival windows, the recommendation first", () => {
  const busy: RouteInsertions = { ...answer, technicianDays: [
    day("a", totals(80, 20000), [gap("a1", "a2", 9, [start(15, 0, totals(95, 21500)), start(15, 30, totals(96, 21700)), start(16, 0, totals(96, 21800))])]),
    day("b", totals(60, 15000), [gap("b1", undefined, 5, [start(17, 0, totals(66, 15900)), start(17, 30, totals(66, 16000))]), gap(undefined, "b1", 20, [start(13, 0, totals(80, 17000))])]),
  ] };
  const options = bestOptions(busy, null);
  assert.equal(options.length, 4);
  assert.equal(options[0]?.key, recommendation(busy, false)?.key);
  assert.equal(options[0]?.start.plannedStart, recommendation(busy, false)?.firstStart.plannedStart);
  assert.deepEqual(options.map(option => [option.spot.technicianId, option.start.plannedStart, option.addedBufferedMinutes]),
    [["b", at(17), 5], ["a", at(15), 9], ["a", at(16), 9], ["b", at(13), 20]],
    "17:30 shares b's 17:00 to 19:00 window and 15:30 shares a's 15:00 window, so each window is offered once");
  assert.equal(bestOptions(busy, null, 2).length, 2);
});

test("a move is offered its best places that keep the promise, one start each, and says where it is now", () => {
  const options = bestOptions(answer, original);
  assert.deepEqual(options.map(option => [option.key, option.start.plannedStart, option.samePlace]), [
    [spotKey(endOfB), at(15, 30), false],
    [spotKey({ technicianId: "a", serviceDate: DATE, afterAppointmentId: "a1", beforeAppointmentId: "a2" }), at(15), true]]);
  const nowhere: RouteInsertions = { ...answer, technicianDays: [day("b", totals(60, 15000), [gap("b1", undefined, 5, [start(18, 0, totals(66, 16100), false)])])] };
  assert.deepEqual(bestOptions(nowhere, original).map(option => option.start.keepsWindow), [false], "With no place that keeps it, window-changing places are offered");
  assert.deepEqual(bestOptions({ ...answer, technicianDays: [] }, original), [], "Nothing fits, nothing is offered");
});

test("a day paged to is joined into the week's answer with its timed stops, and only from the same road map", () => {
  const NEXT = "2026-11-19";
  const untimedNext = day("a", totals(70, 18000), [gap(undefined, undefined, 12, [])], { serviceDate: NEXT });
  const week: RouteInsertions = { ...answer, technicianDays: [...answer.technicianDays, untimedNext],
    skippedTechnicianDays: [...answer.skippedTechnicianDays, { technicianId: "c", serviceDate: NEXT, reason: "LOCATION_UNRESOLVED", message: "Stop c2 has no location." }] };
  const timedNext = { ...untimedNext, stops: [{ appointmentId: "n1", sequence: 0, plannedStart: at(9), plannedEnd: at(10) }] };
  const extra: RouteInsertions = { routingIdentity: answer.routingIdentity, technicianDays: [timedNext], skippedTechnicianDays: [] };
  const joined = withTimedDays(week, extra, [NEXT]);
  assert.ok(joined !== null);
  // The paged-to day is replaced, its skipped day with it; the other days are kept as they were.
  assert.deepEqual(joined.technicianDays.filter(item => item.serviceDate === NEXT), [timedNext]);
  assert.deepEqual(joined.technicianDays.filter(item => item.serviceDate === DATE), answer.technicianDays);
  assert.deepEqual(joined.skippedTechnicianDays, answer.skippedTechnicianDays);
  assert.equal(withTimedDays(week, { ...extra, routingIdentity: "omaha-2026-11" }, [NEXT]), null);
});

test("today ranks after every later day, so it is recommended only when nothing later fits", () => {
  // b's cheap gap is on DATE (today); a's dearer gap is moved to the next day.
  const NEXT = "2026-11-19";
  const split: RouteInsertions = { ...answer, technicianDays: answer.technicianDays.map(item => item.technicianId === "a" ? { ...item, serviceDate: NEXT } : item) };
  const aOnNext = spotKey({ technicianId: "a", serviceDate: NEXT, afterAppointmentId: "a1", beforeAppointmentId: "a2" });
  assert.equal(recommendation(split, false)?.key, spotKey(endOfB), "Without a today, the cheapest place wins");
  assert.equal(recommendation(split, false, DATE)?.key, aOnNext, "With DATE as today, the later day is recommended");
  assert.deepEqual(rankedFits(split, DATE).map(fit => fit.key), [aOnNext, spotKey(endOfB)], "Today's gap is still listed, last");
  assert.equal(bestOptions(split, null, 4, DATE)[0]?.key, aOnNext);
  assert.equal(bestOptions(split, null, 4, DATE).at(-1)?.spot.serviceDate, DATE);
  const onlyToday: RouteInsertions = { ...answer, technicianDays: answer.technicianDays.filter(item => item.technicianId === "b") };
  assert.equal(recommendation(onlyToday, false, DATE)?.key, spotKey(endOfB), "When only today fits, today is recommended");
});