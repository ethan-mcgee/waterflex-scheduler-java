-- Thin store for the public /api/v1 surface. It holds only what the scheduler itself owns: request
-- idempotency, and proposals with the host technician-day timestamps they were computed from. Commit
-- receipts arrive with the commit protocol. No master data. Host IDs are unique only within a tenant, so every key starts with "tenantId".

CREATE TABLE api_request (
  "tenantId" TEXT NOT NULL REFERENCES tenant(id) ON DELETE RESTRICT ON UPDATE CASCADE,
  "requestId" TEXT NOT NULL CHECK ("requestId" ~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'),
  operation TEXT NOT NULL CHECK (operation IN ('DAILY_PROPOSAL')),
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
  PRIMARY KEY ("tenantId", id),
  UNIQUE ("tenantId", "requestId"),
  FOREIGN KEY ("tenantId", "requestId") REFERENCES api_request ("tenantId", "requestId") ON DELETE RESTRICT,
  CHECK ((status = 'COMMITTED') = ("committedAt" IS NOT NULL))
);
CREATE INDEX api_daily_proposal_day ON api_daily_proposal ("tenantId", "metroId", "serviceDate");

-- The host timestamp of every technician-day a proposal covers. Stored as the canonical ISO-8601 instant
-- text so nanosecond precision survives exactly; a commit compares these with the host's current values.
CREATE TABLE api_proposal_technician_day (
  "tenantId" TEXT NOT NULL,
  "proposalId" TEXT NOT NULL,
  "technicianId" TEXT NOT NULL CHECK (length("technicianId") BETWEEN 1 AND 128),
  "serviceDate" DATE NOT NULL,
  "lastModified" TEXT NOT NULL CHECK ("lastModified" ~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}(:[0-9]{2}(\.[0-9]{1,9})?)?Z$'),
  PRIMARY KEY ("tenantId", "proposalId", "technicianId", "serviceDate"),
  FOREIGN KEY ("tenantId", "proposalId") REFERENCES api_daily_proposal ("tenantId", id) ON DELETE CASCADE
);

-- Row-level security is the second guard behind the application's tenant filters. Public API code runs
-- every transaction as scheduler_tenant with app.tenant_id set; that role is not the table owner, so the
-- policies apply to it even when the connecting role is a superuser. An unset tenant matches no row.
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

ALTER TABLE api_request ENABLE ROW LEVEL SECURITY;
ALTER TABLE api_daily_proposal ENABLE ROW LEVEL SECURITY;
ALTER TABLE api_proposal_technician_day ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON api_request USING ("tenantId" = current_setting('app.tenant_id', true)) WITH CHECK ("tenantId" = current_setting('app.tenant_id', true));
CREATE POLICY tenant_isolation ON api_daily_proposal USING ("tenantId" = current_setting('app.tenant_id', true)) WITH CHECK ("tenantId" = current_setting('app.tenant_id', true));
CREATE POLICY tenant_isolation ON api_proposal_technician_day USING ("tenantId" = current_setting('app.tenant_id', true)) WITH CHECK ("tenantId" = current_setting('app.tenant_id', true));
