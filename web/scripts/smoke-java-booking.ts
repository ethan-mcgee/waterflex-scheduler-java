import { z } from "zod";
import { durableSearchStatus, offersResponse, confirmation, selection, success, required } from "../lib/contracts";
import { PrismaClient } from "@prisma/client";
import { randomUUID } from "node:crypto";
import assert from "node:assert/strict";
import { initialAvailability } from "../lib/technicianAvailability";
import { technicianColor } from "../lib/technicianColor";
import { tomorrowInTz, addCalendarDays } from "../lib/date";

const prisma = new PrismaClient();
const base = process.env.SCHEDULER_TEST_URL ?? "http://127.0.0.1:18000";
const expectedLimit = 1;
const previousBase = process.env.SCHEDULER_PREVIOUS_LIMIT_URL;
const database = new URL(z.string().parse(process.env.DATABASE_URL));
assert.equal(database.pathname, "/waterflex_test", "Booking smoke requires isolated waterflex_test data");
const suffix = randomUUID();
const legacyReservationFlag = process.env.SCHEDULER_RESERVATIONS_TEST === "true";

async function post<T>(schema: z.ZodType<T>, path: string, body: unknown, server = base) {
  const response = await fetch(`${server}${path}`, {
    method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body),
  });
  const payload = schema.parse(await response.json());
  assert.equal(response.status, 200, `${path}: ${JSON.stringify(payload)}`);
  return payload;
}

async function main() {
  // Only the fixture technician can qualify, so seeded Omaha routes cannot affect offers.
  const metro = await prisma.metro.create({ data: { id: `smoke-metro-${suffix}`, name: "Booking fixture", timezone: "America/Chicago" } });
  const dealership = await prisma.dealership.create({ data: { name: "Booking dealership" } });
  const depot = await prisma.depot.create({ data: { metroId: metro.id, dealershipId: dealership.id, name: "Booking depot", lat: 43.735, lng: 7.420,
    endpointPolicies: { create: { effectiveDate: new Date("1900-01-01T00:00:00Z"), departure: "HOME", returnTo: "HOME" } } } });
  const service = await prisma.serviceCatalog.create({ data: { code: `BOOK_${suffix}`, name: "Booking fixture", estDurationMin: 50 } });
  const techId = `smoke-tech-${suffix}`;
  const customerId = `smoke-customer-${suffix}`;
  const addressId = `smoke-address-${suffix}`;
  const jobId = `smoke-job-${suffix}`;
  const otherJobId = `smoke-other-job-${suffix}`;
  try {
    await prisma.technician.create({ data: {
      id: techId, name: "Monaco fixture technician", color: technicianColor(techId), availabilityVersions: initialAvailability(480, 1020),
      depotAssignments: { create: { depotId: depot.id, effectiveDate: new Date("1900-01-01T00:00:00Z") } },
      homeLat: 43.735, homeLng: 7.420, shiftStartMin: 480, shiftEndMin: 1020,
      maxDailyMinutes: 600, maxOvertimeMinutes: 60,
      qualifications: { create: { serviceId: service.id } },
    } });
    await prisma.customer.create({ data: { id: customerId, firstName: "Smoke", lastName: "Test", email: "smoke@example.invalid", phone: "0000000000" } });
    await prisma.address.create({ data: { id: addressId, customerId, line1: "Fixture address", city: "Monaco", state: "MC", postalCode: "98000", lat: 43.748, lng: 7.438 } });
    await prisma.job.create({ data: { id: jobId, customerId, addressId, serviceId: service.id, durationMin: 50, bookingRequestId: suffix } });
    await prisma.job.create({ data: { id: otherJobId, customerId, addressId, serviceId: service.id, durationMin: 50, bookingRequestId: `other-${suffix}` } });

    await prisma.address.update({ where: { id: addressId }, data: { lat: null } });
    const missingLocation = await fetch(`${base}/v1/offers`, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ jobId }) });
    assert.equal(missingLocation.status, 422);
    assert.equal(await prisma.slotHold.count({ where: { jobId } }), 0);
    await prisma.address.update({ where: { id: addressId }, data: { lat: 43.748 } });
    const coordinateKey = "43.74800,7.43800";
    await prisma.roadRouteCache.upsert({ where: { originKey_destinationKey_profile_mapVersion: { originKey: coordinateKey, destinationKey: coordinateKey, profile: "car", mapVersion: "ci-monaco-omaha-car-v2" } },
      create: { originKey: coordinateKey, destinationKey: coordinateKey, profile: "car", mapVersion: "ci-monaco-omaha-car-v2", routable: true, seconds: null, meters: null },
      update: { routable: true, seconds: null, meters: null } });
    const expired = await post(offersResponse, "/v1/offers", { jobId, deadlineEpochMs: Date.now() - 1 });
    assert.equal(expired.search.outcome, "SEARCH_INCOMPLETE");
    assert.equal(await prisma.slotHold.count({ where: { jobId } }), 0);
    let unlock: () => void = () => { throw new Error("Lock was not initialized"); };
    let locked: () => void = () => { throw new Error("Lock signal was not initialized"); };
    const released = new Promise<void>(resolve => { unlock = resolve; });
    const acquired = new Promise<void>(resolve => { locked = resolve; });
    const held = prisma.$transaction(async tx => {
      await tx.$queryRaw`SELECT id FROM job WHERE id=${jobId} FOR UPDATE`;
      locked();
      await released;
    }, { timeout: 10000 });
    await acquired;
    try {
      const started = performance.now();
      const blocked = await post(offersResponse, "/v1/offers", { jobId, deadlineEpochMs: Date.now() + 1500 });
      assert.ok(["SEARCH_INCOMPLETE", "SERVICE_BUSY"].includes(blocked.search.outcome));
      assert.equal(blocked.search.prescribedSearchCompleted, false);
      assert.equal(blocked.search.retryable, true);
      assert.ok(performance.now() - started < 4000, "The database lock must not consume an unbounded wait");
    } finally { unlock(); await held; }
    assert.equal(await prisma.slotHold.count({ where: { jobId } }), 0, "Expired lock wait must not create late reservations");
    const offered = await post(offersResponse, "/v1/offers", { jobId });
    assert.equal(offered.jobId, jobId);
    assert.equal(offered.search.outcome, "AVAILABLE");
    assert.equal(offered.search.prescribedSearchCompleted, true);
    assert.equal(await prisma.bookingOffer.count({ where: { jobId, overtimeAuthorized: true } }), 0,
      "Incomplete scarcity search cannot authorize overtime");
    const repairedCache = await prisma.roadRouteCache.findUniqueOrThrow({ where: { originKey_destinationKey_profile_mapVersion: { originKey: coordinateKey, destinationKey: coordinateKey, profile: "car", mapVersion: "ci-monaco-omaha-car-v2" } } });
    assert.notEqual(repairedCache.seconds, null); assert.notEqual(repairedCache.meters, null);
    await prisma.address.update({ where: { id: addressId }, data: { lat: null } });
    const incompleteSelection = await fetch(`${base}/v1/offers/select`, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ jobId, offerId: required(offered.offers[0]).offerId }) });
    assert.equal(incompleteSelection.status, 409);
    assert.equal(await prisma.slotHold.count({ where: { jobId, releasedAt: null } }), offered.offers.length);
    assert.equal(await prisma.appointment.count({ where: { jobId } }), 0);
    await prisma.address.update({ where: { id: addressId }, data: { lat: 43.748 } });
    assert.equal(offered.offers.length, expectedLimit);
    assert.equal(new Set(offered.offers.map(offer => offer.windowStart)).size, expectedLimit, "Duplicate windows must not consume the limit");
    assert.equal(await prisma.slotHold.count({ where: { jobId, releasedAt: null } }), offered.offers.length, "Every offer reserves capacity");
    const reused = await post(offersResponse, "/v1/offers", { jobId });
    assert.deepEqual(reused.offers.map((offer: { offerId: string }) => offer.offerId).sort(), offered.offers.map((offer: { offerId: string }) => offer.offerId).sort());
    const foreignRelease = await fetch(`${base}/v1/offers/release`, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ jobId: otherJobId, offerId: required(offered.offers[0]).offerId }) });
    assert.equal(foreignRelease.status, 409);
    assert.equal(await prisma.slotHold.count({ where: { jobId, releasedAt: null } }), offered.offers.length);
    assert.equal((await post(success, "/v1/offers/release", { jobId, offerId: required(offered.offers[0]).offerId })).success, true);
    assert.equal(await prisma.slotHold.count({ where: { jobId, releasedAt: null } }), 0, "Start over releases every sibling immediately");
    assert.equal((await post(success, "/v1/offers/release", { jobId, offerId: required(offered.offers[0]).offerId })).success, true);
    const refreshed = await post(offersResponse, "/v1/offers", { jobId, refresh: true });
    assert.equal(refreshed.offers.length, expectedLimit);
    assert.notEqual(required(refreshed.offers[0]).offerId, required(offered.offers[0]).offerId);
    assert.equal(await prisma.slotHold.count({ where: { jobId, releasedAt: null } }), refreshed.offers.length);
    assert.equal((await post(success, "/v1/offers/release", { jobId, offerId: required(offered.offers[0]).offerId })).success, true);
    assert.equal(await prisma.slotHold.count({ where: { jobId, releasedAt: null } }), refreshed.offers.length, "Stale release preserves the newer set");
    const staleSelection = await fetch(`${base}/v1/offers/select`, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ jobId, offerId: required(offered.offers[0]).offerId }) });
    assert.equal(staleSelection.status, 409);
    const selected = await post(selection, "/v1/offers/select", { jobId, offerId: required(refreshed.offers[0]).offerId });
    assert.ok(selected.holdId);
    assert.ok(selected.appointmentId, "Selection commits the appointment");
    assert.equal(await prisma.slotHold.count({ where: { jobId, releasedAt: null } }), 0);
    const confirmed = await post(confirmation, "/v1/holds/confirm", { holdId: selected.holdId });
    assert.equal(confirmed.appointmentId, selected.appointmentId);
    assert.equal(confirmed.windowStart, required(refreshed.offers[0]).windowStart);
    assert.equal(confirmed.windowEnd, required(refreshed.offers[0]).windowEnd);
    const retry = await post(confirmation, "/v1/holds/confirm", { holdId: selected.holdId });
    assert.equal(retry.appointmentId, confirmed.appointmentId);
    assert.equal(await prisma.appointment.count({ where: { jobId } }), 1);
    const repeatedSelection = await post(selection, "/v1/offers/select", { jobId, offerId: required(refreshed.offers[0]).offerId });
    assert.equal(repeatedSelection.appointmentId, selected.appointmentId);
    const scheduledRelease = await fetch(`${base}/v1/offers/release`, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ jobId, offerId: required(refreshed.offers[0]).offerId }) });
    assert.equal(scheduledRelease.status, 409);
    assert.equal(await prisma.appointment.count({ where: { jobId, cancelledAt: null } }), 1);
    if (refreshed.offers.length > 1) {
      const differentSelection = await fetch(`${base}/v1/offers/select`, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ jobId, offerId: required(refreshed.offers[1]).offerId }) });
      assert.equal(differentSelection.status, 409);
    }
    const booked = await prisma.appointment.findUniqueOrThrow({ where: { id: selected.appointmentId } });
    const survivorStart = new Date(booked.windowEnd.getTime());
    await prisma.appointment.create({ data: { jobId: otherJobId, technicianId: techId, serviceDate: booked.serviceDate,
      windowStart: survivorStart, windowEnd: new Date(survivorStart.getTime() + 7200000), plannedStart: survivorStart,
      plannedEnd: new Date(survivorStart.getTime() + 3000000), sequence: 1 } });
    await prisma.job.update({ where: { id: otherJobId }, data: { status: "SCHEDULED" } });
    // Deliberately bypass edit guards to cover damaged persisted scheduling facts.
    await prisma.technicianShiftOverride.create({ data: { technicianId: techId, serviceDate: booked.serviceDate, available: false } });
    const missingShiftCancellation = await fetch(`${base}/v1/appointments/cancel`, { method: "POST", headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ appointment_id: selected.appointmentId, reason: "Missing remaining shift" }) });
    assert.equal(missingShiftCancellation.status, 409);
    assert.equal((await prisma.appointment.findUniqueOrThrow({ where: { id: selected.appointmentId } })).cancelledAt, null,
      "Failed remaining-route validation rolls back cancellation");
    assert.equal((await prisma.job.findUniqueOrThrow({ where: { id: jobId } })).status, "SCHEDULED");
    await prisma.technicianShiftOverride.deleteMany({ where: { technicianId: techId } });
    await prisma.technician.update({ where: { id: techId }, data: { maxDailyMinutes: 1 } });
    const infeasibleCancellation = await fetch(`${base}/v1/appointments/cancel`, { method: "POST", headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ appointment_id: selected.appointmentId, reason: "Infeasible remaining route" }) });
    assert.equal(infeasibleCancellation.status, 409);
    assert.equal((await prisma.appointment.findUniqueOrThrow({ where: { id: selected.appointmentId } })).cancelledAt, null);
    await prisma.technician.update({ where: { id: techId }, data: { maxDailyMinutes: 600 } });
    const cancelled = await post(success.extend({ alreadyCancelled: z.boolean() }), "/v1/appointments/cancel", { appointment_id: selected.appointmentId, reason: "Fixture cancellation" });
    assert.equal(cancelled.success, true);
    const cancelledAgain = await post(success.extend({ alreadyCancelled: z.boolean() }), "/v1/appointments/cancel", { appointment_id: selected.appointmentId, reason: "Fixture cancellation" });
    assert.equal(cancelledAgain.alreadyCancelled, true);
    assert.ok((await prisma.appointment.findUniqueOrThrow({ where: { id: selected.appointmentId } })).cancelledAt);
    const survivor = await prisma.appointment.findFirstOrThrow({ where: { jobId: otherJobId, cancelledAt: null } });
    await post(success, "/v1/appointments/cancel", { appointment_id: survivor.id, reason: "Fixture cleanup before availability cases" });
    if (previousBase) {
      const retainedJob = await prisma.job.create({ data: { customerId, addressId, serviceId: service.id, durationMin: 50 } });
      // Historical configuration values cannot restore multiple offers after algorithm rollback.
      const original = await post(offersResponse, "/v1/offers", { jobId: retainedJob.id }, previousBase);
      assert.equal(original.offers.length, 1);
      const retained = await post(offersResponse, "/v1/offers", { jobId: retainedJob.id });
      assert.deepEqual(retained.offers.map(offer => offer.offerId).sort(), original.offers.map(offer => offer.offerId).sort());
      assert.equal(await prisma.slotHold.count({ where: { jobId: retainedJob.id, releasedAt: null } }), 1);
      const chosen = await post(selection, "/v1/offers/select", { jobId: retainedJob.id, offerId: required(original.offers[0]).offerId });
      assert.equal(await prisma.slotHold.count({ where: { jobId: retainedJob.id, releasedAt: null } }), 0);
      await post(success, "/v1/appointments/cancel", { appointment_id: chosen.appointmentId, reason: "Limit change fixture" });
    }
    // Use a separate pending job to verify refresh replaces a larger set and releases every old hold.
    const refreshJob = await prisma.job.create({ data: { customerId, addressId, serviceId: service.id, durationMin: 50 } });
    const beforeRefresh = await post(offersResponse, "/v1/offers", { jobId: refreshJob.id }, previousBase ?? base);
    const afterRefresh = await post(offersResponse, "/v1/offers", { jobId: refreshJob.id, refresh: true });
    assert.equal(afterRefresh.offers.length, expectedLimit);
    assert.equal(await prisma.slotHold.count({ where: { jobId: refreshJob.id, releasedAt: null } }), expectedLimit);
    assert.equal(await prisma.slotHold.count({ where: { offerToken: { in: beforeRefresh.offers.map(offer => offer.offerId) }, releasedAt: null } }), 0);
    await post(success, "/v1/offers/release", { jobId: refreshJob.id, offerId: required(afterRefresh.offers[0]).offerId });

    const searchToken = randomUUID();
    const startedSearch = await post(durableSearchStatus, "/v1/booking-searches", { jobId: refreshJob.id, requestId: searchToken, refresh: false });
    const duplicate = await post(durableSearchStatus, "/v1/booking-searches", { jobId: refreshJob.id, requestId: searchToken, refresh: false });
    assert.equal(duplicate.id, startedSearch.id);
    let durable = duplicate;
    for (let attempt = 0; attempt < 100 && ["QUEUED", "RUNNING"].includes(durable.state); attempt++) {
      await new Promise(resolve => setTimeout(resolve, 100));
      durable = durableSearchStatus.parse(await (await fetch(`${base}/v1/booking-searches/${searchToken}?jobId=${encodeURIComponent(refreshJob.id)}`)).json());
    }
    assert.equal(durable.state, "AVAILABLE"); assert.equal(durable.offers.length, 1);
    assert.ok(new Date(required(durable.offers[0]).expiresAt).getTime() - Date.now() > 590000, "Offer expiry starts at publication");
    const cancelledSearch = durableSearchStatus.parse(await (await fetch(`${base}/v1/booking-searches/${searchToken}?jobId=${encodeURIComponent(refreshJob.id)}`, { method: "DELETE" })).json());
    assert.equal(cancelledSearch.state, "CANCELLED");
    for (let attempt = 0; attempt < 100 && await prisma.slotHold.count({ where: { jobId: refreshJob.id, releasedAt: null } }); attempt++)
      await new Promise(resolve => setTimeout(resolve, 100));
    assert.equal(await prisma.slotHold.count({ where: { jobId: refreshJob.id, releasedAt: null } }), 0, "Cancelled publication releases reserved capacity");

    // Exactly one eligible date with a four-hour shift produces one distinct window.
    await prisma.technicianAvailabilityDay.updateMany({ where: { version: { technicianId: techId } }, data: { available: false, shiftStartMin: null, shiftEndMin: null } });
    await prisma.technicianShiftOverride.create({ data: { technicianId: techId, serviceDate: new Date(required(offered.offers[0]).date), available: true, shiftStartMin: 480, shiftEndMin: 720 } });
    const scarce = await post(offersResponse, "/v1/offers", { jobId: refreshJob.id, refresh: true });
    assert.equal(scarce.offers.length, 1);
    assert.equal(await prisma.slotHold.count({ where: { jobId: refreshJob.id, releasedAt: null } }), 1);
    await post(success, "/v1/offers/release", { jobId: refreshJob.id, offerId: required(scarce.offers[0]).offerId });
    await prisma.technicianShiftOverride.deleteMany({ where: { technicianId: techId } });
    const unavailable = await post(offersResponse, "/v1/offers", { jobId: refreshJob.id, refresh: true });
    assert.equal(unavailable.offers.length, 0);
    assert.equal(await prisma.slotHold.count({ where: { jobId: refreshJob.id, releasedAt: null } }), 0);
    let cursor = tomorrowInTz("America/Chicago");
    for (let weekdays = 0; weekdays < 10; cursor = addCalendarDays(cursor, 1))
      if (![0, 6].includes(new Date(`${cursor}T00:00:00Z`).getUTCDay())) weekdays++;
    while ([0, 6].includes(new Date(`${cursor}T00:00:00Z`).getUTCDay())) cursor = addCalendarDays(cursor, 1);
    const firstOverflow = cursor;
    cursor = addCalendarDays(cursor, 1);
    while ([0, 6].includes(new Date(`${cursor}T00:00:00Z`).getUTCDay())) cursor = addCalendarDays(cursor, 1);
    for (const date of [firstOverflow, cursor]) await prisma.technicianShiftOverride.create({ data: {
      technicianId: techId, serviceDate: new Date(`${date}T00:00:00Z`), available: true, shiftStartMin: 480, shiftEndMin: 720,
    } });
    const overflow = await post(offersResponse, "/v1/offers", { jobId: refreshJob.id, refresh: true });
    assert.equal(overflow.offers.length, 1);
    assert.equal(required(overflow.offers[0]).date, firstOverflow, "Use first feasible overflow weekday");
    assert.equal(new Date(required(overflow.offers[0]).windowEnd).getTime() - new Date(required(overflow.offers[0]).windowStart).getTime(), 4 * 3600000);
    await post(success, "/v1/offers/release", { jobId: refreshJob.id, offerId: required(overflow.offers[0]).offerId });
    console.log(`Java booking lifecycle, scarcity, and offer limit ${expectedLimit} passed (legacyReservationFlag=${legacyReservationFlag})`);
  } finally {
    await prisma.appointment.deleteMany({ where: { job: { customerId } } });
    await prisma.slotHold.deleteMany({ where: { job: { customerId } } });
    await prisma.reservationArrangement.deleteMany({ where: { metroId: metro.id } });
    await prisma.bookingOffer.deleteMany({ where: { job: { customerId } } });
    await prisma.bookingOfferSet.deleteMany({ where: { job: { customerId } } });
    await prisma.job.deleteMany({ where: { customerId } });
    await prisma.address.deleteMany({ where: { id: addressId } });
    await prisma.customer.deleteMany({ where: { id: customerId } });
    await prisma.technicianQualification.deleteMany({ where: { technicianId: techId } });
    await prisma.technicianShiftOverride.deleteMany({ where: { technicianId: techId } });
    await prisma.scheduleDay.deleteMany({ where: { technicianId: techId } });
    await prisma.technician.deleteMany({ where: { id: techId } });
    await prisma.serviceCatalog.delete({ where: { id: service.id } });
    await prisma.depot.delete({ where: { id: depot.id } });
    await prisma.dealership.delete({ where: { id: dealership.id } });
    await prisma.metro.delete({ where: { id: metro.id } });
    await prisma.$disconnect();
  }
}

main().catch((error) => { console.error(error); process.exitCode = 1; });
