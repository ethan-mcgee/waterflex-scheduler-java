import { z } from "zod";
import { required } from "../lib/contracts";
import assert from "node:assert/strict";
import { Prisma, PrismaClient } from "@prisma/client";

const expected: Record<string, string[]> = {
  tenant: ["id", "disabledAt"],
  tenant_api_token: ["tenantId", "tokenSha256", "revokedAt"],
  api_request: ["tenantId", "requestId", "operation", "requestSha256", "state", "ownerToken", "leaseExpiresAt", "responseStatus", "responseJson", "completedAt"],
  api_daily_proposal: ["tenantId", "id", "requestId", "metroId", "timeZone", "serviceDate", "inputRevision", "routingIdentity", "status", "proposalJson", "committedAt"],
  api_proposal_technician_day: ["tenantId", "proposalId", "technicianId", "serviceDate", "lastModified"],
  api_commit_receipt: ["tenantId", "id", "proposalId", "requestId", "receiptJson"],
  api_booking_day: ["tenantId", "metroId", "serviceDate", "version", "stateJson"],
  api_booking_offer_set: ["tenantId", "id", "requestId", "jobId", "metroId", "expiresAt", "status", "offerSetJson"],
  api_booking_offer: ["tenantId", "id", "offerSetId", "holdId", "serviceDate", "technicianId", "windowStart", "windowEnd", "status"],
  api_booking_receipt: ["tenantId", "id", "offerId", "requestId", "receiptJson"],
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
  schedule_day: ["technicianId", "serviceDate", "version"],
  road_route_cache: ["originKey", "destinationKey", "profile", "mapVersion", "seconds", "meters", "routable"],
  omaha_setting: ["key", "value"],
  time_off_request: ["id", "technicianId", "status"],
};

const prisma = new PrismaClient();
async function main() {
try {
  const column = z.object({ table_name: z.string(), column_name: z.string(), data_type: z.string(), udt_name: z.string(), is_nullable: z.enum(["YES", "NO"]) });
  const actual = z.array(column).parse(await prisma.$queryRaw`
    SELECT table_name, column_name, data_type, udt_name, is_nullable FROM information_schema.columns WHERE table_schema = current_schema()
  `);
  const timestampWithZone = new Set(["tenant.createdAt", "tenant.disabledAt", "tenant_api_token.createdAt", "tenant_api_token.revokedAt", "api_request.leaseExpiresAt", "api_request.createdAt", "api_request.completedAt", "api_daily_proposal.createdAt", "api_daily_proposal.committedAt", "api_commit_receipt.createdAt", "api_booking_day.updatedAt", "api_booking_offer_set.expiresAt", "api_booking_offer_set.createdAt", "api_booking_offer.windowStart", "api_booking_offer.windowEnd", "api_booking_receipt.createdAt", "client.createdAt", "client_solver_settings.updatedAt", "technician.factsChangedAt", "technician_day_change.changedAt", "portal_api_offer_set.expiresAt", "portal_api_offer_set.endedAt", "portal_api_offer_set.createdAt", "portal_api_offer.windowStart", "portal_api_offer.windowEnd", "portal_api_daily_proposal.committedAt", "portal_api_daily_proposal.createdAt", "portal_overnight_run.scheduledFor", "portal_overnight_run.createdAt", "portal_overnight_run.startedAt", "portal_overnight_run.heartbeatAt", "portal_overnight_run.finishedAt", "portal_overnight_day.finishedAt"]);
  const uuidColumns = new Set(["portal_api_offer_set.requestId", "portal_api_daily_proposal.requestId", "portal_api_daily_proposal.commitRequestId", "portal_overnight_run.id", "portal_overnight_day.runId"]);
  const dateColumns = new Set(["api_daily_proposal.serviceDate", "api_proposal_technician_day.serviceDate", "api_booking_day.serviceDate", "api_booking_offer.serviceDate", "portal_api_offer.serviceDate", "portal_api_daily_proposal.serviceDate", "portal_overnight_day.serviceDate"]);
  const sqlTypes: Record<string, string> = { String: "text", Int: "integer", BigInt: "bigint", Float: "double precision", Decimal: "numeric", Boolean: "boolean", DateTime: "timestamp without time zone", Json: "jsonb", Bytes: "bytea" };
  for (const model of Prisma.dmmf.datamodel.models) {
    const table = model.dbName ?? model.name;
    for (const field of model.fields.filter(field => field.kind !== "object")) {
      const name = field.dbName ?? field.name;
      const found = required(actual.find(row => row.table_name === table && row.column_name === name), `${table}.${name}`);
      assert.equal(found.is_nullable, field.isRequired ? "NO" : "YES", `${table}.${name} nullability differs from Prisma`);
      assert.equal(found.data_type, field.kind === "enum" ? "USER-DEFINED" : timestampWithZone.has(`${table}.${name}`) ? "timestamp with time zone" : uuidColumns.has(`${table}.${name}`) ? "uuid" : dateColumns.has(`${table}.${name}`) ? "date" : required(sqlTypes[field.type], `SQL type for ${field.type}`), `${table}.${name} column type`);
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
