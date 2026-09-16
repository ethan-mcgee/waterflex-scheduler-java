import assert from "node:assert/strict";
import { PrismaClient } from "@prisma/client";

const expected: Record<string, string[]> = {
  appointment: ["id", "jobId", "technicianId", "serviceDate", "windowStart", "windowEnd", "plannedStart", "plannedEnd", "sequence"],
  job: ["id", "addressId", "serviceId", "durationMin", "status", "bookingRequestId"],
  technician: ["id", "metroId", "homeLat", "homeLng", "shiftStartMin", "shiftEndMin", "maxDailyMinutes", "maxOvertimeMinutes", "active"],
  technician_qualification: ["technicianId", "serviceId"],
  technician_shift_override: ["technicianId", "serviceDate", "available", "shiftStartMin", "shiftEndMin"],
  booking_offer: ["id", "jobId", "serviceDate", "windowStart", "windowEnd", "expiresAt", "incrementalRegularMinutes", "incrementalOvertimeMinutes", "incrementalRoadMeters", "incrementalCostDollars"],
  slot_hold: ["id", "jobId", "technicianId", "serviceDate", "windowStart", "windowEnd", "expiresAt", "releasedAt"],
  schedule_day: ["technicianId", "serviceDate", "version"],
  road_route_cache: ["originKey", "destinationKey", "profile", "mapVersion", "seconds", "meters", "routable"],
  omaha_setting: ["key", "value"],
  optimization_run: ["id", "serviceDate", "scheduleVersions", "proposedAssignments", "objectiveImprovement", "status"],
  optimization_change: ["runId", "appointmentId", "fromTechnicianId", "toTechnicianId"],
};

const prisma = new PrismaClient();
async function main() {
try {
  const actual = await prisma.$queryRaw<Array<{ table_name: string; column_name: string }>>`
    SELECT table_name, column_name FROM information_schema.columns WHERE table_schema = 'public'
  `;
  const columns = new Map<string, Set<string>>();
  for (const row of actual) {
    if (!columns.has(row.table_name)) columns.set(row.table_name, new Set());
    columns.get(row.table_name)!.add(row.column_name);
  }
  for (const [table, names] of Object.entries(expected)) {
    assert.ok(columns.has(table), `Missing Java contract table ${table}`);
    for (const name of names) assert.ok(columns.get(table)!.has(name), `Missing ${table}.${name}`);
  }
  console.log(`Java and Prisma schema contract passed for ${Object.keys(expected).length} tables`);
} finally {
  await prisma.$disconnect();
}
}

main().catch((error) => { console.error(error); process.exitCode = 1; });
