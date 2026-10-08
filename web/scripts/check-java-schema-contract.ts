import { z } from "zod";
import { required } from "../lib/contracts";
import assert from "node:assert/strict";
import { Prisma, PrismaClient } from "@prisma/client";

const expected: Record<string, string[]> = {
  overnight_optimization_attempt: ["id", "metroId", "serviceDate", "state", "previewKey", "resultRunId", "resultReason", "failureContext", "startedAt", "finishedAt", "nightOf"],
  daily_calculation_attempt: ["id", "requestKey", "requestFingerprint", "ownerToken", "state", "expiresAt", "snapshotRevision", "routingIdentity", "resultRunId", "resultJson", "failureReason"],
  booking_search_request: ["id", "jobId", "deadlineAt", "cancelledAt", "acknowledgedAt", "offerSetId", "cleanedAt"],
  appointment: ["id", "jobId", "technicianId", "serviceDate", "windowStart", "windowEnd", "plannedStart", "plannedEnd", "sequence"],
  job: ["id", "addressId", "serviceId", "durationMin", "status", "bookingRequestId"],
  technician: ["id", "homeLat", "homeLng", "shiftStartMin", "shiftEndMin", "maxDailyMinutes", "maxOvertimeMinutes", "active"],
  dealership: ["id", "name"],
  depot_endpoint_policy: ["depotId", "effectiveDate", "departure", "returnTo"],
  technician_depot_assignment: ["technicianId", "effectiveDate", "depotId"],
  depot: ["id", "dealershipId", "metroId", "lat", "lng"],
  technician_qualification: ["technicianId", "serviceId"],
  technician_shift_override: ["technicianId", "serviceDate", "available", "shiftStartMin", "shiftEndMin"],
  technician_availability_version: ["id", "technicianId", "effectiveDate"],
  technician_availability_day: ["versionId", "dayOfWeek", "available", "shiftStartMin", "shiftEndMin"],
  booking_offer: ["id", "jobId", "serviceDate", "windowStart", "windowEnd", "expiresAt", "incrementalRegularMinutes", "incrementalOvertimeMinutes", "incrementalRoadMeters", "incrementalCostDollars", "overtimeAuthorized"],
  slot_hold: ["id", "jobId", "technicianId", "serviceDate", "windowStart", "windowEnd", "expiresAt", "releasedAt"],
  booking_offer_set: ["id", "jobId", "expiresAt", "supersededAt", "selectedOfferId", "searchDiagnostics"],
  schedule_day: ["technicianId", "serviceDate", "version", "routeTiming"],
  road_route_cache: ["originKey", "destinationKey", "profile", "mapVersion", "seconds", "meters", "routable"],
  omaha_setting: ["key", "value"],
  optimization_run: ["id", "serviceDate", "scheduleVersions", "proposedAssignments", "endpointSnapshots", "objectiveImprovement", "status", "policyAnalysis"],
  optimization_change: ["runId", "appointmentId", "fromTechnicianId", "toTechnicianId"],
  time_off_request: ["id", "technicianId", "status", "additionalOvertimeApproved"],
  reservation_arrangement: ["id", "metroId", "serviceDate", "version", "state", "updatedAt"],
  reservation_dependency: ["arrangementId", "holdId", "technicianId", "serviceId", "serviceDate"],
  reservation_obligation: ["id", "jobId", "technicianId", "serviceId", "serviceDate", "expiresAt", "releasedAt"],
};

const prisma = new PrismaClient();
async function main() {
try {
  const column = z.object({ table_name: z.string(), column_name: z.string(), data_type: z.string(), udt_name: z.string(), is_nullable: z.enum(["YES", "NO"]) });
  const actual = z.array(column).parse(await prisma.$queryRaw`
    SELECT table_name, column_name, data_type, udt_name, is_nullable FROM information_schema.columns WHERE table_schema = current_schema()
  `);
  const timestampWithZone = new Set(["booking_optimization.createdAt", "optimization_run.serviceDate", "optimization_run.createdAt", "optimization_run.appliedAt", "monetary_migration_receipt.migratedAt", "daily_calculation_attempt.expiresAt", "daily_calculation_attempt.createdAt", "daily_calculation_attempt.updatedAt", "overnight_optimization_attempt.startedAt", "overnight_optimization_attempt.finishedAt"]);
  const dateColumns = new Set(["overnight_optimization_attempt.serviceDate", "overnight_optimization_attempt.nightOf"]);
  const sqlTypes: Record<string, string> = { String: "text", Int: "integer", BigInt: "bigint", Float: "double precision", Decimal: "numeric", Boolean: "boolean", DateTime: "timestamp without time zone", Json: "jsonb", Bytes: "bytea" };
  for (const model of Prisma.dmmf.datamodel.models) {
    const table = model.dbName ?? model.name;
    for (const field of model.fields.filter(field => field.kind !== "object")) {
      const name = field.dbName ?? field.name;
      const found = required(actual.find(row => row.table_name === table && row.column_name === name), `${table}.${name}`);
      assert.equal(found.is_nullable, field.isRequired ? "NO" : "YES", `${table}.${name} nullability differs from Prisma`);
      assert.equal(found.data_type, field.kind === "enum" ? "USER-DEFINED" : timestampWithZone.has(`${table}.${name}`) ? "timestamp with time zone" : dateColumns.has(`${table}.${name}`) ? "date" : required(sqlTypes[field.type], `SQL type for ${field.type}`), `${table}.${name} column type`);
      if (field.kind === "enum") assert.equal(found.udt_name, field.type, `${table}.${name} enum type`);
    }
  }
  const columns = new Map<string, Set<string>>();
  for (const row of actual) {
    if (!columns.has(row.table_name)) columns.set(row.table_name, new Set());
    required(columns.get(row.table_name)).add(row.column_name);
  }
  for (const [table, names] of Object.entries(expected)) {
    assert.ok(columns.has(table), `Missing Java contract table ${table}`);
    for (const name of names) assert.ok(required(columns.get(table)).has(name), `Missing ${table}.${name}`);
  }
  console.log(`Java and Prisma schema contract passed for ${Object.keys(expected).length} tables`);
} finally {
  await prisma.$disconnect();
}
}

main().catch((error) => { console.error(error); process.exitCode = 1; });
