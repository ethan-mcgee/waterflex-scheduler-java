-- The scheduler's own database: tenants and their API tokens, the thin store of the public API, and the road route
-- cache. Nothing here is master data; WaterFlex Software keeps that and sends snapshots. The scheduler creates and
-- migrates this schema itself (java -jar scheduler-service.jar --command=migrate, as the schema owner); no other
-- program writes it. This baseline matches the tables the portal's Prisma migrations created up to 2026-10-28.

-- Tenants (clients of the public API) and their tokens. A token is stored only as its SHA-256 hex digest.
CREATE TABLE tenant (
  id TEXT PRIMARY KEY CHECK (id ~ '^[a-z0-9][a-z0-9-]{0,62}$'),
  name TEXT NOT NULL CHECK (length(btrim(name)) > 0),
  "createdAt" TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
  "disabledAt" TIMESTAMPTZ
);

CREATE TABLE tenant_api_token (
  id TEXT PRIMARY KEY,
  "tenantId" TEXT NOT NULL REFERENCES tenant(id) ON UPDATE CASCADE ON DELETE RESTRICT,
  "tokenSha256" TEXT NOT NULL UNIQUE CHECK ("tokenSha256" ~ '^[0-9a-f]{64}$'),
  label TEXT NOT NULL CHECK (length(btrim(label)) > 0),
  "createdAt" TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
  "revokedAt" TIMESTAMPTZ,
  CHECK ("revokedAt" IS NULL OR "revokedAt" >= "createdAt")
);
CREATE INDEX tenant_api_token_tenant ON tenant_api_token ("tenantId");

-- Idempotency claims: one row per host requestId, holding the stored answer once the call completes.
CREATE TABLE api_request (
  "tenantId" TEXT NOT NULL REFERENCES tenant(id) ON UPDATE CASCADE ON DELETE RESTRICT,
  "requestId" TEXT NOT NULL CHECK ("requestId" ~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'),
  operation TEXT NOT NULL CHECK (operation IN ('DAILY_PROPOSAL', 'DAILY_COMMIT', 'BOOKING_OFFERS', 'BOOKING_SELECT', 'BOOKING_RELEASE', 'BOOKING_CONFIRM', 'REPAIR_PROPOSAL')),
  "requestSha256" TEXT NOT NULL CHECK ("requestSha256" ~ '^[0-9a-f]{64}$'),
  state TEXT NOT NULL CHECK (state IN ('IN_PROGRESS', 'COMPLETED')),
  "ownerToken" TEXT NOT NULL CHECK (length("ownerToken") > 0),
  "leaseExpiresAt" TIMESTAMPTZ NOT NULL,
  "responseStatus" INTEGER CHECK ("responseStatus" BETWEEN 200 AND 599),
  "responseJson" JSONB,
  "createdAt" TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
  "completedAt" TIMESTAMPTZ,
  PRIMARY KEY ("tenantId", "requestId"),
  CHECK ((state = 'COMPLETED') = ("responseStatus" IS NOT NULL AND "responseJson" IS NOT NULL AND "completedAt" IS NOT NULL))
);

-- Daily and repair proposals, the technician-day timestamps each was computed from, and commit receipts.
CREATE TABLE api_daily_proposal (
  "tenantId" TEXT NOT NULL,
  id TEXT NOT NULL CHECK (id ~ '^prop-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'),
  "requestId" TEXT NOT NULL,
  "metroId" TEXT NOT NULL CHECK (length("metroId") BETWEEN 1 AND 128),
  "serviceDate" DATE NOT NULL,
  "inputRevision" TEXT NOT NULL CHECK ("inputRevision" ~ '^[0-9a-f]{64}$'),
  "routingIdentity" TEXT NOT NULL CHECK (length("routingIdentity") > 0),
  status TEXT NOT NULL CHECK (status IN ('PROPOSED', 'COMMITTED', 'STALE')),
  "proposalJson" JSONB NOT NULL,
  "createdAt" TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
  "committedAt" TIMESTAMPTZ,
  "timeZone" TEXT NOT NULL CHECK (length("timeZone") BETWEEN 1 AND 64),
  PRIMARY KEY ("tenantId", id),
  UNIQUE ("tenantId", "requestId"),
  FOREIGN KEY ("tenantId", "requestId") REFERENCES api_request("tenantId", "requestId") ON DELETE RESTRICT,
  CHECK ((status = 'COMMITTED') = ("committedAt" IS NOT NULL))
);
CREATE INDEX api_daily_proposal_day ON api_daily_proposal ("tenantId", "metroId", "serviceDate");

CREATE TABLE api_proposal_technician_day (
  "tenantId" TEXT NOT NULL,
  "proposalId" TEXT NOT NULL,
  "technicianId" TEXT NOT NULL CHECK (length("technicianId") BETWEEN 1 AND 128),
  "serviceDate" DATE NOT NULL,
  "lastModified" TEXT NOT NULL CHECK ("lastModified" ~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}(:[0-9]{2}(\.[0-9]{1,9})?)?Z$'),
  PRIMARY KEY ("tenantId", "proposalId", "technicianId", "serviceDate"),
  FOREIGN KEY ("tenantId", "proposalId") REFERENCES api_daily_proposal("tenantId", id) ON DELETE CASCADE
);

CREATE TABLE api_commit_receipt (
  "tenantId" TEXT NOT NULL,
  id TEXT NOT NULL CHECK (id ~ '^rcpt-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'),
  "proposalId" TEXT NOT NULL,
  "requestId" TEXT NOT NULL,
  "receiptJson" JSONB NOT NULL,
  "createdAt" TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY ("tenantId", id),
  UNIQUE ("tenantId", "proposalId"),
  UNIQUE ("tenantId", "requestId"),
  FOREIGN KEY ("tenantId", "proposalId") REFERENCES api_daily_proposal("tenantId", id) ON DELETE RESTRICT,
  FOREIGN KEY ("tenantId", "requestId") REFERENCES api_request("tenantId", "requestId") ON DELETE RESTRICT
);

-- Booking: each metro-day's holds (a versioned JSON state), offer sets with their offers, and booking receipts.
CREATE TABLE api_booking_day (
  "tenantId" TEXT NOT NULL REFERENCES tenant(id) ON UPDATE CASCADE ON DELETE RESTRICT,
  "metroId" TEXT NOT NULL CHECK (length("metroId") BETWEEN 1 AND 128),
  "serviceDate" DATE NOT NULL,
  version INTEGER NOT NULL CHECK (version >= 0),
  "stateJson" JSONB,
  "updatedAt" TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY ("tenantId", "metroId", "serviceDate"),
  CHECK ((version = 0) = ("stateJson" IS NULL))
);

CREATE TABLE api_booking_offer_set (
  "tenantId" TEXT NOT NULL,
  id TEXT NOT NULL CHECK (id ~ '^set-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'),
  "requestId" TEXT NOT NULL,
  "jobId" TEXT NOT NULL CHECK (length("jobId") BETWEEN 1 AND 128),
  "metroId" TEXT NOT NULL CHECK (length("metroId") BETWEEN 1 AND 128),
  "expiresAt" TIMESTAMPTZ NOT NULL,
  status TEXT NOT NULL CHECK (status IN ('ACTIVE', 'SELECTED', 'RELEASED', 'SUPERSEDED', 'CONFIRMED')),
  "offerSetJson" JSONB NOT NULL,
  "createdAt" TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY ("tenantId", id),
  UNIQUE ("tenantId", "requestId"),
  FOREIGN KEY ("tenantId", "requestId") REFERENCES api_request("tenantId", "requestId") ON DELETE RESTRICT
);
CREATE INDEX api_booking_offer_set_job ON api_booking_offer_set ("tenantId", "jobId");

CREATE TABLE api_booking_offer (
  "tenantId" TEXT NOT NULL,
  id TEXT NOT NULL CHECK (id ~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'),
  "offerSetId" TEXT NOT NULL,
  "holdId" TEXT NOT NULL CHECK ("holdId" ~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'),
  "serviceDate" DATE NOT NULL,
  "technicianId" TEXT NOT NULL CHECK (length("technicianId") BETWEEN 1 AND 128),
  "windowStart" TIMESTAMPTZ NOT NULL,
  "windowEnd" TIMESTAMPTZ NOT NULL,
  status TEXT NOT NULL CHECK (status IN ('HELD', 'SELECTED', 'RELEASED', 'SUPERSEDED', 'LOST', 'CONFIRMED')),
  PRIMARY KEY ("tenantId", id),
  UNIQUE ("tenantId", "holdId"),
  FOREIGN KEY ("tenantId", "offerSetId") REFERENCES api_booking_offer_set("tenantId", id) ON DELETE CASCADE,
  CHECK ("windowStart" < "windowEnd")
);
CREATE INDEX api_booking_offer_set_member ON api_booking_offer ("tenantId", "offerSetId");

CREATE TABLE api_booking_receipt (
  "tenantId" TEXT NOT NULL,
  id TEXT NOT NULL CHECK (id ~ '^rcpt-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'),
  "offerId" TEXT NOT NULL,
  "requestId" TEXT NOT NULL,
  "receiptJson" JSONB NOT NULL,
  "createdAt" TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY ("tenantId", id),
  UNIQUE ("tenantId", "offerId"),
  UNIQUE ("tenantId", "requestId"),
  FOREIGN KEY ("tenantId", "offerId") REFERENCES api_booking_offer("tenantId", id) ON DELETE RESTRICT,
  FOREIGN KEY ("tenantId", "requestId") REFERENCES api_request("tenantId", "requestId") ON DELETE RESTRICT
);

-- Road legs keyed by routing identity (mapVersion); it holds coordinates only, never client data.
CREATE TABLE road_route_cache (
  id TEXT PRIMARY KEY,
  "originKey" TEXT NOT NULL,
  "destinationKey" TEXT NOT NULL,
  profile TEXT NOT NULL,
  "mapVersion" TEXT NOT NULL,
  seconds INTEGER,
  meters INTEGER,
  routable BOOLEAN NOT NULL,
  "fetchedAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX "road_route_cache_originKey_destinationKey_profile_mapVersion_ke" ON road_route_cache ("originKey", "destinationKey", profile, "mapVersion");
CREATE INDEX road_route_cache_fetched_at_idx ON road_route_cache ("fetchedAt");

-- Row-level security is the second guard behind the application's tenant filters. Public API code runs every
-- transaction as scheduler_tenant with app.tenant_id set; that role is not the table owner, so the policies apply to it.
-- An unset tenant matches no row.
DO $$
BEGIN
  IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'scheduler_tenant') THEN
    CREATE ROLE scheduler_tenant NOLOGIN NOSUPERUSER NOBYPASSRLS NOINHERIT;
  END IF;
  EXECUTE format('GRANT scheduler_tenant TO %I', current_user);
  EXECUTE format('GRANT USAGE ON SCHEMA %I TO scheduler_tenant', current_schema());
END $$;

GRANT SELECT, INSERT, UPDATE, DELETE ON api_request TO scheduler_tenant;
GRANT SELECT, INSERT, UPDATE ON api_daily_proposal TO scheduler_tenant;
GRANT SELECT, INSERT ON api_proposal_technician_day TO scheduler_tenant;
GRANT SELECT, INSERT ON api_commit_receipt TO scheduler_tenant;
GRANT SELECT, INSERT, UPDATE ON api_booking_day TO scheduler_tenant;
GRANT SELECT, INSERT, UPDATE ON api_booking_offer_set TO scheduler_tenant;
GRANT SELECT, INSERT, UPDATE ON api_booking_offer TO scheduler_tenant;
GRANT SELECT, INSERT ON api_booking_receipt TO scheduler_tenant;

ALTER TABLE api_request ENABLE ROW LEVEL SECURITY;
ALTER TABLE api_daily_proposal ENABLE ROW LEVEL SECURITY;
ALTER TABLE api_proposal_technician_day ENABLE ROW LEVEL SECURITY;
ALTER TABLE api_commit_receipt ENABLE ROW LEVEL SECURITY;
ALTER TABLE api_booking_day ENABLE ROW LEVEL SECURITY;
ALTER TABLE api_booking_offer_set ENABLE ROW LEVEL SECURITY;
ALTER TABLE api_booking_offer ENABLE ROW LEVEL SECURITY;
ALTER TABLE api_booking_receipt ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON api_request USING ("tenantId" = current_setting('app.tenant_id', true)) WITH CHECK ("tenantId" = current_setting('app.tenant_id', true));
CREATE POLICY tenant_isolation ON api_daily_proposal USING ("tenantId" = current_setting('app.tenant_id', true)) WITH CHECK ("tenantId" = current_setting('app.tenant_id', true));
CREATE POLICY tenant_isolation ON api_proposal_technician_day USING ("tenantId" = current_setting('app.tenant_id', true)) WITH CHECK ("tenantId" = current_setting('app.tenant_id', true));
CREATE POLICY tenant_isolation ON api_commit_receipt USING ("tenantId" = current_setting('app.tenant_id', true)) WITH CHECK ("tenantId" = current_setting('app.tenant_id', true));
CREATE POLICY tenant_isolation ON api_booking_day USING ("tenantId" = current_setting('app.tenant_id', true)) WITH CHECK ("tenantId" = current_setting('app.tenant_id', true));
CREATE POLICY tenant_isolation ON api_booking_offer_set USING ("tenantId" = current_setting('app.tenant_id', true)) WITH CHECK ("tenantId" = current_setting('app.tenant_id', true));
CREATE POLICY tenant_isolation ON api_booking_offer USING ("tenantId" = current_setting('app.tenant_id', true)) WITH CHECK ("tenantId" = current_setting('app.tenant_id', true));
CREATE POLICY tenant_isolation ON api_booking_receipt USING ("tenantId" = current_setting('app.tenant_id', true)) WITH CHECK ("tenantId" = current_setting('app.tenant_id', true));

-- scheduler_app is the running scheduler's role; its login is created by --command=migrate. It reads tenants and token
-- digests, keeps the route cache, runs the purge, reads the migration history (to refuse an unmigrated schema), and
-- acts as scheduler_tenant. NOINHERIT: scheduler_tenant's table grants apply only after SET LOCAL ROLE
-- scheduler_tenant, never to the login directly. It cannot change the schema or write tenants and tokens.
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'scheduler_app') THEN
    CREATE ROLE scheduler_app NOLOGIN NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE NOINHERIT;
  END IF;
  EXECUTE format('GRANT USAGE ON SCHEMA %I TO scheduler_app', current_schema());
END $$;
GRANT SELECT ON tenant, tenant_api_token TO scheduler_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON road_route_cache TO scheduler_app;
GRANT SELECT ON flyway_schema_history TO scheduler_app;
GRANT scheduler_tenant TO scheduler_app;

-- Deletes one batch per thin store table of rows older than cutoff, children before parents, and returns how many
-- rows it deleted; the caller repeats it until it returns 0. It runs as the table owner, so it sees every tenant's
-- rows, and it can only delete by age: a cutoff less than a day old is refused. Rows another replica's purge has
-- locked are skipped. A row still referenced by a newer one (a receipt for an older offer set) stays until both age.
CREATE FUNCTION scheduler_purge(cutoff TIMESTAMPTZ, batch INTEGER) RETURNS INTEGER
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

  RETURN total;
END $$;

-- A definer function must not resolve names through the caller's search path.
DO $$
BEGIN
  EXECUTE format('ALTER FUNCTION scheduler_purge(TIMESTAMPTZ, INTEGER) SET search_path = %I, pg_temp', current_schema());
END $$;
REVOKE ALL ON FUNCTION scheduler_purge(TIMESTAMPTZ, INTEGER) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION scheduler_purge(TIMESTAMPTZ, INTEGER) TO scheduler_app;
