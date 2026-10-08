-- One overnight attempt per metro-day per night across all scheduler replicas.
-- "nightOf" is the America/Chicago date on which the overnight run fired. Rows written
-- before this migration have no recorded night and stay NULL; NULLs never conflict.
ALTER TABLE overnight_optimization_attempt ADD COLUMN "nightOf" DATE;
CREATE UNIQUE INDEX overnight_optimization_attempt_night_claim
  ON overnight_optimization_attempt ("metroId","serviceDate","nightOf");
