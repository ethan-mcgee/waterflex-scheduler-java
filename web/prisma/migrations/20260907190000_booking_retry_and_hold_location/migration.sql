-- Add a stable client retry key so repeated submissions reuse one booking job.
ALTER TABLE "job" ADD COLUMN "bookingRequestId" TEXT;
CREATE UNIQUE INDEX "job_bookingRequestId_key" ON "job"("bookingRequestId");

-- New holds carry the job location so they can reserve route capacity before
-- confirmation. Existing holds remain valid but are ignored for detour-aware
-- capacity until they expire naturally.
ALTER TABLE "slot_hold"
  ADD COLUMN "locationLat" DOUBLE PRECISION,
  ADD COLUMN "locationLng" DOUBLE PRECISION;
