import { test } from "node:test";
import assert from "node:assert/strict";
import { checkDailyReceipt, checkProposal, proposalChanges, storedProposal } from "./apiDispatchCore";
import { problem, type CommitReceipt, type DailyProposal } from "./schedulerApi";
import type { PublicSnapshot } from "./publicApi";

const DATE = "2026-10-12";
const at = (time: string) => `${DATE}T${time}:00-05:00`;
const location = { lat: 43.748, lng: 7.438 };
const day = (technicianId: string) => ({ technicianId, serviceDate: DATE, lastModified: "2026-10-11T21:04:17.123456Z",
  shift: { start: at("08:00"), end: at("17:00") }, absences: [], start: location, end: location, maxPaidMinutes: 540 });
const appointment = (id: string, technicianId: string, sequence: number, start: string) => ({ id, technicianId, serviceDate: DATE, serviceId: "svc",
  durationMinutes: 45, window: { start: at("08:00"), end: at("12:00") }, location, sequence, plannedStart: at(start) });
const snapshot: PublicSnapshot = { metroId: "metro", timeZone: "America/Chicago",
  rates: { regularHourly: "30", overtimeHourly: "45", mileagePerMile: "0.67", travelBufferPct: "0", travelBufferMinutes: 0 },
  policy: { fairnessBudget: "0.02" }, technicians: [{ id: "a", qualifications: ["svc"] }, { id: "b", qualifications: ["svc"] }],
  technicianDays: [day("a"), day("b")], appointments: [appointment("x", "a", 0, "09:00"), appointment("y", "b", 0, "09:00")] };
const stop = (appointmentId: string, sequence: number, start: string, end: string) => ({ appointmentId, sequence, plannedStart: at(start), plannedEnd: at(end) });
const improved: DailyProposal = { proposalId: "prop-1", inputRevision: "a".repeat(64), decision: "IMPROVED", reason: "Lower fleet cost within policy",
  routes: [{ technicianId: "a", serviceDate: DATE, stops: [stop("x", 0, "09:00", "09:45"), stop("y", 1, "10:00", "10:45")] },
    { technicianId: "b", serviceDate: DATE, stops: [] }],
  unresolvedAppointmentIds: [], skippedTechnicianDays: [], costCents: 9000, overtimeMinutes: 0 };
const covered = [{ technicianId: "a", serviceDate: DATE }, { technicianId: "b", serviceDate: DATE }];
const receipt: CommitReceipt = { receiptId: "rcpt-1",
  assignments: [{ appointmentId: "x", technicianId: "a", serviceDate: DATE, sequence: 0, plannedStart: at("09:00"), plannedEnd: at("09:45") },
    { appointmentId: "y", technicianId: "a", serviceDate: DATE, sequence: 1, plannedStart: at("10:00"), plannedEnd: at("10:45") }],
  technicianDays: [{ technicianId: "b", serviceDate: DATE, lastModified: "2026-10-11T21:04:17.123456Z" },
    { technicianId: "a", serviceDate: DATE, lastModified: "2026-10-11T21:04:17.123456Z" }] };

test("a proposal is accepted only when it routes the snapshot's own technician-days and appointments, each once", () => {
  assert.doesNotThrow(() => checkProposal(improved, snapshot, DATE));
  assert.throws(() => checkProposal(improved, snapshot, "2026-10-13"), /for 2026-10-13/);
  assert.throws(() => checkProposal({ ...improved, routes: [...improved.routes, { technicianId: "c", serviceDate: DATE, stops: [] }] }, snapshot, DATE),
    /not in the snapshot/, "A technician-day of another client is never written");
  assert.throws(() => checkProposal({ ...improved, routes: [{ technicianId: "a", serviceDate: DATE, stops: [stop("x", 0, "09:00", "09:45"), stop("z", 1, "10:00", "10:45")] }] }, snapshot, DATE),
    /appointment z, which is not in the snapshot/);
  assert.throws(() => checkProposal({ ...improved, unresolvedAppointmentIds: ["x"] }, snapshot, DATE), /places appointment x twice/);
  assert.throws(() => checkProposal({ ...improved, skippedTechnicianDays: [{ technicianId: "b", serviceDate: DATE, reason: "LOCATION_UNRESOLVED", message: "No pin" }] }, snapshot, DATE),
    /lists technician-day b\|2026-10-12 twice/);
  assert.throws(() => checkProposal({ ...improved, routes: [{ technicianId: "a", serviceDate: DATE, stops: [stop("x", 1, "09:00", "09:45")] }] }, snapshot, DATE), /sequence 1 at position 0/);
});

test("an improvement that leaves an appointment unresolved is refused, since a partial schedule is never applied", () => {
  const partial: DailyProposal = { ...improved, routes: [{ technicianId: "a", serviceDate: DATE, stops: [stop("x", 0, "09:00", "09:45")] }], unresolvedAppointmentIds: ["y"] };
  assert.throws(() => checkProposal(partial, snapshot, DATE), /leaves appointments unresolved/);
  assert.doesNotThrow(() => checkProposal({ ...partial, decision: "NO_IMPROVEMENT" }, snapshot, DATE));
});

test("a commit receipt must be exactly the proposal: every covered technician-day once, and its stops as proposed", () => {
  const { days, routed } = checkDailyReceipt(receipt, improved, covered);
  assert.deepEqual(days, covered, "Sorted, the way the scheduler locks them");
  assert.deepEqual(routed, covered);
  assert.throws(() => checkDailyReceipt({ ...receipt, technicianDays: receipt.technicianDays.slice(1) }, improved, covered), /does not list every technician-day/);
  assert.throws(() => checkDailyReceipt(receipt, improved, covered.slice(0, 1)), /which the proposal does not cover/);
  const moved = receipt.assignments.map(item => item.appointmentId === "y" ? { ...item, plannedStart: at("10:15") } : item);
  assert.throws(() => checkDailyReceipt({ ...receipt, assignments: moved }, improved, covered), /places appointment y differently/);
  assert.throws(() => checkDailyReceipt({ ...receipt, assignments: receipt.assignments.slice(0, 1) }, improved, covered), /not the proposal's stops/);
  // The same instant written with another offset is the same placement.
  const utc = receipt.assignments.map(item => ({ ...item, plannedStart: new Date(item.plannedStart).toISOString() }));
  assert.doesNotThrow(() => checkDailyReceipt({ ...receipt, assignments: utc }, improved, covered));
});

test("changes list only appointments whose technician, order or start moves", () => {
  const baseline = snapshot.appointments.map(item => ({ appointmentId: item.id, technicianId: item.technicianId, sequence: item.sequence, plannedStart: new Date(item.plannedStart).toISOString() }));
  const changes = proposalChanges(baseline, improved);
  assert.deepEqual(changes.map(item => item.appointmentId), ["y"]);
  assert.deepEqual(changes[0] && { from: changes[0].fromTechnicianId, to: changes[0].toTechnicianId, sequence: changes[0].toSequence }, { from: "b", to: "a", sequence: 1 });
  assert.throws(() => proposalChanges(baseline.slice(0, 1), improved), /no baseline placement/);
});

test("stored proposal JSON is validated before use", () => {
  const baseline = [{ appointmentId: "x", technicianId: "a", sequence: 0, plannedStart: "2026-10-12T14:00:00.000Z" }];
  assert.equal(storedProposal.safeParse({ proposal: improved, technicianDays: covered, baseline }).success, true);
  assert.equal(storedProposal.safeParse({ proposal: { ...improved, overtimeMinutes: 5 }, technicianDays: covered, baseline }).success, false);
  assert.equal(storedProposal.safeParse({ proposal: improved, technicianDays: [], baseline }).success, false, "A proposal always covers a technician-day");
  assert.equal(storedProposal.safeParse({ proposal: improved, technicianDays: covered, baseline: null }).success, false);
});

test("a STALE refusal keeps its changed technician-days, and only a STALE refusal may carry them", () => {
  const changed = [{ technicianId: "a", serviceDate: DATE, lastModified: "2026-10-11T21:04:17.123456Z" }];
  assert.equal(problem.safeParse({ error: "STALE", message: "Technician-day changed", changed }).success, true);
  assert.equal(problem.safeParse({ error: "STALE", message: "Technician-day changed" }).success, false);
  assert.equal(problem.safeParse({ error: "BUSY", message: "Busy", changed }).success, false);
  assert.equal(problem.safeParse({ error: "BUSY", message: "Busy" }).success, true);
});
