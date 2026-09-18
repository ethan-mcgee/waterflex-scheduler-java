CREATE TABLE "booking_offer_set" (
    "id" TEXT NOT NULL PRIMARY KEY,
    "jobId" TEXT NOT NULL REFERENCES "job"("id"),
    "expiresAt" TIMESTAMP(3) NOT NULL,
    "supersededAt" TIMESTAMP(3),
    "selectedOfferId" TEXT,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX "booking_offer_set_jobId_expiresAt_idx" ON "booking_offer_set"("jobId", "expiresAt");
ALTER TABLE "booking_offer" ADD COLUMN "offerSetId" TEXT REFERENCES "booking_offer_set"("id");
ALTER TABLE "slot_hold" ADD COLUMN "offerSetId" TEXT REFERENCES "booking_offer_set"("id");
CREATE INDEX "booking_offer_offerSetId_idx" ON "booking_offer"("offerSetId");
CREATE INDEX "slot_hold_offerSetId_idx" ON "slot_hold"("offerSetId");
ALTER TABLE "appointment" ADD COLUMN "cancelledAt" TIMESTAMP(3);
ALTER TABLE "appointment" ADD COLUMN "cancellationReason" TEXT;
CREATE TABLE "time_off_request" (
    "id" TEXT NOT NULL PRIMARY KEY,
    "technicianId" TEXT NOT NULL REFERENCES "technician"("id"),
    "reason" TEXT NOT NULL,
    "status" TEXT NOT NULL DEFAULT 'PENDING',
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "decidedAt" TIMESTAMP(3)
);
CREATE INDEX "time_off_request_technicianId_status_idx" ON "time_off_request"("technicianId", "status");
CREATE TABLE "time_off_interval" (
    "id" TEXT NOT NULL PRIMARY KEY,
    "requestId" TEXT NOT NULL REFERENCES "time_off_request"("id") ON DELETE CASCADE,
    "serviceDate" TIMESTAMP(3) NOT NULL,
    "startMin" INTEGER NOT NULL,
    "endMin" INTEGER NOT NULL,
    CONSTRAINT "time_off_interval_minutes_check" CHECK ("startMin" >= 0 AND "endMin" <= 1440 AND "startMin" < "endMin")
);
CREATE INDEX "time_off_interval_serviceDate_idx" ON "time_off_interval"("serviceDate");
CREATE TABLE "time_off_report" (
    "id" TEXT NOT NULL PRIMARY KEY,
    "requestId" TEXT NOT NULL UNIQUE REFERENCES "time_off_request"("id") ON DELETE CASCADE,
    "status" TEXT NOT NULL DEFAULT 'QUEUED',
    "progress" INTEGER NOT NULL DEFAULT 0,
    "data" JSONB,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP
);
