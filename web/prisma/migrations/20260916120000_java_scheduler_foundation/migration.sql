ALTER TABLE "job" ADD COLUMN "manualFollowUpStatus" TEXT, ADD COLUMN "manualFollowUpReason" TEXT;

CREATE TABLE "booking_offer" (
  "id" TEXT PRIMARY KEY,
  "jobId" TEXT NOT NULL REFERENCES "job"("id"),
  "serviceDate" TIMESTAMP(3) NOT NULL,
  "windowStart" TIMESTAMP(3) NOT NULL,
  "windowEnd" TIMESTAMP(3) NOT NULL,
  "expiresAt" TIMESTAMP(3) NOT NULL,
  "incrementalRegularMinutes" INTEGER,
  "incrementalOvertimeMinutes" INTEGER,
  "incrementalRoadMeters" INTEGER,
  "incrementalCostDollars" DOUBLE PRECISION,
  "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX "booking_offer_jobId_expiresAt_idx" ON "booking_offer"("jobId", "expiresAt");

CREATE TABLE "technician_qualification" (
  "technicianId" TEXT NOT NULL REFERENCES "technician"("id"),
  "serviceId" TEXT NOT NULL REFERENCES "service_catalog"("id"),
  PRIMARY KEY ("technicianId", "serviceId")
);
INSERT INTO "technician_qualification" ("technicianId", "serviceId")
SELECT t."id", s."id" FROM "technician" t CROSS JOIN "service_catalog" s;

CREATE TABLE "technician_shift_override" (
  "id" TEXT PRIMARY KEY,
  "technicianId" TEXT NOT NULL REFERENCES "technician"("id"),
  "serviceDate" TIMESTAMP(3) NOT NULL,
  "available" BOOLEAN NOT NULL DEFAULT true,
  "shiftStartMin" INTEGER,
  "shiftEndMin" INTEGER
);
CREATE UNIQUE INDEX "technician_shift_override_technicianId_serviceDate_key" ON "technician_shift_override"("technicianId", "serviceDate");

CREATE TABLE "road_route_cache" (
  "id" TEXT PRIMARY KEY,
  "originKey" TEXT NOT NULL,
  "destinationKey" TEXT NOT NULL,
  "profile" TEXT NOT NULL,
  "mapVersion" TEXT NOT NULL,
  "seconds" INTEGER,
  "meters" INTEGER,
  "routable" BOOLEAN NOT NULL,
  "fetchedAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX "road_route_cache_originKey_destinationKey_profile_mapVersion_key" ON "road_route_cache"("originKey", "destinationKey", "profile", "mapVersion");

CREATE TABLE "omaha_setting" (
  "key" TEXT PRIMARY KEY,
  "value" DOUBLE PRECISION NOT NULL,
  "updatedAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP
);
INSERT INTO "omaha_setting" ("key", "value") VALUES
  ('regular_hourly_dollars', 30), ('overtime_hourly_dollars', 45),
  ('mileage_dollars_per_mile', 0.67), ('travel_buffer_pct', 0.20),
  ('travel_buffer_minutes_per_leg', 5);
