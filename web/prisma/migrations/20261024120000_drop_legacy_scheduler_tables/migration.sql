-- The scheduler's database path and the portal's internal booking path are gone (P7c). These tables and
-- columns were written only by that code; the public API keeps its state in the api_* tables and the
-- portal in portal_api_*. No CASCADE: an unexpected dependent fails the migration instead of being dropped.

-- Optimization runs and the scheduler's own attempt receipts.
DROP TABLE "optimization_change";
DROP TABLE "optimization_run";
DROP TABLE "daily_calculation_attempt";
DROP TABLE "overnight_optimization_attempt";

-- Internal booking holds, offers and searches. The obligation view joined holds and dependencies.
DROP VIEW "reservation_obligation";
DROP TABLE "reservation_dependency";
DROP TABLE "reservation_arrangement";
DROP TABLE "booking_search_request";
DROP TABLE "slot_hold";
DROP TABLE "booking_offer";
DROP TABLE "booking_offer_set";
DROP TABLE "booking_optimization";
DROP TABLE "monetary_migration_receipt";

-- Never read: an outbox no code writes, seeded scoring weights and the original travel time matrix.
DROP TABLE "outbound_event";
DROP TABLE "scoring_weight";
DROP TABLE "travel_time";
DROP TYPE "TimeBucket";
DROP TYPE "DayType";

-- Columns nothing reads: job priority, the retired repair overtime approval, a seeded service flag, and
-- segment timing only the deleted optimizer wrote.
ALTER TABLE "job" DROP COLUMN "priority";
ALTER TABLE "time_off_request" DROP COLUMN "additionalOvertimeApproved";
ALTER TABLE "service_catalog" DROP COLUMN "startsAtDepot";
ALTER TABLE "schedule_day" DROP COLUMN "routeTiming";
