import { test } from "node:test";
import assert from "node:assert/strict";
import { Prisma } from "@prisma/client";
import type { AppointmentFacts, SnapshotFacts, TechnicianFacts } from "./clientSnapshot";
import type { InsertionStart, RouteInsertions } from "./insertionContract";
import { dateBatches, insertionCalls, joined, placementPlan, placementWrite, PlacementRefused, type JobRow, type PlacementPlan } from "./apiPlacementCore";
import { placementPreviewRequest } from "./contracts";

const DATE = "2026-11-18";
const NEXT = "2026-11-19";
const settings: SnapshotFacts["settings"] = { regularHourly: new Prisma.Decimal("30"), overtimeHourly: new Prisma.Decimal("45"), mileagePerMile: new Prisma.Decimal("0.67"),
  travelBufferPct: new Prisma.Decimal("0.2"), travelBufferMinutes: 5, arrivalBufferPercent: 25, arrivalBufferMinMinutes: 15, arrivalBufferMaxMinutes: 60,
  fairnessBudget: new Prisma.Decimal("0.02"), offerLimit: 4, windowHours: 2, bookingHorizonWeekdays: 10, horizonMode: "FIXED", horizonLeadWeekdays: 2,
  horizonFullPercent: 85, dailySearch: "TABU_KOPT", costSearchSeconds: 10, fairnessSearchSeconds: 5, unimprovedSearchSeconds: null, bookingDeadlineMs: 5000,
  regularWindowThreshold: 2, version: 0, updatedAt: new Date("2026-10-01T00:00:00Z") };
const technician = (id: string): TechnicianFacts => ({ id, active: true, homeLat: 41.3, homeLng: -96, maxDailyMinutes: 540, qualifications: ["svc"],
  assignments: [], versions: [], overrides: [], absences: [] });
const at = (date: string, hour: number, minute = 0) => new Date(`${date}T${String(hour).padStart(2, "0")}:${String(minute).padStart(2, "0")}:00.000Z`);
const appointment = (id: string, technicianId: string, sequence: number, hour: number, change: Partial<AppointmentFacts> = {}): AppointmentFacts => ({
  id, technicianId, serviceDate: DATE, serviceId: "svc", durationMin: 60, windowStart: at(DATE, hour), windowEnd: at(DATE, hour + 2), sequence,
  plannedStart: at(DATE, hour, 10), address: { line1: "1 Main St", line2: null, city: "Omaha", state: "NE", postalCode: "68102", lat: 41.26, lng: -95.94 },
  ...change });
const facts = (): SnapshotFacts => ({ metro: { id: "metro", timezone: "America/Chicago" }, settings, dates: [DATE, NEXT],
  technicians: [technician("a"), technician("b")],
  appointments: [appointment("a1", "a", 0, 14), appointment("m", "a", 1, 15), appointment("a2", "a", 2, 18), appointment("b1", "b", 0, 14)],
  lastModified: new Map([[`a|${DATE}`, "2026-11-10T12:00:00.123456Z"], [`b|${DATE}`, "2026-11-11T12:00:00.654321Z"], [`b|${NEXT}`, "2026-11-11T13:00:00Z"]]) });
const job = (change: Partial<JobRow> = {}): JobRow => ({ id: "job-9", serviceId: "svc", durationMin: 45, status: "PENDING", hasAppointment: false, lat: 41.2, lng: -96.01, ...change });

const totals = { driveMinutes: 60, meters: 60000, paidMinutes: 480, costCents: 15000, bufferSeconds: 0 };
const stop = (appointmentId: string, sequence: number, hour: number) =>
  ({ appointmentId, sequence, plannedStart: at(DATE, hour, 10).toISOString(), plannedEnd: at(DATE, hour + 1, 10).toISOString() });
const start = (subject: string, hour: number, keepsWindow?: boolean): InsertionStart => ({ plannedStart: at(DATE, hour).toISOString(),
  window: { start: at(DATE, hour).toISOString(), end: at(DATE, hour + 2).toISOString() }, timed: totals,
  ...(keepsWindow === undefined ? {} : { keepsWindow }), stops: [stop("b1", 0, 14), { ...stop(subject, 1, hour), plannedStart: at(DATE, hour).toISOString() }] });
const leg = { seconds: 600, meters: 6000, bufferedMinutes: 14 };
/** The fresh answer a save gets: b's day takes the subject after b1, and a without m still holds. */
const answer = (subject: string, starts: InsertionStart[]): RouteInsertions => ({ routingIdentity: "omaha", skippedTechnicianDays: [], technicianDays: [
  { technicianId: "a", serviceDate: DATE, holdsAsGiven: true, qualified: true, asGiven: totals, stops: [stop("a1", 0, 14), stop("a2", 1, 18)],
    gaps: [{ afterAppointmentId: "a1", beforeAppointmentId: "a2", routable: true, toJob: leg, fromJob: leg, replaced: leg, addedSeconds: 600,
      addedMeters: 6000, addedBufferedMinutes: 14, starts: [start(subject, 15, true)] }] },
  { technicianId: "b", serviceDate: DATE, holdsAsGiven: true, qualified: true, asGiven: totals, stops: [stop("b1", 0, 14)],
    gaps: [{ afterAppointmentId: "b1", routable: true, toJob: leg, fromJob: leg, replaced: leg, addedSeconds: 600, addedMeters: 6000, addedBufferedMinutes: 14, starts }] },
] });
const endOfB = { technicianId: "b", serviceDate: DATE, afterAppointmentId: "b1" };

function refused(run: () => unknown, status: number, message: RegExp) {
  assert.throws(run, (error: unknown) => error instanceof PlacementRefused && error.status === status && message.test(error.message));
}

test("an unscheduled job is sent as it is, and only a pending, located job without an appointment", () => {
  const plan = placementPlan(facts(), { kind: "job", job: job() });
  assert.deepEqual(plan.job, { id: "job-9", serviceId: "svc", durationMinutes: 45, location: { lat: 41.2, lng: -96.01 } });
  assert.equal(plan.original, null);
  assert.equal(plan.facts.appointments.length, 4);
  refused(() => placementPlan(facts(), { kind: "job", job: job({ status: "SCHEDULED" }) }), 409, /not waiting/);
  refused(() => placementPlan(facts(), { kind: "job", job: job({ hasAppointment: true }) }), 409, /not waiting/);
  refused(() => placementPlan(facts(), { kind: "job", job: job({ lat: null }) }), 409, /Confirm its pin/);
});

test("a moved appointment leaves its day, which closes up, and is sent with the window its customer was promised", () => {
  const plan = placementPlan(facts(), { kind: "appointment", appointmentId: "m" });
  assert.deepEqual(plan.job, { id: "m", serviceId: "svc", durationMinutes: 60, location: { lat: 41.26, lng: -95.94 },
    window: { start: "2026-11-18T15:00:00.000Z", end: "2026-11-18T17:00:00.000Z" } });
  assert.deepEqual(plan.original, { appointmentId: "m", technicianId: "a", serviceDate: DATE,
    window: { start: "2026-11-18T15:00:00.000Z", end: "2026-11-18T17:00:00.000Z" }, plannedStart: "2026-11-18T15:10:00.000Z",
    afterAppointmentId: "a1", beforeAppointmentId: "a2" });
  assert.deepEqual(plan.facts.appointments.map(item => `${item.id}:${item.sequence}`), ["a1:0", "a2:1", "b1:0"]);
  refused(() => placementPlan(facts(), { kind: "appointment", appointmentId: "gone" }), 404, /not booked/);
  const unlocated = { ...facts(), appointments: [appointment("m", "a", 0, 15, { address: { ...appointment("m", "a", 0, 15).address, lat: null, lng: null } })] };
  refused(() => placementPlan(unlocated, { kind: "appointment", appointmentId: "m" }), 409, /Confirm its pin/);
});

test("a week too big for one call is split by date, and the answers join only from one road map", () => {
  const counts = new Map([[DATE, 120], [NEXT, 90]]);
  assert.deepEqual(dateBatches(facts(), date => counts.get(date) ?? 0, 200), [[DATE], [NEXT]]);
  assert.deepEqual(dateBatches(facts(), () => 50, 200), [[DATE, NEXT]]);
  refused(() => dateBatches(facts(), () => 201, 200), 413, /more than the 200/);
  const one = answer("job-9", []);
  assert.equal(joined([one, { ...one, technicianDays: [] }]).technicianDays.length, 2);
  refused(() => joined([one, { ...one, routingIdentity: "other" }]), 503, /road map changed/);
});

test("only the days on screen are asked with timed stops; the rest of the week is asked without, and each group is split by date", () => {
  const week = ["2026-11-16", "2026-11-17", DATE, NEXT, "2026-11-20"];
  // The day view: its one day timed, the other four in one untimed call.
  assert.deepEqual(insertionCalls(week, [DATE], () => 40, 200),
    [{ dates: [DATE], routes: true }, { dates: ["2026-11-16", "2026-11-17", NEXT, "2026-11-20"], routes: false }]);
  // A day paged to while placing is asked alone.
  assert.deepEqual(insertionCalls([NEXT], [NEXT], () => 40, 200), [{ dates: [NEXT], routes: true }]);
  // The week view times every day, in one call.
  assert.deepEqual(insertionCalls(week, week, () => 40, 200), [{ dates: week, routes: true }]);
  // Each group stays under the technician-day limit on its own.
  assert.deepEqual(insertionCalls(week, ["2026-11-16", "2026-11-17"], () => 90, 200), [
    { dates: ["2026-11-16", "2026-11-17"], routes: true },
    { dates: [DATE, NEXT], routes: false }, { dates: ["2026-11-20"], routes: false }]);
  refused(() => insertionCalls([DATE], [DATE], () => 201, 200), 413, /more than the 200/);
});

test("a preview request names the days on screen, inside the week shown, and whether the rest of the week comes too", () => {
  const body = { metroId: "metro", monday: "2026-11-16", jobId: "job-9", timedDates: [DATE], withRestOfWeek: true };
  assert.equal(placementPreviewRequest.safeParse(body).success, true);
  // Sunday is the last day of the week of Monday the 16th; the next Monday is not.
  assert.equal(placementPreviewRequest.safeParse({ ...body, timedDates: ["2026-11-22"] }).success, true);
  assert.equal(placementPreviewRequest.safeParse({ ...body, timedDates: ["2026-11-23"] }).success, false);
  assert.equal(placementPreviewRequest.safeParse({ ...body, timedDates: ["2026-11-15"] }).success, false);
  assert.equal(placementPreviewRequest.safeParse({ ...body, timedDates: [] }).success, false);
  const { timedDates: _timed, ...untimed } = body;
  assert.equal(placementPreviewRequest.safeParse(untimed).success, false);
  const { withRestOfWeek: _rest, ...noRest } = body;
  assert.equal(placementPreviewRequest.safeParse(noRest).success, false);
});

test("saving a new job creates its appointment with the promised window and writes only the day it joins", () => {
  const plan = placementPlan(facts(), { kind: "job", job: job() });
  const write = placementWrite(plan, answer("job-9", [start("job-9", 16)]), endOfB, at(DATE, 16).toISOString(), false);
  assert.equal(write.verdict.kind, "NEW");
  assert.deepEqual(write.assignments.map(item => `${item.technicianId}:${item.appointmentId}:${item.sequence}`), ["b:b1:0", "b:job-9:1"]);
  assert.deepEqual(write.technicianDays, [{ technicianId: "b", serviceDate: DATE, lastModified: "2026-11-11T12:00:00.654321Z" }]);
  assert.deepEqual(write.create, { jobId: "job-9", windowStart: at(DATE, 16), windowEnd: at(DATE, 18) });
  assert.equal(write.promiseChange, undefined);
  refused(() => placementWrite(plan, answer("job-9", [start("job-9", 16)]), endOfB, at(DATE, 17).toISOString(), false), 409, /no longer fits/);
});

test("a move that keeps its promise rewrites both days, and one that changes it needs the acknowledgement and is recorded", () => {
  const plan: PlacementPlan = placementPlan(facts(), { kind: "appointment", appointmentId: "m" });
  const kept = placementWrite(plan, answer("m", [start("m", 15, true)]), endOfB, at(DATE, 15).toISOString(), false);
  assert.equal(kept.verdict.kind, "KEEPS_PROMISE");
  assert.deepEqual(kept.assignments.map(item => `${item.technicianId}:${item.appointmentId}:${item.sequence}`), ["b:b1:0", "b:m:1", "a:a1:0", "a:a2:1"]);
  assert.deepEqual(kept.technicianDays.map(item => item.technicianId), ["b", "a"]);
  assert.equal(kept.create, undefined);
  assert.equal(kept.promiseChange, undefined);

  const late = answer("m", [start("m", 18, false)]);
  refused(() => placementWrite(plan, late, endOfB, at(DATE, 18).toISOString(), false), 400, /Acknowledge the policy violation/);
  const changed = placementWrite(plan, late, endOfB, at(DATE, 18).toISOString(), true);
  assert.equal(changed.verdict.kind, "CHANGES_PROMISE");
  assert.deepEqual(changed.promiseChange, { appointmentId: "m", from: { serviceDate: DATE, windowStart: at(DATE, 15), windowEnd: at(DATE, 17) },
    to: { serviceDate: DATE, windowStart: at(DATE, 18), windowEnd: at(DATE, 20) } });

  const leftBroken = { ...late, technicianDays: late.technicianDays.map(item => item.technicianId === "a"
    ? { ...item, holdsAsGiven: false, asGiven: undefined, stops: undefined, gaps: item.gaps.map(gap => ({ ...gap, starts: [] })) } : item) };
  refused(() => placementWrite(plan, leftBroken, endOfB, at(DATE, 18).toISOString(), true), 409, /could not be timed/);
});
