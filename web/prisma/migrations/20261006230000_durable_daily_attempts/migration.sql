CREATE TABLE daily_calculation_attempt (
    id TEXT PRIMARY KEY,
    "requestKey" TEXT NOT NULL UNIQUE CHECK (length("requestKey") BETWEEN 1 AND 160 AND btrim("requestKey")<>''),
    "requestFingerprint" TEXT NOT NULL CHECK ("requestFingerprint" ~ '^[0-9a-f]{64}$'),
    "ownerToken" TEXT NOT NULL CHECK (length("ownerToken")>0),
    state TEXT NOT NULL CHECK (state IN ('CLAIMED','SUCCEEDED','FAILED','CANCELLED','ABANDONED','STALE')),
    "expiresAt" TIMESTAMPTZ NOT NULL,
    "snapshotRevision" TEXT CHECK ("snapshotRevision" ~ '^[0-9a-f]{64}$'),
    "routingIdentity" TEXT,
    -- Logical receipt reference: explicit benchmark/fixture cleanup may delete its run.
    -- The retained response never authorizes applying a missing or stale run.
    "resultRunId" TEXT,
    "resultJson" JSONB,
    "failureReason" TEXT,
    "createdAt" TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    "updatedAt" TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CHECK ((state='SUCCEEDED') = ("resultJson" IS NOT NULL)),
    CHECK ("resultJson" IS NULL OR (jsonb_typeof("resultJson")='object' AND "resultJson" ? 'status' AND jsonb_typeof("resultJson"->'status')='string')),
    CHECK ("resultRunId" IS NULL OR state='SUCCEEDED'),
    CHECK ((state IN ('FAILED','CANCELLED','ABANDONED','STALE')) = ("failureReason" IS NOT NULL))
);
CREATE INDEX daily_calculation_attempt_state_expiry ON daily_calculation_attempt(state,"expiresAt");
