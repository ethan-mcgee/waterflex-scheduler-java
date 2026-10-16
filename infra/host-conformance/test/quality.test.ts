import assert from "node:assert/strict";
import { test } from "node:test";
import { HostStore, type MetroShape } from "../src/load/host.ts";
import { disturbance, pairedAgainst, qualityLines, summarizeThreshold, withReceipt, type QualityCase, type Receipt, type Searched } from "../src/load/quality.ts";
import { KIT_POLICY, KIT_RATES, type Snapshot } from "../src/snapshot.ts";

const DATE = "2026-10-12";
const at = (hour: number) => `${DATE}T${String(hour + 5).padStart(2, "0")}:00:00.000Z`;
const point = { lat: 41.25, lng: -96 };
const job = { id: "job", serviceId: "svc", durationMinutes: 60, location: point };

function day(): Snapshot {
  const visit = (id: string, technicianId: string, sequence: number, hour: number) => ({ id, technicianId, serviceDate: DATE, serviceId: "svc", durationMinutes: 45,
    window: { start: at(8), end: at(17) }, location: point, sequence, plannedStart: at(hour) });
  const technicianDay = (technicianId: string) => ({ technicianId, serviceDate: DATE, lastModified: "2026-10-01T00:00:00.000Z",
    shift: { start: at(8), end: at(17) }, absences: [], start: point, end: point, maxPaidMinutes: 540 });
  return { metroId: "metro", timeZone: "America/Chicago", rates: KIT_RATES, policy: KIT_POLICY,
    technicians: [{ id: "a", qualifications: ["svc"] }, { id: "b", qualifications: ["svc"] }], technicianDays: [technicianDay("a"), technicianDay("b")],
    appointments: [visit("a1", "a", 0, 9), visit("a2", "a", 1, 11), visit("b1", "b", 0, 9)] };
}

const assignment = (appointmentId: string, technicianId: string, sequence: number, hour: number) =>
  ({ appointmentId, technicianId, serviceDate: DATE, sequence, plannedStart: at(hour), plannedEnd: at(hour + 1) });

test("a receipt applied to a copy places the job and moves the visits it lists, leaving the original alone", () => {
  const before = day();
  const receipt: Receipt = { receiptId: "r", technicianDays: [],
    assignments: [assignment("a2", "b", 1, 11), assignment("job", "a", 1, 12)] };
  const after = withReceipt(before, receipt, job, { start: at(12), end: at(14) });
  assert.deepEqual(after.appointments.map(item => `${item.technicianId}:${item.id}:${item.sequence}`), ["a:a1:0", "a:job:1", "b:b1:0", "b:a2:1"]);
  assert.deepEqual(after.appointments.find(item => item.id === "job")?.window, { start: at(12), end: at(14) });
  assert.equal(before.appointments.find(item => item.id === "a2")?.technicianId, "a", "The host's own snapshot is unchanged");
  assert.deepEqual(disturbance(before, after, "job"), { reassignedVisits: 1, reorderedDays: 0 });
  const swapped = withReceipt(before, { ...receipt, assignments: [assignment("a2", "a", 0, 9), assignment("a1", "a", 1, 11), assignment("job", "b", 1, 12)] }, job, { start: at(12), end: at(14) });
  assert.deepEqual(disturbance(before, swapped, "job"), { reassignedVisits: 0, reorderedDays: 1 }, "Swapping two visits reorders their day");
  assert.throws(() => withReceipt(before, { ...receipt, assignments: [assignment("a1", "a", 0, 9)] }, job, { start: at(12), end: at(14) }), /does not place job/);
  assert.throws(() => withReceipt(before, { ...receipt, assignments: [assignment("ghost", "a", 0, 9), assignment("job", "a", 1, 12)] }, job,
    { start: at(12), end: at(14) }), /unknown appointment ghost/);
});

test("touching a technician-day stamps it newer without changing it", () => {
  const shape: MetroShape = { metroId: "metro", timeZone: "America/Chicago", serviceId: "svc", prefix: "t", technicians: 1, appointmentsPerDay: 2,
    dates: [DATE], rates: KIT_RATES, policy: KIT_POLICY };
  const host = new HostStore(shape, () => point, () => point, Date.parse("2026-10-09T12:00:00Z"));
  const before = host.snapshot([DATE]);
  const technicianDay = before.technicianDays[0];
  assert.ok(technicianDay !== undefined);
  host.touch([technicianDay]);
  const after = host.snapshot([DATE]);
  assert.ok(Date.parse(after.technicianDays[0]?.lastModified ?? "") > Date.parse(technicianDay.lastModified));
  assert.deepEqual(after.appointments, before.appointments);
  assert.throws(() => host.touch([{ technicianId: "nobody", serviceDate: DATE }]), /No technician-day/);
});

const offer = (serviceDate: string, hour: number) => ({ serviceDate, start: `${serviceDate}T${hour}:00:00Z`, end: `${serviceDate}T${hour + 2}:00:00Z` });
const searched = (threshold: number, offers: Searched["offers"], added: number | null, refinement = "COMPLETE"): Searched => ({ threshold, status: 201, ms: 100 * (threshold === 2 ? 3 : 6),
  refinement, searchComplete: true, incompleteDates: 0, offers,
  priced: added === null ? null : { beforeCostCents: 1000, afterCostCents: 1000 + added, addedCostCents: added, reassignedVisits: threshold === 2 ? 0 : 1, reorderedDays: 0 },
  pricingFailure: null });

test("thresholds are summarized and compared on the same cases", () => {
  const cases: QualityCase[] = [
    { index: 1, firstDate: "2026-10-12", dates: 13, order: [2, 1000],
      searched: [searched(2, [offer("2026-10-14", 9), offer("2026-10-15", 9)], 2500, "SHORTENED"), searched(1000, [offer("2026-10-13", 9), offer("2026-10-15", 9), offer("2026-10-15", 13)], 2300)] },
    { index: 2, firstDate: "2026-10-12", dates: 13, order: [1000, 2],
      searched: [searched(2, [offer("2026-10-14", 9)], 2400, "SHED"), searched(1000, [offer("2026-10-14", 9)], 2400)] },
    { index: 3, firstDate: "2026-10-12", dates: 13, order: [2, 1000],
      searched: [searched(2, [], null, "SHED"), { ...searched(1000, [], null), status: 503, ms: null, refinement: null, searchComplete: null, incompleteDates: null, pricingFailure: "search answered 503" }] },
  ];
  const low = summarizeThreshold(cases, 2);
  assert.deepEqual({ searches: low.searches, failed: low.failed, noOffer: low.noOffer, refinements: low.refinements, priced: low.priced },
    { searches: 3, failed: 0, noOffer: 1, refinements: { SHORTENED: 1, SHED: 2 }, priced: 2 });
  assert.equal(low.earliestLeadDays, 2, "Both searches with offers first offered two days after the horizon's first date");
  assert.deepEqual(low.addedCostCents, { mean: 2450, p50: 2400 });
  const high = summarizeThreshold(cases, 1000);
  assert.equal(high.failed, 1);
  assert.equal(high.distinctDatesPerSearch, 1.5);
  assert.deepEqual(pairedAgainst(cases, 2, 1000), { threshold: 1000, cases: 2, sameOffers: 1, moreOffers: 1, fewerOffers: 0, earlierFirstOffer: 1,
    laterFirstOffer: 0, costCompared: 2, cheaper: 1, dearer: 0, meanCostChangeCents: -100 }, "The failed search's case is left out of the comparison");
  const lines = qualityLines([2, 1000], cases);
  assert.equal(lines.find(line => line.startsWith("| 1000 | 2 |")), "| 1000 | 2 | 1 | 1 / 0 | 1 / 0 | 2 | 1 / 0 | -1.00 |");
  assert.throws(() => qualityLines([], cases), /No thresholds/);
});
