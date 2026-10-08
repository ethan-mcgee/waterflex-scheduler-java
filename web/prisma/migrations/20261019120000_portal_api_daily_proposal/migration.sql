-- Daily optimization through the public scheduling API: the portal, acting as the host, keeps each proposal the
-- scheduler returned for one of its clients' metro days, and the outcome of committing it. The scheduler keeps its own
-- copy; this one is what the dispatch board shows and what a commit is sent from.
CREATE TABLE portal_api_daily_proposal (
  id TEXT PRIMARY KEY,
  "clientId" TEXT NOT NULL REFERENCES client(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
  "metroId" TEXT NOT NULL REFERENCES metro(id) ON DELETE RESTRICT ON UPDATE CASCADE,
  "serviceDate" DATE NOT NULL,
  "requestId" UUID NOT NULL UNIQUE,
  decision TEXT NOT NULL CHECK (decision IN ('IMPROVED', 'NO_IMPROVEMENT', 'REJECTED_BY_POLICY')),
  -- The scheduler's answer, exactly as validated when it arrived.
  proposal JSONB NOT NULL,
  -- Every technician-day of the snapshot the proposal was computed from; a commit sends current timestamps for each.
  "technicianDays" JSONB NOT NULL,
  -- Where each appointment of the snapshot was, as the snapshot read it, for showing what the proposal changes.
  baseline JSONB NOT NULL,
  -- Set once before the first commit call, so a retry after a lost answer replays the same commit.
  "commitRequestId" UUID UNIQUE,
  "receiptId" TEXT UNIQUE,
  "committedAt" TIMESTAMPTZ(6),
  -- Why a commit was finally refused (stale facts, or not committable); the proposal cannot be applied any more.
  "commitRefusal" TEXT,
  "createdAt" TIMESTAMPTZ(6) NOT NULL DEFAULT clock_timestamp(),
  CHECK (("receiptId" IS NULL) = ("committedAt" IS NULL)),
  CHECK ("receiptId" IS NULL OR ("commitRequestId" IS NOT NULL AND decision = 'IMPROVED' AND "commitRefusal" IS NULL)),
  CHECK (jsonb_typeof(proposal) = 'object' AND jsonb_typeof("technicianDays") = 'array' AND jsonb_typeof(baseline) = 'array')
);
CREATE INDEX portal_api_daily_proposal_day ON portal_api_daily_proposal ("clientId", "metroId", "serviceDate", "createdAt");
