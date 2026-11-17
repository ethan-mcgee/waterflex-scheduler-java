import { test } from "node:test";
import assert from "node:assert/strict";
import { bufferedMinutes, scheduleDriveOf } from "./apiScheduleDriveCore";
import { driveLabel, driveMinutes, scheduleDrive, visitDrives, type ScheduleDrive } from "./scheduleDrive";
import type { PublicSnapshot } from "./publicApi";
import type { RouteGeometry } from "./schedulerApi";

const DATE = "2026-10-14";
const location = (lat: number) => ({ lat, lng: -95.9 });
const window = { start: `${DATE}T14:00:00.000Z`, end: `${DATE}T18:00:00.000Z` };
const technicianDay = (technicianId: string) => ({ technicianId, serviceDate: DATE, lastModified: "2026-10-01T00:00:00.000Z",
  shift: { start: `${DATE}T13:00:00.000Z`, end: `${DATE}T22:00:00.000Z` }, absences: [], start: location(41.1), end: location(41.2), maxPaidMinutes: 540 });
const appointment = (id: string, technicianId: string, sequence: number) => ({ id, technicianId, serviceDate: DATE, serviceId: "svc", durationMinutes: 45,
  window, location: { address: { line1: "1 Main St", city: "Omaha", state: "NE", postalCode: "68102" } }, sequence, plannedStart: `${DATE}T15:00:00.000Z` });
// A 15 percent travel buffer plus 2 minutes: a 600-second leg is allowed ceil(690 / 60) + 2 = 14 minutes.
const snapshot: PublicSnapshot = { metroId: "omaha", timeZone: "America/Chicago",
  rates: { regularHourly: "20", overtimeHourly: "30", mileagePerMile: "0.5", travelBufferPct: "0.15", travelBufferMinutes: 2 },
  policy: { fairnessBudget: "0.02", dailySearch: "TABU_KOPT", costSearchSeconds: 10, fairnessSearchSeconds: 5, bookingDeadlineMs: 5000, regularWindowThreshold: 2 },
  technicians: [{ id: "a", qualifications: [] }, { id: "b", qualifications: [] }, { id: "c", qualifications: [] }],
  technicianDays: [technicianDay("a"), technicianDay("b"), technicianDay("c")],
  appointments: [appointment("x", "a", 0), appointment("y", "a", 1), appointment("w", "a", 2), appointment("z", "c", 0)] };
const leg = (segment: number, legIndex: number, seconds = 600) =>
  ({ segment, legIndex, seconds, meters: 3000, coordinates: [[-95.9, 41.1], [-95.8, 41.3]] as Array<[number, number]> });
const stop = (appointmentId: string, sequence: number) => ({ appointmentId, sequence, lat: 41.3, lng: -95.8, source: "HOST" as const });
/** `a` works two stretches around an absence: x alone, then y and w. `b` has no visits; `c` could not be located. */
const drawn = (legs = [leg(0, 0), leg(0, 1, 300), leg(1, 0), leg(1, 1, 120), leg(1, 2, 900)]): RouteGeometry => ({ routingIdentity: "omaha-map-v7",
  skippedTechnicianDays: [{ technicianId: "c", serviceDate: DATE, reason: "LOCATION_UNRESOLVED", message: "Stop z has no location." }],
  routes: [{ technicianId: "a", serviceDate: DATE, stops: [stop("w", 2), stop("x", 0), stop("y", 1)], legs },
    { technicianId: "b", serviceDate: DATE, stops: [], legs: [] }] });
const anchors = () => ({ departure: "DEPOT" as const, returnTo: "HOME" as const });

test("a leg's minutes are the scheduler's: road seconds raised by the travel buffer, rounded up, plus its fixed minutes", () => {
  assert.equal(bufferedMinutes(600, "0", 0), 10);
  assert.equal(bufferedMinutes(601, "0", 0), 11, "A started minute counts");
  assert.equal(bufferedMinutes(600, "0.15", 2), 14);
  assert.equal(bufferedMinutes(0, "0.15", 2), 2);
  // 6000 seconds at 1 percent is exactly 101 minutes; floating point would make it 101.00000000000001 and round to 102.
  assert.equal(bufferedMinutes(6000, "0.01", 0), 101);
  assert.throws(() => bufferedMinutes(-1, "0", 0), /Invalid travel buffer inputs/);
  assert.throws(() => bufferedMinutes(60, "-0.1", 0), /Invalid travel buffer percentage/);
});

test("the drawing becomes each technician's stretches, with stops shared out in sequence order and minutes allowed per leg", () => {
  const drive = scheduleDriveOf(DATE, snapshot, drawn(), anchors);
  assert.deepEqual(scheduleDrive.parse(drive), drive, "The page accepts what the server sends");
  assert.deepEqual(drive.technicians.map(item => item.technicianId), ["a"], "A day with no visits has nothing to draw");
  assert.deepEqual(drive.technicians[0]?.segments.map(item => item.appointmentIds), [["x"], ["y", "w"]]);
  assert.deepEqual(drive.technicians[0]?.segments[1]?.legs.map(item => item.minutes), [14, 5, 20]);
  assert.deepEqual(drive.skipped, [{ technicianId: "c", message: "Stop z has no location." }]);
});

test("a drawing that does not match the day is refused, never guessed around", () => {
  assert.throws(() => scheduleDriveOf(DATE, snapshot, drawn([leg(0, 0), leg(0, 2), leg(1, 0), leg(1, 1), leg(1, 2)]), anchors), /missing or repeated legs/);
  assert.throws(() => scheduleDriveOf(DATE, snapshot, drawn([leg(0, 0), leg(0, 1), leg(0, 2), leg(0, 3), leg(0, 4)]), anchors), /more legs than stops/);
  assert.throws(() => scheduleDriveOf(DATE, snapshot, drawn([leg(0, 0), leg(0, 1), leg(1, 0), leg(1, 1)]), anchors), /stops outside every stretch/);
  assert.throws(() => scheduleDriveOf("2026-10-15", snapshot, drawn(), anchors), /drew a on 2026-10-14/);
});

test("each visit carries the drive after it, and the first visit of a stretch also the drive from the start point", () => {
  const drives = visitDrives(scheduleDriveOf(DATE, snapshot, drawn(), anchors));
  assert.deepEqual(drives.get("x"), { before: { minutes: 14, from: "DEPOT" }, after: { minutes: 8, to: "HOME" } });
  assert.deepEqual(drives.get("y"), { before: { minutes: 14, from: "DEPOT" }, after: { minutes: 5, to: "NEXT" } });
  assert.deepEqual(drives.get("w"), { before: null, after: { minutes: 20, to: "HOME" } });
  assert.equal(drives.get("z"), undefined, "A visit the scheduler could not draw has no drive, not a zero one");
  assert.deepEqual(driveMinutes(scheduleDriveOf(DATE, snapshot, drawn(), anchors)), new Map([["a", 61]]));
});

test("a drive band names where it goes", () => {
  assert.equal(driveLabel(17, { from: "DEPOT" }), "17 min from depot");
  assert.equal(driveLabel(12, { to: "NEXT" }), "12 min to next stop");
  assert.equal(driveLabel(15, { to: "HOME" }), "15 min to home");
});

test("the page refuses a stretch whose legs do not match its stops", () => {
  const broken: ScheduleDrive = { serviceDate: DATE, skipped: [], technicians: [{ technicianId: "a", departure: "DEPOT", returnTo: "HOME",
    segments: [{ appointmentIds: ["x"], legs: [{ seconds: 600, meters: 3000, minutes: 10 }] }] }] };
  assert.equal(scheduleDrive.safeParse(broken).success, false);
});
