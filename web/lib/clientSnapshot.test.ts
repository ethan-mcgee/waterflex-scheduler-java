import { test } from "node:test";
import assert from "node:assert/strict";
import { Prisma } from "@prisma/client";
import { assembleSnapshot, SnapshotError, type AppointmentFacts, type SnapshotFacts, type TechnicianFacts } from "./clientSnapshot";

const DATE = "2026-10-12"; // a Monday
const settings = { regularHourly: new Prisma.Decimal("30"), overtimeHourly: new Prisma.Decimal("45"), mileagePerMile: new Prisma.Decimal("0.67"),
  travelBufferPct: new Prisma.Decimal("0.2"), travelBufferMinutes: 5, fairnessBudget: new Prisma.Decimal("0.02"), offerLimit: 4,
  bookingHorizonWeekdays: 10, version: 0, updatedAt: new Date("2026-10-01T00:00:00Z") };
const week = Array.from({ length: 7 }, (_, dayOfWeek) => ({ dayOfWeek, available: dayOfWeek >= 1 && dayOfWeek <= 5,
  shiftStartMin: dayOfWeek >= 1 && dayOfWeek <= 5 ? 480 : null, shiftEndMin: dayOfWeek >= 1 && dayOfWeek <= 5 ? 1020 : null }));
const depot = { id: "depot-a", metroId: "metro", lat: 41.25, lng: -95.93,
  policies: [{ effectiveDate: "1900-01-01", departure: "DEPOT" as const, returnTo: "HOME" as const }] };

function technician(id: string, change: Partial<TechnicianFacts> = {}): TechnicianFacts {
  return { id, active: true, homeLat: 41.3, homeLng: -96.0, maxDailyMinutes: 540, qualifications: ["svc-b", "svc-a"],
    assignments: [{ effectiveDate: "1900-01-01", depot }], versions: [{ effectiveDate: new Date("1900-01-01T00:00:00Z"), days: week }],
    overrides: [], absences: [], ...change };
}

function appointment(id: string, technicianId: string, change: Partial<AppointmentFacts> = {}): AppointmentFacts {
  return { id, technicianId, serviceDate: DATE, serviceId: "svc-a", durationMin: 60,
    windowStart: new Date("2026-10-12T14:00:00Z"), windowEnd: new Date("2026-10-12T18:00:00Z"), sequence: 0,
    plannedStart: new Date("2026-10-12T14:30:00Z"),
    address: { line1: "1 Main St", line2: null, city: "Omaha", state: "NE", postalCode: "68102", lat: 41.26, lng: -95.94 }, ...change };
}

function facts(change: Partial<SnapshotFacts> = {}): SnapshotFacts {
  const technicians = change.technicians ?? [technician("tech-a")];
  return { metro: { id: "metro", timezone: "America/Chicago" }, settings, dates: [DATE], technicians, appointments: [],
    lastModified: new Map(technicians.map(item => [`${item.id}|${DATE}`, "2026-10-08T12:00:00.123456Z"])), ...change };
}

const code = (run: () => unknown) => {
  try { run(); } catch (error) { if (error instanceof SnapshotError) return error.code; throw error; }
  return "BUILT";
};

test("a working technician-day carries the shift, endpoints, paid limit and exact change time", () => {
  const snapshot = assembleSnapshot(facts({ appointments: [appointment("appt-1", "tech-a")] }));
  assert.deepEqual(snapshot.rates, { regularHourly: "30", overtimeHourly: "45", mileagePerMile: "0.67", travelBufferPct: "0.2", travelBufferMinutes: 5 });
  assert.deepEqual(snapshot.policy, { fairnessBudget: "0.02" });
  assert.deepEqual(snapshot.technicians, [{ id: "tech-a", qualifications: ["svc-a", "svc-b"] }]);
  assert.deepEqual(snapshot.technicianDays, [{ technicianId: "tech-a", serviceDate: DATE, lastModified: "2026-10-08T12:00:00.123456Z",
    shift: { start: "2026-10-12T13:00:00.000Z", end: "2026-10-12T22:00:00.000Z" }, absences: [],
    start: { lat: 41.25, lng: -95.93 }, end: { lat: 41.3, lng: -96.0 }, maxPaidMinutes: 540 }]);
  assert.equal(snapshot.appointments.length, 1);
  assert.deepEqual(snapshot.appointments[0]?.location, { lat: 41.26, lng: -95.94 });
});

test("a date exception replaces the weekly day, and approved time off becomes absences", () => {
  const snapshot = assembleSnapshot(facts({ technicians: [technician("tech-a", {
    overrides: [{ date: DATE, available: true, shiftStartMin: 600, shiftEndMin: 900 }],
    absences: [{ date: DATE, startMin: 720, endMin: 780 }, { date: "2026-10-13", startMin: 600, endMin: 660 }] })] }));
  const day = snapshot.technicianDays[0];
  assert.deepEqual(day?.shift, { start: "2026-10-12T15:00:00.000Z", end: "2026-10-12T20:00:00.000Z" });
  assert.deepEqual(day?.absences, [{ start: "2026-10-12T17:00:00.000Z", end: "2026-10-12T18:00:00.000Z" }]);
});

test("days off, inactive technicians and other metros are left out, and so are their technicians", () => {
  const elsewhere = { ...depot, id: "depot-b", metroId: "other-metro" };
  const snapshot = assembleSnapshot(facts({ technicians: [
    technician("off", { overrides: [{ date: DATE, available: false, shiftStartMin: null, shiftEndMin: null }] }),
    technician("inactive", { active: false }),
    technician("moved", { assignments: [{ effectiveDate: "1900-01-01", depot }, { effectiveDate: DATE, depot: elsewhere }] }),
    technician("unassigned", { assignments: [] }),
  ], appointments: [appointment("elsewhere", "moved")] }));
  assert.deepEqual(snapshot.technicians, []);
  assert.deepEqual(snapshot.technicianDays, []);
  assert.deepEqual(snapshot.appointments, [], "An appointment in another metro belongs to that metro's snapshot");
});

test("an address without coordinates is sent as an address for the scheduler to locate", () => {
  const snapshot = assembleSnapshot(facts({ appointments: [appointment("appt-1", "tech-a", {
    address: { line1: "1 Main St", line2: "Unit 2", city: "Omaha", state: "NE", postalCode: "68102", lat: null, lng: null } })] }));
  assert.deepEqual(snapshot.appointments[0]?.location, { address: { line1: "1 Main St", line2: "Unit 2", city: "Omaha", state: "NE", postalCode: "68102" } });
});

test("missing or contradictory facts stop the build instead of being filled in", () => {
  assert.equal(code(() => assembleSnapshot(facts({ appointments: [appointment("appt-1", "tech-a")],
    technicians: [technician("tech-a", { overrides: [{ date: DATE, available: false, shiftStartMin: null, shiftEndMin: null }] })] }))), "INCONSISTENT",
    "An appointment on a technician's day off");
  assert.equal(code(() => assembleSnapshot(facts({ technicians: [technician("tech-a", { active: false })], appointments: [appointment("appt-1", "tech-a")] }))), "INCONSISTENT");
  assert.equal(code(() => assembleSnapshot(facts({ technicians: [technician("tech-a", { versions: [] })] }))), "INCONSISTENT");
  assert.equal(code(() => assembleSnapshot(facts({ technicians: [technician("tech-a", { assignments: [{ effectiveDate: "1900-01-01", depot: { ...depot, policies: [] } }] })] }))), "INCONSISTENT");
  assert.equal(code(() => assembleSnapshot(facts({ technicians: [technician("tech-a", { overrides: [{ date: DATE, available: true, shiftStartMin: 900, shiftEndMin: 600 }] })] }))), "INCONSISTENT");
  assert.equal(code(() => assembleSnapshot(facts({ technicians: [technician("tech-a", { maxDailyMinutes: 0 })] }))), "INCONSISTENT");
  assert.equal(code(() => assembleSnapshot(facts({ lastModified: new Map() }))), "INCONSISTENT");
  assert.equal(code(() => assembleSnapshot(facts({ appointments: [appointment("appt-1", "tech-a", {
    address: { line1: "1 Main St", line2: null, city: "Omaha", state: "NE", postalCode: "68102", lat: 41.2, lng: null } })] }))), "INCONSISTENT");
  assert.equal(code(() => assembleSnapshot(facts({ metro: { id: "metro", timezone: "America/Denver" } }))), "UNSUPPORTED_TIME_ZONE");
});
