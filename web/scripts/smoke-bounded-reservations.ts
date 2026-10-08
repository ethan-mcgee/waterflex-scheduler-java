import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { PrismaClient } from "@prisma/client";
import { z } from "zod";
import { offersResponse, selection, required } from "../lib/contracts";
import { addCalendarDays, localMidnightUtc, tomorrowInTz } from "../lib/date";
import { technicianColor } from "../lib/technicianColor";
import { currentRouteTiming } from "../lib/currentRouteTiming";
import { isDispatchGeometry } from "../lib/dispatchGeometry";
import { DEFAULT_CLIENT_ID } from "../lib/clients";

const prisma = new PrismaClient();
const base = process.env.SCHEDULER_TEST_URL ?? "http://127.0.0.1:18000";
const confirmationBase = process.env.SCHEDULER_CONFIRM_URL ?? base;
const prefix = `bounded-${randomUUID()}`;
const cancelPending = process.env.SCHEDULER_CANCEL_PENDING_TEST === "true";
const ids = { metro: prefix, dealer: `${prefix}-dealer`, depot: `${prefix}-depot`, a: `${prefix}-a`, b: `${prefix}-b`,
  oldService: `${prefix}-old-service`, newService: `${prefix}-new-service`, customer: `${prefix}-customer`, address: `${prefix}-address`,
  oldJob: `${prefix}-old-job`, first: `${prefix}-first`, second: `${prefix}-second`, appointment: `${prefix}-appointment` };

async function search(jobId: string) {
  const started = performance.now();
  const response = await fetch(`${base}/v1/offers`, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ jobId }) });
  const body: unknown = await response.json();
  assert.equal(response.status, 200, JSON.stringify(body));
  return { ...offersResponse.parse(body), apiMs: performance.now() - started };
}

async function main() {
  assert.equal(new URL(process.env.DATABASE_URL ?? "").pathname, "/waterflex_test");
  let date = tomorrowInTz("America/Chicago");
  while ([0, 6].includes(new Date(`${date}T00:00:00Z`).getUTCDay())) date = addCalendarDays(date, 1);
  const serviceDate = new Date(`${date}T00:00:00Z`);
  const start = new Date(localMidnightUtc(date, "America/Chicago").getTime() + 9 * 3600000);
  const end = new Date(start.getTime() + 2 * 3600000);
  try {
    await prisma.metro.create({ data: { id: ids.metro, name: "Bounded booking fixture", timezone: "America/Chicago" } });
    await prisma.dealership.create({ data: { clientId: DEFAULT_CLIENT_ID, id: ids.dealer, name: "Bounded booking fixture" } });
    await prisma.depot.create({ data: { id: ids.depot, metroId: ids.metro, dealershipId: ids.dealer, name: "Bounded fixture", lat: 43.735, lng: 7.420,
      endpointPolicies: { create: { effectiveDate: new Date("1900-01-01"), departure: "HOME", returnTo: "HOME" } } } });
    for (const id of [ids.oldService, ids.newService]) await prisma.serviceCatalog.create({ data: { id, code: id, name: id, estDurationMin: 60 } });
    for (const id of [ids.a, ids.b]) await prisma.technician.create({ data: { clientId: DEFAULT_CLIENT_ID, id, name: id, color: technicianColor(id), homeLat: 43.735, homeLng: 7.420,
      shiftStartMin: 540, shiftEndMin: 780, maxDailyMinutes: 120, maxOvertimeMinutes: 0,
      availabilityVersions: { create: { effectiveDate: new Date("1900-01-01"), days: { create: Array.from({ length: 7 }, (_, dayOfWeek) => ({
        dayOfWeek, available: false, shiftStartMin: null, shiftEndMin: null,
      })) } } },
      shiftOverrides: { create: { serviceDate, available: true, shiftStartMin: 540, shiftEndMin: 780 } },
      depotAssignments: { create: { depotId: ids.depot, effectiveDate: new Date("1900-01-01") } },
      qualifications: { create: (id === ids.a ? [ids.oldService, ids.newService] : [ids.oldService]).map(serviceId => ({ serviceId })) },
    } });
    await prisma.customer.create({ data: { clientId: DEFAULT_CLIENT_ID, id: ids.customer, firstName: "Bounded", lastName: "Fixture", email: "bounded@example.invalid", phone: "0000000000" } });
    await prisma.address.create({ data: { id: ids.address, customerId: ids.customer, line1: "Fixture", city: "Monaco", state: "MC", postalCode: "98000", lat: 43.748, lng: 7.438 } });
    for (const id of [ids.oldJob, ids.first, ids.second]) await prisma.job.create({ data: { id, customerId: ids.customer, addressId: ids.address,
      serviceId: id === ids.oldJob ? ids.oldService : ids.newService, durationMin: 60, status: id === ids.oldJob ? "SCHEDULED" : "PENDING", bookingRequestId: id } });
    await prisma.appointment.create({ data: { id: ids.appointment, jobId: ids.oldJob, technicianId: ids.a, serviceDate, windowStart: start, windowEnd: end,
      plannedStart: new Date(start.getTime() + 15 * 60000), plannedEnd: new Date(start.getTime() + 75 * 60000), sequence: 0 } });

    const results = await Promise.all([search(ids.first), search(ids.second)]);
    const winners = results.filter(result => result.search.outcome === "AVAILABLE");
    assert.equal(winners.length, 1, JSON.stringify(results));
    const winner = required(winners[0]);
    const loser = required(results.find(result => result.jobId !== winner.jobId));
    assert.ok(["NO_CANDIDATE_FOUND", "SEARCH_INCOMPLETE", "SCHEDULE_CONFLICT"].includes(loser.search.outcome), JSON.stringify(loser));
    assert.equal(winner.offers.length, 1);
    assert.equal(winner.search.prescribedSearchCompleted, true);
    assert.equal(await prisma.slotHold.count({ where: { jobId: { in: [ids.first, ids.second] }, releasedAt: null } }), 1);
    assert.equal((await prisma.appointment.findUniqueOrThrow({ where: { id: ids.appointment } })).technicianId, ids.a,
      "Offering must leave the confirmed assignment unchanged");
    const saved = await prisma.bookingOfferSet.findFirstOrThrow({ where: { jobId: winner.jobId, supersededAt: null } });
    const diagnostics = z.object({ completed: z.literal(true), distinctRegularWindows: z.literal(1), overtimeAuthorized: z.literal(false),
      offerSources: z.record(z.string(), z.enum(["INSERTION", "REARRANGEMENT"])) }).parse(saved.searchDiagnostics);
    assert.equal(diagnostics.offerSources[required(winner.offers[0]).offerId], "REARRANGEMENT");
    assert.ok(await prisma.reservationDependency.count({ where: { technicianId: ids.b, serviceId: ids.oldService } }) > 0);
    if (cancelPending) {
      const cancellation = await fetch(`${confirmationBase}/v1/appointments/cancel`, { method: "POST", headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ appointment_id: ids.appointment, reason: "Cancellation while a rearranged offer is reserved" }) });
      assert.equal(cancellation.status, 200, await cancellation.text());
      assert.equal(await prisma.slotHold.count({ where: { jobId: winner.jobId, releasedAt: null } }), 1);
      assert.ok((await prisma.appointment.findUniqueOrThrow({ where: { id: ids.appointment } })).cancelledAt);
    }
    const response = await fetch(`${confirmationBase}/v1/offers/select`, { method: "POST", headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ jobId: winner.jobId, offerId: required(winner.offers[0]).offerId }) });
    const body: unknown = await response.json(); assert.equal(response.status, 200, JSON.stringify(body));
    const selected = selection.parse(body);
    const moved = await prisma.appointment.findUniqueOrThrow({ where: { id: ids.appointment } });
    assert.equal(moved.technicianId, cancelPending ? ids.a : ids.b);
    assert.equal(moved.windowStart.getTime(), start.getTime()); assert.equal(moved.windowEnd.getTime(), end.getTime());
    assert.equal((await prisma.appointment.findUniqueOrThrow({ where: { id: selected.appointmentId } })).technicianId, ids.a);
    assert.equal(await prisma.slotHold.count({ where: { jobId: winner.jobId, releasedAt: null } }), 0);
    for (const technicianId of [ids.a, ids.b]) {
      const day = await prisma.scheduleDay.findUniqueOrThrow({ where: { technicianId_serviceDate: { technicianId, serviceDate } } });
      const confirmed = await prisma.appointment.findMany({ where: { technicianId, serviceDate, cancelledAt: null } });
      assert.equal(currentRouteTiming(day.routeTiming, day.version, confirmed).status, "AVAILABLE");
    }
    const geometry = await fetch(`${confirmationBase}/v1/dispatch/geometry?metro_id=${encodeURIComponent(ids.metro)}&date=${date}`);
    const geometryBody: unknown = await geometry.json();
    assert.equal(geometry.status, 200, JSON.stringify(geometryBody));
    assert.ok(isDispatchGeometry(geometryBody, date, "current"));
    console.log(JSON.stringify({ result: "Bounded API rearrangement and concurrent reservation protection passed", cancelPending, outcomes: results.map(result => ({
      outcome: result.search.outcome, apiMs: Math.round(result.apiMs), completed: result.search.prescribedSearchCompleted,
    })) }));
  } finally {
    await prisma.reservationArrangement.deleteMany({ where: { metroId: ids.metro } });
    await prisma.appointment.deleteMany({ where: { jobId: { in: [ids.oldJob, ids.first, ids.second] } } });
    await prisma.slotHold.deleteMany({ where: { jobId: { in: [ids.first, ids.second] } } });
    await prisma.bookingOffer.deleteMany({ where: { jobId: { in: [ids.first, ids.second] } } });
    await prisma.bookingOfferSet.deleteMany({ where: { jobId: { in: [ids.first, ids.second] } } });
    await prisma.job.deleteMany({ where: { id: { in: [ids.oldJob, ids.first, ids.second] } } });
    await prisma.address.deleteMany({ where: { id: ids.address } }); await prisma.customer.deleteMany({ where: { id: ids.customer } });
    await prisma.scheduleDay.deleteMany({ where: { technicianId: { in: [ids.a, ids.b] } } });
    await prisma.technicianQualification.deleteMany({ where: { technicianId: { in: [ids.a, ids.b] } } });
    await prisma.technicianShiftOverride.deleteMany({ where: { technicianId: { in: [ids.a, ids.b] } } });
    await prisma.technician.deleteMany({ where: { id: { in: [ids.a, ids.b] } } });
    await prisma.serviceCatalog.deleteMany({ where: { id: { in: [ids.oldService, ids.newService] } } });
    await prisma.depot.deleteMany({ where: { id: ids.depot } }); await prisma.dealership.deleteMany({ where: { id: ids.dealer } });
    await prisma.metro.deleteMany({ where: { id: ids.metro } }); await prisma.$disconnect();
  }
}
main().catch(error => { console.error(error); process.exitCode = 1; });
