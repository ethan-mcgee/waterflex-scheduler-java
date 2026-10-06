BEGIN;
-- PostgreSQL's shortest round-trip decimal rendering preserves the legacy decimal intent.
-- Record its bytes and float8 bits before conversion. Never cast directly from float to numeric.
SET LOCAL extra_float_digits = 3;
CREATE TABLE monetary_migration_receipt (
  "sourceTable" TEXT NOT NULL, "sourceIdentity" TEXT NOT NULL, "sourceColumn" TEXT NOT NULL,
  "legacyRendering" TEXT NOT NULL, "legacyBits" TEXT NOT NULL,
  "decimalValue" NUMERIC(65,30) NOT NULL, "costModelVersion" TEXT NOT NULL,
  "migratedAt" TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY ("sourceTable", "sourceIdentity", "sourceColumn")
);
DO $money$
DECLARE item RECORD; rendered TEXT; exact_value NUMERIC;
BEGIN
  FOR item IN
    SELECT 'omaha_setting' AS source_table, key AS identity, 'value' AS source_column, value AS amount FROM omaha_setting
    UNION ALL
    SELECT 'booking_offer', id, 'incrementalCostDollars', "incrementalCostDollars" FROM booking_offer WHERE "incrementalCostDollars" IS NOT NULL
  LOOP
    rendered := item.amount::text;
    IF rendered IN ('NaN','Infinity','-Infinity') THEN RAISE EXCEPTION 'Invalid legacy money %.%', item.source_table, item.identity; END IF;
    exact_value := rendered::numeric;
    IF (item.source_table='omaha_setting' AND item.identity IN ('regular_hourly_dollars','overtime_hourly_dollars','mileage_dollars_per_mile') AND exact_value<0)
      OR exact_value<>exact_value::numeric(65,30) THEN
      RAISE EXCEPTION 'Unrepresentable legacy money %.%', item.source_table, item.identity;
    END IF;
    INSERT INTO monetary_migration_receipt VALUES (item.source_table,item.identity,item.source_column,
      rendered,encode(float8send(item.amount),'hex'),exact_value,'exact-fleet-half-up-v2',CURRENT_TIMESTAMP);
  END LOOP;
END $money$;
ALTER TABLE omaha_setting ALTER COLUMN value TYPE NUMERIC(65,30) USING value::text::numeric;
ALTER TABLE booking_offer ALTER COLUMN "incrementalCostDollars" TYPE NUMERIC(65,30) USING "incrementalCostDollars"::text::numeric;
ALTER TABLE omaha_setting ADD CONSTRAINT omaha_setting_finite_decimal CHECK (value NOT IN ('NaN'::numeric,'Infinity'::numeric,'-Infinity'::numeric));
ALTER TABLE omaha_setting ADD CONSTRAINT omaha_setting_nonnegative_money CHECK
  (key NOT IN ('regular_hourly_dollars','overtime_hourly_dollars','mileage_dollars_per_mile') OR value>=0);
ALTER TABLE booking_offer ADD CONSTRAINT booking_offer_finite_decimal CHECK
  ("incrementalCostDollars" NOT IN ('NaN'::numeric,'Infinity'::numeric,'-Infinity'::numeric));
ALTER TABLE booking_offer ADD COLUMN "costModelVersion" TEXT NOT NULL DEFAULT 'legacy-double-v1';
ALTER TABLE booking_offer_set ADD COLUMN "costModelVersion" TEXT NOT NULL DEFAULT 'legacy-double-v1';
ALTER TABLE booking_offer ALTER COLUMN "costModelVersion" SET DEFAULT 'exact-fleet-half-up-v2';
ALTER TABLE booking_offer_set ALTER COLUMN "costModelVersion" SET DEFAULT 'exact-fleet-half-up-v2';
ALTER TABLE optimization_run ALTER COLUMN "objectiveImprovement" TYPE BIGINT;
-- Retain evidence and confirmed customer promises; unconfirmed numeric proposals require refresh.
UPDATE optimization_run SET status='STALE', reason='COST_MODEL_CHANGED'
  WHERE status IN ('PREVIEW','REPAIR_PREVIEW');
UPDATE booking_offer_set s SET "supersededAt"=COALESCE(s."supersededAt",CURRENT_TIMESTAMP)
  FROM job j WHERE j.id=s."jobId" AND j.status='PENDING';
UPDATE booking_offer o SET "expiresAt"=LEAST(o."expiresAt",CURRENT_TIMESTAMP)
  FROM job j WHERE j.id=o."jobId" AND j.status='PENDING';
UPDATE slot_hold h SET "releasedAt"=COALESCE(h."releasedAt",CURRENT_TIMESTAMP)
  FROM job j WHERE j.id=h."jobId" AND j.status='PENDING';
-- reservation_obligation is a read-only UNION view. Both direct and dependent
-- obligations inherit releasedAt from the slot_hold rows updated above.
UPDATE booking_search_request SET state='FAILED', phase='FAILED', "stopReason"='COST_MODEL_CHANGED',
  "finishedAt"=CURRENT_TIMESTAMP, "cancelledAt"=COALESCE("cancelledAt",CURRENT_TIMESTAMP), "bestCostDeltaCents"=NULL
  WHERE state IN ('QUEUED','RUNNING');
COMMIT;
