ALTER TABLE booking_search_request
  ADD COLUMN state TEXT,
  ADD COLUMN refresh BOOLEAN NOT NULL DEFAULT false,
  ADD COLUMN owner TEXT,
  ADD COLUMN "leaseUntil" TIMESTAMP(3),
  ADD COLUMN "startedAt" TIMESTAMP(3),
  ADD COLUMN "finishedAt" TIMESTAMP(3),
  ADD COLUMN "stopReason" TEXT,
  ADD COLUMN "queueMs" BIGINT,
  ADD COLUMN "completedWork" BIGINT NOT NULL DEFAULT 0,
  ADD COLUMN phase TEXT NOT NULL DEFAULT 'QUEUED',
  ADD CONSTRAINT booking_search_state CHECK (state IS NULL OR state IN
    ('QUEUED','RUNNING','AVAILABLE','NO_CANDIDATE','INCOMPLETE','FAILED','CANCELLED'));
CREATE UNIQUE INDEX booking_search_one_active_job ON booking_search_request ("jobId")
  WHERE state IN ('QUEUED','RUNNING');
ALTER TABLE booking_offer ADD COLUMN "policyVersion" TEXT NOT NULL DEFAULT 'zero-overtime-four-hour-v2';
ALTER TABLE booking_offer_set ADD COLUMN "policyVersion" TEXT NOT NULL DEFAULT 'zero-overtime-four-hour-v2';

-- Existing confirmed appointments are untouched. Incompatible unconfirmed offers
-- are retired as sets, so sibling reservations cannot outlive a policy change.
UPDATE booking_offer_set s SET "supersededAt" = CURRENT_TIMESTAMP
WHERE s."supersededAt" IS NULL AND EXISTS
  (SELECT 1 FROM booking_offer o WHERE o."offerSetId"=s.id
   AND (o."overtimeAuthorized" OR o."incrementalOvertimeMinutes" > 0));
UPDATE slot_hold h SET "releasedAt" = CURRENT_TIMESTAMP
WHERE h."releasedAt" IS NULL AND EXISTS
  (SELECT 1 FROM booking_offer_set s WHERE s.id=h."offerSetId" AND s."supersededAt" IS NOT NULL);
