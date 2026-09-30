import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { randomUUID } from "node:crypto";
import { PrismaClient } from "@prisma/client";

const database = new URL(process.env.DATABASE_URL ?? "");
assert.equal(database.pathname, "/waterflex_test");
assert.equal(database.searchParams.get("schema"), "durable_contracts");
const prisma = new PrismaClient();
const rollback = new Error("Expected fixture rollback");
const prefix = `policy-migration-${randomUUID()}`;

async function main() {
  try {
    await prisma.$transaction(async tx => {
      const service = await tx.serviceCatalog.create({ data: { code: prefix, name: "Policy fixture", estDurationMin: 30 } });
      const customer = await tx.customer.create({ data: { firstName: "Policy", lastName: "Fixture", email: "fixture@example.invalid", phone: "0000000000" } });
      const address = await tx.address.create({ data: { customerId: customer.id, line1: "Fixture", city: "Omaha", state: "NE", postalCode: "68102", lat: 41.25, lng: -95.93 } });
      const tech = await tx.technician.create({ data: { name: prefix, color: "#059669", homeLat: 41.25, homeLng: -95.93,
        shiftStartMin: 480, shiftEndMin: 1020, maxDailyMinutes: 540, maxOvertimeMinutes: 0 } });
      const day = new Date("2030-01-07T00:00:00Z");
      const start = new Date("2030-01-07T15:00:00Z");
      const end = new Date("2030-01-07T17:00:00Z");
      const expiry = new Date(Date.now() + 600_000);
      const newJob = () => tx.job.create({ data: { customerId: customer.id, addressId: address.id, serviceId: service.id, durationMin: 30 } });
      const confirmed = await newJob();
      await tx.job.update({ where: { id: confirmed.id }, data: { status: "SCHEDULED" } });
      const appointment = await tx.appointment.create({ data: { jobId: confirmed.id, technicianId: tech.id, serviceDate: day,
        windowStart: start, windowEnd: end, plannedStart: start, plannedEnd: new Date(start.getTime() + 1800_000), sequence: 0 } });
      const legacy = await newJob();
      const standalone = await newJob();
      const compatible = await newJob();
      const oldSet = await tx.bookingOfferSet.create({ data: { jobId: legacy.id, expiresAt: expiry } });
      const newSet = await tx.bookingOfferSet.create({ data: { jobId: compatible.id, expiresAt: expiry } });
      for (const [job, set, width] of [[legacy, oldSet, 2], [standalone, null, 2], [compatible, newSet, 4]] as const) {
        const offer = await tx.bookingOffer.create({ data: { jobId: job.id, offerSetId: set?.id ?? null, serviceDate: day,
          windowStart: start, windowEnd: new Date(start.getTime() + width * 3600_000), expiresAt: expiry } });
        await tx.slotHold.create({ data: { jobId: job.id, offerToken: offer.id, offerSetId: set?.id ?? null,
          technicianId: tech.id, serviceDate: day, windowStart: start, windowEnd: offer.windowEnd, plannedStart: start,
          plannedEnd: new Date(start.getTime() + 1800_000), insertPosition: 0, locationLat: 41.25, locationLng: -95.93, expiresAt: expiry } });
      }
      for (const folder of ["20260929230000_retire_incompatible_promises", "20260929233000_retire_legacy_standalone_offers"]) {
        const sql = readFileSync(new URL(`../prisma/migrations/${folder}/migration.sql`, import.meta.url), "utf8");
        for (const statement of sql.split(";").filter(s => s.trim())) await tx.$executeRawUnsafe(statement);
      }
      assert.ok((await tx.bookingOfferSet.findUniqueOrThrow({ where: { id: oldSet.id } })).supersededAt);
      assert.equal(await tx.slotHold.count({ where: { jobId: { in: [legacy.id, standalone.id] }, releasedAt: null } }), 0);
      assert.equal(await tx.slotHold.count({ where: { jobId: compatible.id, releasedAt: null } }), 1);
      assert.equal((await tx.bookingOfferSet.findUniqueOrThrow({ where: { id: newSet.id } })).supersededAt, null);
      assert.ok((await tx.bookingOffer.findFirstOrThrow({ where: { jobId: standalone.id } })).expiresAt <= new Date());
      assert.deepEqual(await tx.appointment.findUniqueOrThrow({ where: { id: appointment.id } }), appointment);
      assert.equal((await tx.job.findUniqueOrThrow({ where: { id: confirmed.id } })).status, "SCHEDULED");
      throw rollback;
    }, { timeout: 10_000 });
  } catch (error) {
    if (error !== rollback) throw error;
  } finally { await prisma.$disconnect(); }
  console.log("Policy migration retires incompatible sets and standalone holds, preserving confirmed promises and compatible holds");
}
void main();
