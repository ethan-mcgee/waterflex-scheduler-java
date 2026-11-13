import { test } from "node:test";
import assert from "node:assert/strict";
import { Prisma } from "@prisma/client";
import { assembleSnapshot, type AppointmentFacts, type SnapshotFacts, type TechnicianFacts } from "./clientSnapshot";
import { moveCheckResult } from "./contracts";
import { moveResult, MoveRefused, onlyTechnicians, withMove, type MovedFacts } from "./apiMoveCheckCore";
import type { RouteEvaluation } from "./schedulerApi";

const DATE = "2026-10-12"; // a Monday
const settings = { regularHourly: new Prisma.Decimal("30"), overtimeHourly: new Prisma.Decimal("45"), mileagePerMile: new Prisma.Decimal("0.67"),
  travelBufferPct: new Prisma.Decimal("0.2"), travelBufferMinutes: 5, arrivalBufferPercent: 25, arrivalBufferMinMinutes: 15, arrivalBufferMaxMinutes: 60,
  fairnessBudget: new Prisma.Decimal("0.02"), offerLimit: 4, windowHours: 4,
  bookingHorizonWeekdays: 10, horizonMode: "FIXED", horizonLeadWeekdays: 2, horizonFullPercent: 85, dailySearch: "TABU_KOPT", costSearchSeconds: 10, fairnessSearchSeconds: 5, unimprovedSearchSeconds: null, bookingDeadlineMs: 5000, regularWindowThreshold: 2, version: 0, updatedAt: new Date("2026-10-01T00:00:00Z") };
const week = Array.from({ length: 7 }, (_, dayOfWeek) => ({ dayOfWeek, available: dayOfWeek >= 1 && dayOfWeek <= 5,
  shiftStartMin: dayOfWeek >= 1 && dayOfWeek <= 5 ? 480 : null, shiftEndMin: dayOfWeek >= 1 && dayOfWeek <= 5 ? 1020 : null }));
const depot = { id: "depot-a", metroId: "metro", lat: 41.25, lng: -95.93,
  policies: [{ effectiveDate: "1900-01-01", departure: "DEPOT" as const, returnTo: "HOME" as const }] };

function technician(id: string, change: Partial<TechnicianFacts> = {}): TechnicianFacts {
  return { id, active: true, homeLat: 41.3, homeLng: -96.0, maxDailyMinutes: 540, qualifications: ["svc-a"],
    assignments: [{ effectiveDate: "1900-01-01", depot }], versions: [{ effectiveDate: new Date("1900-01-01T00:00:00Z"), days: week }],
    overrides: [], absences: [], ...change };
}

/** An appointment whose promised window starts at the given UTC hour and lasts two hours. */
function appointment(id: string, technicianId: string, sequence: number, startHour: number, change: Partial<AppointmentFacts> = {}): AppointmentFacts {
  const at = (hour: number) => new Date(Date.UTC(2026, 9, 12, hour));
  return { id, technicianId, serviceDate: DATE, serviceId: "svc-a", durationMin: 60, windowStart: at(startHour), windowEnd: at(startHour + 2),
    sequence, plannedStart: at(startHour),
    address: { line1: "1 Main St", line2: null, city: "Omaha", state: "NE", postalCode: "68102", lat: 41.26, lng: -95.94 }, ...change };
}

/** Three technicians: `a` with three stops, `b` with two, `c` with one that only matters as a bystander. */
function facts(change: Partial<SnapshotFacts> = {}): SnapshotFacts {
  const technicians = change.technicians ?? [technician("a"), technician("b"), technician("c")];
  return { metro: { id: "metro", timezone: "America/Chicago" }, settings, dates: [DATE], technicians,
    appointments: [appointment("a1", "a", 0, 14), appointment("a2", "a", 1, 16), appointment("a3", "a", 2, 18),
      appointment("b1", "b", 0, 14), appointment("b2", "b", 1, 18), appointment("c1", "c", 0, 15)],
    lastModified: new Map(technicians.map(item => [`${item.id}|${DATE}`, "2026-10-08T12:00:00.123456Z"])), ...change };
}

function moved(result: ReturnType<typeof withMove>): MovedFacts {
  if ("refusal" in result) throw new Error(`Expected a move, got ${result.refusal.message}`);
  return result;
}

const order = (value: SnapshotFacts, technicianId: string) => value.appointments
  .filter(item => item.technicianId === technicianId).sort((x, y) => x.sequence - y.sequence).map(item => `${item.id}:${item.sequence}`);

test("the moved stop leaves the source, which closes up, and joins the target in promised-window order", () => {
  const before = facts();
  const after = moved(withMove(before, { appointmentId: "a2", serviceDate: DATE, targetTechnicianId: "b" }));
  assert.equal(after.sourceTechnicianId, "a");
  assert.equal(after.targetTechnicianId, "b");
  assert.deepEqual(order(after.facts, "a"), ["a1:0", "a3:1"]);
  assert.deepEqual(order(after.facts, "b"), ["b1:0", "a2:1", "b2:2"], "a 16:00 window goes between 14:00 and 18:00");
  assert.deepEqual(order(after.facts, "c"), ["c1:0"], "a bystander is untouched");
  assert.equal(after.facts.appointments.find(item => item.id === "a2")?.plannedStart.toISOString(), "2026-10-12T16:00:00.000Z", "planned starts are kept as stored");
  assert.deepEqual(order(before, "b"), ["b1:0", "b2:1"], "the loaded facts are not changed");
});

test("window ties go by window end, then id, and a latest window goes last", () => {
  const tied = moved(withMove(facts(), { appointmentId: "a1", serviceDate: DATE, targetTechnicianId: "b" }));
  assert.deepEqual(order(tied.facts, "b"), ["a1:0", "b1:1", "b2:2"], "same window as b1: a1 sorts before b1 by id");
  const last = moved(withMove(facts(), { appointmentId: "a3", serviceDate: DATE, targetTechnicianId: "c" }));
  assert.deepEqual(order(last.facts, "c"), ["c1:0", "a3:1"]);
  const emptied = moved(withMove(facts(), { appointmentId: "c1", serviceDate: DATE, targetTechnicianId: "a" }));
  assert.deepEqual(order(emptied.facts, "c"), [], "the source may be left with no stops");
  assert.deepEqual(order(emptied.facts, "a"), ["a1:0", "c1:1", "a2:2", "a3:3"]);
});

test("a target who is off that day is not feasible without asking the scheduler", () => {
  const off = facts({ technicians: [technician("a"), technician("b", { overrides: [{ date: DATE, available: false, shiftStartMin: null, shiftEndMin: null }] }), technician("c")] });
  const result = withMove(off, { appointmentId: "a2", serviceDate: DATE, targetTechnicianId: "b" });
  assert.ok("refusal" in result);
  assert.equal(result.refusal.outcome, "NOT_FEASIBLE");
  assert.ok(moveCheckResult.safeParse(result.refusal).success);
  const inactive = withMove(facts({ technicians: [technician("a"), technician("b", { active: false }), technician("c")] }),
    { appointmentId: "a2", serviceDate: DATE, targetTechnicianId: "b" });
  assert.ok("refusal" in inactive);
});

test("unknown appointments and technicians, the same technician and another metro are refused", () => {
  const status = (run: () => unknown) => {
    try { run(); } catch (error) { if (error instanceof MoveRefused) return error.status; throw error; }
    return 0;
  };
  assert.equal(status(() => withMove(facts(), { appointmentId: "nope", serviceDate: DATE, targetTechnicianId: "b" })), 404);
  assert.equal(status(() => withMove(facts(), { appointmentId: "a1", serviceDate: "2026-10-13", targetTechnicianId: "b" })), 404, "the appointment is not on that day");
  assert.equal(status(() => withMove(facts(), { appointmentId: "a1", serviceDate: DATE, targetTechnicianId: "nobody" })), 404);
  assert.equal(status(() => withMove(facts(), { appointmentId: "a1", serviceDate: DATE, targetTechnicianId: "a" })), 409);
  const elsewhere = { ...depot, id: "depot-b", metroId: "other-metro" };
  const away = facts({ technicians: [technician("a"), technician("b", { assignments: [{ effectiveDate: "1900-01-01", depot: elsewhere }] }), technician("c")] });
  assert.equal(status(() => withMove(away, { appointmentId: "a1", serviceDate: DATE, targetTechnicianId: "b" })), 409);
});

test("only the two technicians' days are assembled, and the snapshot is valid", () => {
  const after = moved(withMove(facts(), { appointmentId: "a2", serviceDate: DATE, targetTechnicianId: "b" }));
  const snapshot = assembleSnapshot(onlyTechnicians(after.facts, [after.sourceTechnicianId, after.targetTechnicianId]));
  assert.deepEqual(snapshot.technicianDays.map(day => day.technicianId), ["a", "b"]);
  assert.deepEqual(snapshot.appointments.map(item => `${item.technicianId}:${item.id}:${item.sequence}`), ["a:a1:0", "a:a3:1", "b:b1:0", "b:a2:1", "b:b2:2"]);
});

test("the scheduler's timing becomes the dispatcher's answer", () => {
  const after = moved(withMove(facts(), { appointmentId: "a2", serviceDate: DATE, targetTechnicianId: "b" }));
  const evaluation = (change: Partial<RouteEvaluation>): RouteEvaluation =>
    ({ feasible: true, routes: [], skippedTechnicianDays: [], costCents: 1000, overtimeMinutes: 0, ...change });
  const feasible = moveResult(evaluation({}), after, DATE);
  assert.equal(feasible.outcome, "FEASIBLE");
  assert.equal(feasible.overtimeMinutes, 0);
  const late = moveResult(evaluation({ feasible: false, overtimeMinutes: 30 }), after, DATE);
  assert.equal(late.outcome, "NOT_FEASIBLE");
  assert.equal(late.overtimeMinutes, null, "a day that does not hold was not fully timed, so it has no overtime figure");
  const skipped = moveResult(evaluation({ skippedTechnicianDays: [{ technicianId: "b", serviceDate: DATE, reason: "LOCATION_UNRESOLVED", message: "Address not found." }] }), after, DATE);
  assert.equal(skipped.outcome, "NOT_CHECKED", "a day the scheduler skipped is not reported as feasible");
  for (const result of [feasible, late, skipped]) assert.ok(moveCheckResult.safeParse(result).success, result.outcome);
});
