-- Every policy rule becomes the client's own, sent in Snapshot.policy with every calculation; the scheduler holds none.
-- Each client with settings starts from the shared values the scheduler read from omaha_setting until now. A client
-- row without both shared values fails the migration rather than inventing them. The utilization threshold is not
-- carried over: it only fed an overtime check that the zero-overtime policy always answers no.
ALTER TABLE client_solver_settings
  ADD COLUMN "bookingDeadlineMs" INTEGER,
  ADD COLUMN "regularWindowThreshold" INTEGER;

UPDATE client_solver_settings SET
  "bookingDeadlineMs" = (SELECT value::integer FROM omaha_setting WHERE key = 'booking_deadline_ms'),
  "regularWindowThreshold" = (SELECT value::integer FROM omaha_setting WHERE key = 'regular_window_threshold');

DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM client_solver_settings WHERE "bookingDeadlineMs" IS NULL OR "regularWindowThreshold" IS NULL) THEN
    RAISE EXCEPTION 'omaha_setting lacks booking_deadline_ms or regular_window_threshold; set them before migrating';
  END IF;
END $$;

ALTER TABLE client_solver_settings
  ALTER COLUMN "bookingDeadlineMs" SET NOT NULL,
  ALTER COLUMN "regularWindowThreshold" SET NOT NULL,
  ADD CONSTRAINT client_solver_settings_booking_deadline CHECK ("bookingDeadlineMs" BETWEEN 1000 AND 5000),
  ADD CONSTRAINT client_solver_settings_window_threshold CHECK ("regularWindowThreshold" BETWEEN 0 AND 1000);

DROP TABLE omaha_setting;
