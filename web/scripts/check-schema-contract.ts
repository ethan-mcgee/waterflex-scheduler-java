// The portal database built by its migrations matches the Prisma schema: every column of every model, with its type
// and nullability. The scheduler checks its own database in scheduler-service SchemaContractIT.
import { z } from "zod";
import { required } from "../lib/contracts";
import assert from "node:assert/strict";
import { Prisma, PrismaClient } from "@prisma/client";

const prisma = new PrismaClient();
async function main() {
try {
  const column = z.object({ table_name: z.string(), column_name: z.string(), data_type: z.string(), udt_name: z.string(), is_nullable: z.enum(["YES", "NO"]) });
  const actual = z.array(column).parse(await prisma.$queryRaw`
    SELECT table_name, column_name, data_type, udt_name, is_nullable FROM information_schema.columns WHERE table_schema = current_schema()
  `);
  const timestampWithZone = new Set(["client.createdAt", "client_solver_settings.updatedAt", "technician.factsChangedAt", "technician_day_change.changedAt", "portal_api_offer_set.expiresAt", "portal_api_offer_set.endedAt", "portal_api_offer_set.createdAt", "portal_api_offer.windowStart", "portal_api_offer.windowEnd", "portal_api_daily_proposal.committedAt", "portal_api_daily_proposal.createdAt", "portal_overnight_run.scheduledFor", "portal_overnight_run.createdAt", "portal_overnight_run.startedAt", "portal_overnight_run.heartbeatAt", "portal_overnight_run.finishedAt", "portal_overnight_day.finishedAt"]);
  const uuidColumns = new Set(["portal_api_offer_set.requestId", "portal_api_daily_proposal.requestId", "portal_api_daily_proposal.commitRequestId", "portal_overnight_run.id", "portal_overnight_day.runId"]);
  const dateColumns = new Set(["portal_api_offer.serviceDate", "portal_api_daily_proposal.serviceDate", "portal_overnight_day.serviceDate"]);
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
  // The scheduler keeps these in its own database; the portal reaches them only through /api/v1.
  for (const table of ["tenant", "tenant_api_token", "api_request", "api_daily_proposal", "api_proposal_technician_day", "api_commit_receipt",
    "api_booking_day", "api_booking_offer_set", "api_booking_offer", "api_booking_receipt", "road_route_cache"])
    assert.ok(!actual.some(row => row.table_name === table), `The portal database must not hold the scheduler's table ${table}`);
  console.log(`Portal database matches the Prisma schema for ${Prisma.dmmf.datamodel.models.length} models and holds no scheduler tables`);
} finally {
  await prisma.$disconnect();
}
}

main().catch((error) => { console.error(error); process.exitCode = 1; });
