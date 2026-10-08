-- Repair proposals for the public /api/v1 surface. A repair is stored and committed like a daily proposal; only its
-- request operation differs.

ALTER TABLE api_request DROP CONSTRAINT api_request_operation_check;
ALTER TABLE api_request ADD CONSTRAINT api_request_operation_check
  CHECK (operation IN ('DAILY_PROPOSAL', 'DAILY_COMMIT', 'BOOKING_OFFERS', 'BOOKING_SELECT', 'BOOKING_RELEASE', 'BOOKING_CONFIRM', 'REPAIR_PROPOSAL'));
