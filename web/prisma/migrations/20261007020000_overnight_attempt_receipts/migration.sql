CREATE TABLE overnight_optimization_attempt (
  id TEXT PRIMARY KEY,
  "metroId" TEXT NOT NULL,
  "serviceDate" DATE NOT NULL,
  state TEXT NOT NULL CHECK (state IN ('ATTEMPTED','SUCCEEDED','SKIPPED','FAILED','CANCELLED')),
  "previewKey" TEXT NOT NULL UNIQUE,
  "resultRunId" TEXT,
  "resultReason" TEXT,
  "failureContext" JSONB,
  "startedAt" TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
  "finishedAt" TIMESTAMPTZ,
  CHECK ((state='ATTEMPTED') = ("finishedAt" IS NULL)),
  CHECK ((state IN ('FAILED','CANCELLED')) = ("failureContext" IS NOT NULL)),
  CHECK (state<>'SUCCEEDED' OR "resultRunId" IS NOT NULL),
  CHECK ("finishedAt" IS NULL OR "finishedAt">="startedAt")
);
CREATE INDEX overnight_optimization_attempt_metro_day ON overnight_optimization_attempt ("metroId","serviceDate","startedAt");
CREATE INDEX overnight_optimization_attempt_state_started ON overnight_optimization_attempt (state,"startedAt");
