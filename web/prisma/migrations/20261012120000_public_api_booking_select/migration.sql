-- Select and release for public API booking offers. Selecting keeps one offer's hold and releases the others in its
-- set; releasing ends every hold in the set.

ALTER TABLE api_request DROP CONSTRAINT api_request_operation_check;
ALTER TABLE api_request ADD CONSTRAINT api_request_operation_check
  CHECK (operation IN ('DAILY_PROPOSAL', 'DAILY_COMMIT', 'BOOKING_OFFERS', 'BOOKING_SELECT', 'BOOKING_RELEASE'));

ALTER TABLE api_booking_offer_set DROP CONSTRAINT api_booking_offer_set_status_check;
ALTER TABLE api_booking_offer_set ADD CONSTRAINT api_booking_offer_set_status_check CHECK (status IN ('ACTIVE', 'SELECTED', 'RELEASED', 'SUPERSEDED'));

ALTER TABLE api_booking_offer DROP CONSTRAINT api_booking_offer_status_check;
ALTER TABLE api_booking_offer ADD CONSTRAINT api_booking_offer_status_check CHECK (status IN ('HELD', 'SELECTED', 'RELEASED', 'SUPERSEDED', 'LOST'));
