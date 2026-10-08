-- Booking for the public /api/v1 surface. Each metro-day has one common arrangement of the host's appointments and
-- every active hold (api_booking_day), as in the portal. Offer sets and offers record what was offered and what became
-- of each hold. No master data beyond what a hold needs to be placed again, which lives in the day's state JSON.

ALTER TABLE api_request DROP CONSTRAINT api_request_operation_check;
ALTER TABLE api_request ADD CONSTRAINT api_request_operation_check CHECK (operation IN ('DAILY_PROPOSAL', 'DAILY_COMMIT', 'BOOKING_OFFERS'));

-- version 0 with no state is the placeholder a first booking on the day locks; every write increments the version.
CREATE TABLE api_booking_day (
  "tenantId" TEXT NOT NULL REFERENCES tenant(id) ON DELETE RESTRICT ON UPDATE CASCADE,
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
  status TEXT NOT NULL CHECK (status IN ('ACTIVE', 'SUPERSEDED')),
  "offerSetJson" JSONB NOT NULL,
  "createdAt" TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY ("tenantId", id),
  UNIQUE ("tenantId", "requestId"),
  FOREIGN KEY ("tenantId", "requestId") REFERENCES api_request ("tenantId", "requestId") ON DELETE RESTRICT
);
CREATE INDEX api_booking_offer_set_job ON api_booking_offer_set ("tenantId", "jobId");

-- One row per offer and its hold. HELD until the hold ends; EXPIRED is not written, since expiry is a time.
CREATE TABLE api_booking_offer (
  "tenantId" TEXT NOT NULL,
  id TEXT NOT NULL CHECK (id ~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'),
  "offerSetId" TEXT NOT NULL,
  "holdId" TEXT NOT NULL CHECK ("holdId" ~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'),
  "serviceDate" DATE NOT NULL,
  "technicianId" TEXT NOT NULL CHECK (length("technicianId") BETWEEN 1 AND 128),
  "windowStart" TIMESTAMPTZ NOT NULL,
  "windowEnd" TIMESTAMPTZ NOT NULL,
  status TEXT NOT NULL CHECK (status IN ('HELD', 'SUPERSEDED', 'LOST')),
  PRIMARY KEY ("tenantId", id),
  UNIQUE ("tenantId", "holdId"),
  FOREIGN KEY ("tenantId", "offerSetId") REFERENCES api_booking_offer_set ("tenantId", id) ON DELETE CASCADE,
  CHECK ("windowStart" < "windowEnd")
);
CREATE INDEX api_booking_offer_set_member ON api_booking_offer ("tenantId", "offerSetId");

GRANT SELECT, INSERT, UPDATE ON api_booking_day TO scheduler_tenant;
GRANT SELECT, INSERT, UPDATE ON api_booking_offer_set TO scheduler_tenant;
GRANT SELECT, INSERT, UPDATE ON api_booking_offer TO scheduler_tenant;

ALTER TABLE api_booking_day ENABLE ROW LEVEL SECURITY;
ALTER TABLE api_booking_offer_set ENABLE ROW LEVEL SECURITY;
ALTER TABLE api_booking_offer ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON api_booking_day USING ("tenantId" = current_setting('app.tenant_id', true)) WITH CHECK ("tenantId" = current_setting('app.tenant_id', true));
CREATE POLICY tenant_isolation ON api_booking_offer_set USING ("tenantId" = current_setting('app.tenant_id', true)) WITH CHECK ("tenantId" = current_setting('app.tenant_id', true));
CREATE POLICY tenant_isolation ON api_booking_offer USING ("tenantId" = current_setting('app.tenant_id', true)) WITH CHECK ("tenantId" = current_setting('app.tenant_id', true));
