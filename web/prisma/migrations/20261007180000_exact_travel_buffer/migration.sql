BEGIN;
-- Buffered travel minutes are now an exact decimal ceiling instead of a binary floating-point ceiling.
-- Paid minutes and feasibility can change by one minute per leg, so the cost model version advances.
ALTER TABLE booking_offer ALTER COLUMN "costModelVersion" SET DEFAULT 'exact-fleet-half-up-v3';
ALTER TABLE booking_offer_set ALTER COLUMN "costModelVersion" SET DEFAULT 'exact-fleet-half-up-v3';
-- Retain evidence and confirmed customer promises; unconfirmed numeric proposals require refresh.
UPDATE optimization_run SET status='STALE', reason='COST_MODEL_CHANGED'
  WHERE status IN ('PREVIEW','REPAIR_PREVIEW');
UPDATE booking_offer_set s SET "supersededAt"=COALESCE(s."supersededAt",CURRENT_TIMESTAMP)
  FROM job j WHERE j.id=s."jobId" AND j.status='PENDING';
UPDATE booking_offer o SET "expiresAt"=LEAST(o."expiresAt",CURRENT_TIMESTAMP)
  FROM job j WHERE j.id=o."jobId" AND j.status='PENDING';
UPDATE slot_hold h SET "releasedAt"=COALESCE(h."releasedAt",CURRENT_TIMESTAMP)
  FROM job j WHERE j.id=h."jobId" AND j.status='PENDING';
UPDATE booking_search_request SET state='FAILED', phase='FAILED', "stopReason"='COST_MODEL_CHANGED',
  "finishedAt"=CURRENT_TIMESTAMP, "cancelledAt"=COALESCE("cancelledAt",CURRENT_TIMESTAMP), "bestCostDeltaCents"=NULL
  WHERE state IN ('QUEUED','RUNNING');
COMMIT;
