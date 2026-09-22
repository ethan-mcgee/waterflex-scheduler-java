-- Add a structured reason category to time-off requests, alongside the
-- existing free-text "reason" explanation. Existing rows backfill to
-- 'Other' since they predate the category selector.
ALTER TABLE "time_off_request" ADD COLUMN "category" TEXT NOT NULL DEFAULT 'Other';
