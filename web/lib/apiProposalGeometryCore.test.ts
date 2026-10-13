import { test } from "node:test";
import assert from "node:assert/strict";
import { baselineMatches, overlayProposal } from "./apiProposalGeometryCore";
import type { Placement } from "./apiDispatchCore";
import type { PublicSnapshot } from "./publicApi";
import type { DailyProposal } from "./schedulerApi";

const DATE = "2026-10-14";
const at = (hour: number) => `${DATE}T${String(hour).padStart(2, "0")}:00:00.000Z`;
const technicianDay = (technicianId: string) => ({ technicianId, serviceDate: DATE, lastModified: "2026-10-01T00:00:00.000Z", shift: { start: at(13), end: at(22) },
  absences: [], start: { lat: 41.1, lng: -95.9 }, end: { lat: 41.1, lng: -95.9 }, maxPaidMinutes: 540 });
const appointment = (id: string, technicianId: string, sequence: number, hour: number) => ({ id, technicianId, serviceDate: DATE, serviceId: "svc", durationMinutes: 45,
  window: { start: at(14), end: at(20) }, location: { lat: 41.2, lng: -95.8 }, sequence, plannedStart: at(hour) });
const snapshot: PublicSnapshot = { metroId: "omaha", timeZone: "America/Chicago",
  rates: { regularHourly: "20", overtimeHourly: "30", mileagePerMile: "0.5", travelBufferPct: "0", travelBufferMinutes: 0 },
  policy: { fairnessBudget: "0.02", dailySearch: "TABU_KOPT", costSearchSeconds: 10, fairnessSearchSeconds: 5 },
  technicians: [{ id: "a", qualifications: [] }, { id: "b", qualifications: [] }, { id: "c", qualifications: [] }],
  technicianDays: [technicianDay("a"), technicianDay("b"), technicianDay("c")],
  appointments: [appointment("x", "a", 0, 14), appointment("y", "a", 1, 16), appointment("z", "c", 0, 15)] };
const baseline: Placement[] = snapshot.appointments.map(item => ({ appointmentId: item.id, technicianId: item.technicianId, sequence: item.sequence, plannedStart: item.plannedStart }));
const days = snapshot.technicianDays.map(day => ({ technicianId: day.technicianId, serviceDate: day.serviceDate }));
const stop = (appointmentId: string, sequence: number, hour: number) => ({ appointmentId, sequence, plannedStart: at(hour), plannedEnd: at(hour).replace(":00:00", ":45:00") });
const proposal = (change: Partial<DailyProposal> = {}): DailyProposal => ({ proposalId: "p1", inputRevision: "0".repeat(64), decision: "IMPROVED", reason: "Cheaper",
  routes: [{ technicianId: "a", serviceDate: DATE, stops: [stop("y", 0, 15)] }, { technicianId: "b", serviceDate: DATE, stops: [stop("x", 0, 14)] }],
  unresolvedAppointmentIds: [], skippedTechnicianDays: [{ technicianId: "c", serviceDate: DATE, reason: "LOCATION_UNRESOLVED", message: "No match" }],
  costCents: 900, overtimeMinutes: 0, ...change });

test("the overlay moves each appointment where the proposal puts it, and a skipped day keeps its stops", () => {
  const moved = overlayProposal(snapshot, proposal());
  assert.deepEqual(moved.appointments.map(item => [item.id, item.technicianId, item.sequence, item.plannedStart]),
    [["y", "a", 0, at(15)], ["x", "b", 0, at(14)], ["z", "c", 0, at(15)]], "Reassigned, resequenced and retimed; sorted as the snapshot is");
  assert.deepEqual(moved.technicianDays, snapshot.technicianDays, "The technician-days are the live ones");
  assert.equal(snapshot.appointments[0]?.technicianId, "a", "The live snapshot is not changed");
});

test("a proposal that leaves an appointment out, or names one the day does not have, is never drawn", () => {
  const route = proposal().routes[1];
  assert.ok(route !== undefined);
  assert.throws(() => overlayProposal(snapshot, proposal({ routes: [route] })), /does not place appointment y/);
  assert.throws(() => overlayProposal(snapshot, proposal({ routes: [...proposal().routes, { technicianId: "c", serviceDate: DATE, stops: [stop("q", 0, 17)] }] })), /not in the snapshot/);
  assert.throws(() => overlayProposal(snapshot, proposal({ routes: [{ ...route, technicianId: "d" }] })), /not in the snapshot/);
  assert.throws(() => overlayProposal(snapshot, proposal({ routes: [{ technicianId: "c", serviceDate: DATE, stops: [stop("x", 0, 14), stop("y", 1, 15)] }] })), /skipped technician-day/);
});

test("the live day matches the baseline only when every placement and technician-day is unchanged", () => {
  assert.ok(baselineMatches(snapshot, baseline, days));
  assert.ok(baselineMatches(snapshot, baseline.map(item => ({ ...item, plannedStart: item.plannedStart.replace(".000Z", "Z") })), days), "The same instant in another spelling matches");
  const first = baseline[0];
  assert.ok(first !== undefined);
  assert.equal(baselineMatches(snapshot, [{ ...first, sequence: 3 }, ...baseline.slice(1)], days), false, "A resequenced stop");
  assert.equal(baselineMatches(snapshot, [{ ...first, plannedStart: at(17) }, ...baseline.slice(1)], days), false, "A retimed stop");
  assert.equal(baselineMatches(snapshot, baseline.slice(1), days), false, "A booking made since");
  assert.equal(baselineMatches(snapshot, baseline, days.slice(1)), false, "A technician-day that started working since");
  assert.equal(baselineMatches({ ...snapshot, technicianDays: snapshot.technicianDays.slice(1) }, baseline, days), false, "A technician-day that stopped working");
});
