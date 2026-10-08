-- Commit protocol for the public /api/v1 surface. A commit is a request of its own (operation DAILY_COMMIT),
-- and its receipt records the assignments the host was told to write. A proposal is committed at most once.

ALTER TABLE api_request DROP CONSTRAINT api_request_operation_check;
ALTER TABLE api_request ADD CONSTRAINT api_request_operation_check CHECK (operation IN ('DAILY_PROPOSAL', 'DAILY_COMMIT'));

-- The commit checks the 6 a.m. cutoff again in the snapshot's own time zone. There is no default: a proposal
-- stored before this column existed has no known zone, so this migration fails rather than guess one.
ALTER TABLE api_daily_proposal ADD COLUMN "timeZone" TEXT NOT NULL CHECK (length("timeZone") BETWEEN 1 AND 64);

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
  FOREIGN KEY ("tenantId", "proposalId") REFERENCES api_daily_proposal ("tenantId", id) ON DELETE RESTRICT,
  FOREIGN KEY ("tenantId", "requestId") REFERENCES api_request ("tenantId", "requestId") ON DELETE RESTRICT
);

GRANT SELECT, INSERT ON api_commit_receipt TO scheduler_tenant;
ALTER TABLE api_commit_receipt ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON api_commit_receipt USING ("tenantId" = current_setting('app.tenant_id', true)) WITH CHECK ("tenantId" = current_setting('app.tenant_id', true));
