ALTER TABLE booking_offer ADD COLUMN "overtimeAuthorized" BOOLEAN NOT NULL DEFAULT false;

-- Preserve explicitly recorded overtime in offers issued before this policy migration.
-- New searches must independently establish authorization before setting this flag.
UPDATE booking_offer SET "overtimeAuthorized" = true
WHERE "incrementalOvertimeMinutes" > 0;
