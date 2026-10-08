-- Confirm for public API booking holds. Confirming a selected hold turns it into the job's appointment in the day's
-- common arrangement; the receipt records the appointments the host was told to write. A hold is confirmed at most once.

ALTER TABLE api_request DROP CONSTRAINT api_request_operation_check;
ALTER TABLE api_request ADD CONSTRAINT api_request_operation_check
  CHECK (operation IN ('DAILY_PROPOSAL', 'DAILY_COMMIT', 'BOOKING_OFFERS', 'BOOKING_SELECT', 'BOOKING_RELEASE', 'BOOKING_CONFIRM'));

ALTER TABLE api_booking_offer_set DROP CONSTRAINT api_booking_offer_set_status_check;
ALTER TABLE api_booking_offer_set ADD CONSTRAINT api_booking_offer_set_status_check
  CHECK (status IN ('ACTIVE', 'SELECTED', 'RELEASED', 'SUPERSEDED', 'CONFIRMED'));

ALTER TABLE api_booking_offer DROP CONSTRAINT api_booking_offer_status_check;
ALTER TABLE api_booking_offer ADD CONSTRAINT api_booking_offer_status_check
  CHECK (status IN ('HELD', 'SELECTED', 'RELEASED', 'SUPERSEDED', 'LOST', 'CONFIRMED'));

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
  FOREIGN KEY ("tenantId", "offerId") REFERENCES api_booking_offer ("tenantId", id) ON DELETE RESTRICT,
  FOREIGN KEY ("tenantId", "requestId") REFERENCES api_request ("tenantId", "requestId") ON DELETE RESTRICT
);

GRANT SELECT, INSERT ON api_booking_receipt TO scheduler_tenant;
ALTER TABLE api_booking_receipt ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON api_booking_receipt USING ("tenantId" = current_setting('app.tenant_id', true)) WITH CHECK ("tenantId" = current_setting('app.tenant_id', true));
