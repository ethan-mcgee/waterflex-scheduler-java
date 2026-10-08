-- Overnight optimization run by the portal for each client through the public scheduling API. The portal is the host,
-- so it owns when each client's runs happen; the scheduler's own overnight batch does not see shared metros.

-- A client's scheduled run times, as minutes after local midnight in the zone of shift minutes. No rows: manual only.
CREATE TABLE client_overnight_time (
  "clientId" TEXT NOT NULL REFERENCES client(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
  "localMinute" INTEGER NOT NULL CHECK ("localMinute" BETWEEN 0 AND 1439),
  PRIMARY KEY ("clientId", "localMinute")
);

-- One run of a client's overnight optimization. The table is the queue: "Run now" and the scheduled tick insert QUEUED
-- runs, and a worker claims one at a time.
CREATE TABLE portal_overnight_run (
  id UUID PRIMARY KEY,
  "clientId" TEXT NOT NULL REFERENCES client(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
  trigger TEXT NOT NULL CHECK (trigger IN ('SCHEDULED', 'MANUAL')),
  -- The run time a scheduled run belongs to.
  "scheduledFor" TIMESTAMPTZ(6),
  status TEXT NOT NULL CHECK (status IN ('QUEUED', 'RUNNING', 'FINISHED', 'ABANDONED')),
  "createdAt" TIMESTAMPTZ(6) NOT NULL DEFAULT clock_timestamp(),
  "startedAt" TIMESTAMPTZ(6),
  -- Moved after every day; a running run whose worker stopped is abandoned once this is old.
  "heartbeatAt" TIMESTAMPTZ(6),
  "finishedAt" TIMESTAMPTZ(6),
  CHECK ((trigger = 'SCHEDULED') = ("scheduledFor" IS NOT NULL)),
  CHECK ((status = 'QUEUED') = ("startedAt" IS NULL)),
  CHECK (("startedAt" IS NULL) = ("heartbeatAt" IS NULL)),
  CHECK ((status IN ('QUEUED', 'RUNNING')) = ("finishedAt" IS NULL))
);
-- Every portal process may fire the same run time; the first insert wins.
CREATE UNIQUE INDEX portal_overnight_run_slot ON portal_overnight_run ("clientId", "scheduledFor") WHERE trigger = 'SCHEDULED';
-- At most one queued or running run per client.
CREATE UNIQUE INDEX portal_overnight_run_active ON portal_overnight_run ("clientId") WHERE status IN ('QUEUED', 'RUNNING');
CREATE INDEX portal_overnight_run_client ON portal_overnight_run ("clientId", "createdAt");
CREATE INDEX portal_overnight_run_queued ON portal_overnight_run ("createdAt") WHERE status = 'QUEUED';

-- What a run did for one metro day: the proposal it kept, or why it kept none. A proposal waits for a dispatcher.
CREATE TABLE portal_overnight_day (
  "runId" UUID NOT NULL REFERENCES portal_overnight_run(id) ON DELETE CASCADE,
  "metroId" TEXT NOT NULL REFERENCES metro(id) ON DELETE RESTRICT ON UPDATE CASCADE,
  "serviceDate" DATE NOT NULL,
  outcome TEXT NOT NULL CHECK (outcome IN ('PROPOSED', 'SKIPPED', 'FAILED')),
  "proposalId" TEXT UNIQUE REFERENCES portal_api_daily_proposal(id) ON DELETE RESTRICT,
  message TEXT,
  "finishedAt" TIMESTAMPTZ(6) NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY ("runId", "metroId", "serviceDate"),
  CHECK ((outcome = 'PROPOSED') = ("proposalId" IS NOT NULL)),
  CHECK ((outcome = 'PROPOSED') = (message IS NULL))
);
