import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { PrismaClient } from "@prisma/client";
import { removeSuccessfulCase } from "./benchmarkCleanup";
import { DEFAULT_CLIENT_ID } from "../lib/clients";

async function main() {
const database = new URL(process.env.DATABASE_URL ?? "");
assert.equal(database.pathname, "/waterflex_test");
assert.ok(["localhost", "127.0.0.1", "[::1]"].includes(database.hostname));
assert.ok(database.searchParams.get("schema")?.startsWith("benchmark_"));
const prisma = new PrismaClient();
const first = `cleanup-${randomUUID()}`, unrelated = `${first}-unrelated`;
async function fixture(id: string) {
  await prisma.metro.create({ data: { id, name: id, timezone: "America/Chicago" } });
  await prisma.dealership.create({ data: { clientId: DEFAULT_CLIENT_ID, id: `${id}-dealer`, name: id } });
  await prisma.depot.create({ data: { id: `${id}-depot`, name: id, metroId: id, dealershipId: `${id}-dealer`, lat: 41, lng: -96,
    endpointPolicies: { create: { effectiveDate: new Date("1900-01-01"), departure: "HOME", returnTo: "HOME" } } } });
  await prisma.optimizationRun.create({ data: { id: `${id}-run`, metroId: id, serviceDate: new Date("2026-10-01"),
    scheduleVersions: {}, weights: {}, solverStatus: "COMPLETED", solveMs: 1, routeSummaryBefore: [], routeSummaryAfter: [],
    warnings: [], proposedAssignments: [], objectiveImprovement: 0, churnCost: 0, status: "PREVIEW",
    changes: { create: { id: `${id}-change`, appointmentId: "historical", fromTechnicianId: "a", toTechnicianId: "b",
      fromSequence: 0, toSequence: 1, fromPlannedArrivalMin: 480, toPlannedArrivalMin: 490 } } } });
}
try {
  await fixture(first); await fixture(unrelated);
  // A real restrictive FK fails at the end, after the optimization and depot deletions.
  await prisma.depot.create({ data: { id: `${first}-blocker`, name: "rollback blocker", metroId: first,
    dealershipId: `${unrelated}-dealer`, lat: 41, lng: -96 } });
  await assert.rejects(removeSuccessfulCase(prisma, first));
  assert.equal(await prisma.optimizationRun.count({ where: { metroId: first } }), 1);
  assert.equal(await prisma.optimizationChange.count({ where: { runId: `${first}-run` } }), 1);
  assert.ok(await prisma.depot.findUnique({ where: { id: `${first}-depot` } }));
  await prisma.depot.delete({ where: { id: `${first}-blocker` } });
  await removeSuccessfulCase(prisma, first);
  assert.equal(await prisma.metro.count({ where: { id: first } }), 0);
  assert.equal(await prisma.optimizationRun.count({ where: { metroId: first } }), 0);
  assert.equal(await prisma.optimizationChange.count({ where: { runId: `${first}-run` } }), 0);
  assert.equal(await prisma.optimizationRun.count({ where: { metroId: unrelated } }), 1);
  assert.equal(await prisma.optimizationChange.count({ where: { runId: `${unrelated}-run` } }), 1);
  await removeSuccessfulCase(prisma, unrelated);
  console.log("PostgreSQL cleanup: cascade, unrelated metro preservation, and transaction rollback passed");
} finally { await prisma.$disconnect(); }

}
main().catch(error => { console.error(error); process.exitCode = 1; });
