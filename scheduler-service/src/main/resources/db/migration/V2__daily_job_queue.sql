-- Asynchronous daily and repair proposals (spec 1.4.0). A host that sends "Prefer: respond-async" gets 202 at once;
-- its request waits here until a worker on any replica takes it, and the host polls GET /api/v1/requests/{requestId}.
-- The request's idempotency claim (api_request) is held by the job's owner token from the moment it is queued, so
-- the same requestId sent again, synchronously or not, finds it in progress and then replays its stored answer.

CREATE TABLE api_daily_job (
  "tenantId" TEXT NOT NULL REFERENCES tenant(id) ON UPDATE CASCADE ON DELETE RESTRICT,
  "requestId" TEXT NOT NULL CHECK ("requestId" ~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'),
  operation TEXT NOT NULL CHECK (operation IN ('DAILY_PROPOSAL', 'REPAIR_PROPOSAL')),
  -- The request as the host sent it, already parsed strictly once; the worker parses it again before use.
  "requestJson" JSONB NOT NULL,
  "ownerToken" TEXT NOT NULL CHECK (length("ownerToken") > 0),
  state TEXT NOT NULL CHECK (state IN ('QUEUED', 'RUNNING', 'DONE')),
  -- How long one run may hold the job and its claim: routing, the client's search allowance and a margin.
  "leaseSeconds" INTEGER NOT NULL CHECK ("leaseSeconds" BETWEEN 60 AND 3600),
  "queuedAt" TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
  "notBefore" TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
  "startedAt" TIMESTAMPTZ,
  "leaseExpiresAt" TIMESTAMPTZ,
  worker TEXT CHECK (worker IS NULL OR length(worker) BETWEEN 1 AND 200),
  attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
  "cancelRequested" BOOLEAN NOT NULL DEFAULT false,
  "finishedAt" TIMESTAMPTZ,
  -- Set only when the run ended without a stored answer (unavailable, busy, cancelled, expired in the queue): the
  -- claim was released, so sending the same requestId again runs it again. A stored answer lives in api_request.
  "resultStatus" INTEGER CHECK ("resultStatus" BETWEEN 400 AND 599),
  "resultJson" JSONB,
  "retryAfterSeconds" INTEGER CHECK ("retryAfterSeconds" BETWEEN 1 AND 300),
  PRIMARY KEY ("tenantId", "requestId"),
  CHECK (state <> 'RUNNING' OR ("startedAt" IS NOT NULL AND "leaseExpiresAt" IS NOT NULL AND worker IS NOT NULL)),
  CHECK ((state = 'DONE') = ("finishedAt" IS NOT NULL)),
  CHECK (("resultStatus" IS NULL) = ("resultJson" IS NULL)),
  CHECK (state = 'DONE' OR ("resultStatus" IS NULL AND "retryAfterSeconds" IS NULL)),
  CHECK ("retryAfterSeconds" IS NULL OR "resultStatus" IS NOT NULL)
);
CREATE INDEX api_daily_job_waiting ON api_daily_job (state, "notBefore") WHERE state <> 'DONE';

GRANT SELECT, INSERT, UPDATE ON api_daily_job TO scheduler_tenant;
ALTER TABLE api_daily_job ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON api_daily_job USING ("tenantId" = current_setting('app.tenant_id', true)) WITH CHECK ("tenantId" = current_setting('app.tenant_id', true));

-- When each tenant's most recent job started. Workers take the waiting job of the tenant whose turn is oldest, so a
-- tenant that queued many days never holds back another tenant's single request. Only scheduler_next_daily_job
-- reads or writes it.
CREATE TABLE api_tenant_turn (
  "tenantId" TEXT PRIMARY KEY REFERENCES tenant(id) ON UPDATE CASCADE ON DELETE RESTRICT,
  "lastStartedAt" TIMESTAMPTZ NOT NULL
);

-- Takes the next job for a worker, across tenants: a job queued longer than max_wait first (the worker ends it
-- without calculating), then the waiting job of the tenant with the oldest turn, oldest first within a tenant. A
-- running job whose lease passed (its replica stopped) is taken again with one more attempt. Rows other workers have
-- locked are skipped, so any number of replicas can call it at once. It renews the request's claim for the run; a job
-- whose claim already ended or passed to another caller is closed without a result and the next one is tried.
CREATE FUNCTION scheduler_next_daily_job(worker_name TEXT, max_wait INTERVAL)
RETURNS TABLE (job_tenant TEXT, job_request TEXT, job_owner TEXT, job_operation TEXT, job_attempts INTEGER, job_expired BOOLEAN)
LANGUAGE plpgsql SECURITY DEFINER AS $$
#variable_conflict use_column
DECLARE
  picked RECORD;
  renewed INTEGER;
  now_at TIMESTAMPTZ;
BEGIN
  IF worker_name IS NULL OR length(worker_name) NOT BETWEEN 1 AND 200 OR max_wait IS NULL OR max_wait < INTERVAL '1 minute' THEN
    RAISE EXCEPTION 'scheduler_next_daily_job needs a worker name of 1 to 200 characters and a max_wait of at least one minute';
  END IF;
  FOR attempt IN 1..20 LOOP
    now_at := clock_timestamp();
    SELECT q."tenantId" AS tenant, q."requestId" AS request, q."ownerToken" AS owner, q.operation AS operation,
           q.attempts AS attempts, q."leaseSeconds" AS lease, (q.state = 'QUEUED' AND q."queuedAt" < now_at - max_wait) AS expired
      INTO picked
      FROM api_daily_job q LEFT JOIN api_tenant_turn t ON t."tenantId" = q."tenantId"
     WHERE (q.state = 'QUEUED' AND q."notBefore" <= now_at) OR (q.state = 'RUNNING' AND q."leaseExpiresAt" < now_at)
     ORDER BY (q.state = 'QUEUED' AND q."queuedAt" < now_at - max_wait) DESC, t."lastStartedAt" ASC NULLS FIRST, q."queuedAt", q."requestId"
     LIMIT 1
       FOR UPDATE OF q SKIP LOCKED;
    IF NOT FOUND THEN
      RETURN;
    END IF;

    IF picked.expired THEN
      -- Ended by the worker without calculating; it keeps the job for one minute while it records the answer.
      UPDATE api_daily_job SET state = 'RUNNING', worker = worker_name, "startedAt" = now_at, "leaseExpiresAt" = now_at + INTERVAL '1 minute'
       WHERE "tenantId" = picked.tenant AND "requestId" = picked.request;
      job_tenant := picked.tenant; job_request := picked.request; job_owner := picked.owner;
      job_operation := picked.operation; job_attempts := picked.attempts; job_expired := true;
      RETURN NEXT;
      RETURN;
    END IF;

    UPDATE api_request SET "leaseExpiresAt" = now_at + make_interval(secs => picked.lease)
     WHERE "tenantId" = picked.tenant AND "requestId" = picked.request AND "ownerToken" = picked.owner AND state = 'IN_PROGRESS';
    GET DIAGNOSTICS renewed = ROW_COUNT;
    IF renewed = 0 THEN
      UPDATE api_daily_job SET state = 'DONE', "finishedAt" = now_at
       WHERE "tenantId" = picked.tenant AND "requestId" = picked.request;
      CONTINUE;
    END IF;

    UPDATE api_daily_job SET state = 'RUNNING', worker = worker_name, "startedAt" = now_at,
           "leaseExpiresAt" = now_at + make_interval(secs => picked.lease), attempts = attempts + 1
     WHERE "tenantId" = picked.tenant AND "requestId" = picked.request;
    INSERT INTO api_tenant_turn ("tenantId", "lastStartedAt") VALUES (picked.tenant, now_at)
      ON CONFLICT ("tenantId") DO UPDATE SET "lastStartedAt" = EXCLUDED."lastStartedAt";
    job_tenant := picked.tenant; job_request := picked.request; job_owner := picked.owner;
    job_operation := picked.operation; job_attempts := picked.attempts + 1; job_expired := false;
    RETURN NEXT;
    RETURN;
  END LOOP;
END $$;

DO $$
BEGIN
  EXECUTE format('ALTER FUNCTION scheduler_next_daily_job(TEXT, INTERVAL) SET search_path = %I, pg_temp', current_schema());
END $$;
REVOKE ALL ON FUNCTION scheduler_next_daily_job(TEXT, INTERVAL) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION scheduler_next_daily_job(TEXT, INTERVAL) TO scheduler_app;

-- The retention purge now also deletes finished jobs, and jobs left waiting, older than the cutoff.
CREATE OR REPLACE FUNCTION scheduler_purge(cutoff TIMESTAMPTZ, batch INTEGER) RETURNS INTEGER
LANGUAGE plpgsql SECURITY DEFINER AS $$
DECLARE
  n INTEGER;
  total INTEGER := 0;
BEGIN
  IF cutoff IS NULL OR batch IS NULL OR batch < 1 OR batch > 50000 OR cutoff > clock_timestamp() - INTERVAL '1 day' THEN
    RAISE EXCEPTION 'scheduler_purge needs a cutoff at least one day old and a batch of 1 to 50000';
  END IF;

  DELETE FROM api_booking_receipt WHERE ctid IN (
    SELECT ctid FROM api_booking_receipt WHERE "createdAt" < cutoff LIMIT batch FOR UPDATE SKIP LOCKED);
  GET DIAGNOSTICS n = ROW_COUNT; total := total + n;

  -- An offer set's offers go with it (ON DELETE CASCADE).
  DELETE FROM api_booking_offer_set WHERE ctid IN (
    SELECT s.ctid FROM api_booking_offer_set s WHERE s."createdAt" < cutoff AND NOT EXISTS (
      SELECT 1 FROM api_booking_receipt r JOIN api_booking_offer o ON o."tenantId" = r."tenantId" AND o.id = r."offerId"
      WHERE o."tenantId" = s."tenantId" AND o."offerSetId" = s.id)
    LIMIT batch FOR UPDATE SKIP LOCKED);
  GET DIAGNOSTICS n = ROW_COUNT; total := total + n;

  DELETE FROM api_commit_receipt WHERE ctid IN (
    SELECT ctid FROM api_commit_receipt WHERE "createdAt" < cutoff LIMIT batch FOR UPDATE SKIP LOCKED);
  GET DIAGNOSTICS n = ROW_COUNT; total := total + n;

  -- A proposal's technician-days go with it (ON DELETE CASCADE).
  DELETE FROM api_daily_proposal WHERE ctid IN (
    SELECT p.ctid FROM api_daily_proposal p WHERE p."createdAt" < cutoff AND NOT EXISTS (
      SELECT 1 FROM api_commit_receipt c WHERE c."tenantId" = p."tenantId" AND c."proposalId" = p.id)
    LIMIT batch FOR UPDATE SKIP LOCKED);
  GET DIAGNOSTICS n = ROW_COUNT; total := total + n;

  DELETE FROM api_request WHERE ctid IN (
    SELECT q.ctid FROM api_request q
    WHERE (q."completedAt" < cutoff OR (q.state = 'IN_PROGRESS' AND q."leaseExpiresAt" < cutoff))
      AND NOT EXISTS (SELECT 1 FROM api_daily_proposal p WHERE p."tenantId" = q."tenantId" AND p."requestId" = q."requestId")
      AND NOT EXISTS (SELECT 1 FROM api_commit_receipt c WHERE c."tenantId" = q."tenantId" AND c."requestId" = q."requestId")
      AND NOT EXISTS (SELECT 1 FROM api_booking_offer_set s WHERE s."tenantId" = q."tenantId" AND s."requestId" = q."requestId")
      AND NOT EXISTS (SELECT 1 FROM api_booking_receipt r WHERE r."tenantId" = q."tenantId" AND r."requestId" = q."requestId")
    LIMIT batch FOR UPDATE SKIP LOCKED);
  GET DIAGNOSTICS n = ROW_COUNT; total := total + n;

  -- A booking day untouched this long has no live hold; the next search for it starts from the host's snapshot.
  DELETE FROM api_booking_day WHERE ctid IN (
    SELECT ctid FROM api_booking_day WHERE "updatedAt" < cutoff LIMIT batch FOR UPDATE SKIP LOCKED);
  GET DIAGNOSTICS n = ROW_COUNT; total := total + n;

  DELETE FROM api_daily_job WHERE ctid IN (
    SELECT ctid FROM api_daily_job WHERE COALESCE("finishedAt", "queuedAt") < cutoff LIMIT batch FOR UPDATE SKIP LOCKED);
  GET DIAGNOSTICS n = ROW_COUNT; total := total + n;

  RETURN total;
END $$;

-- CREATE OR REPLACE resets the function's settings, so its search path is set again.
DO $$
BEGIN
  EXECUTE format('ALTER FUNCTION scheduler_purge(TIMESTAMPTZ, INTEGER) SET search_path = %I, pg_temp', current_schema());
END $$;
