-- Each client's own arrival window length in whole hours, sent with every booking search. Every client with settings
-- starts at 4 hours, the width every booking offer had before this choice existed; from then on the value is required
-- and never assumed.
ALTER TABLE client_solver_settings ADD COLUMN "windowHours" INTEGER NOT NULL DEFAULT 4 CHECK ("windowHours" BETWEEN 1 AND 8);
ALTER TABLE client_solver_settings ALTER COLUMN "windowHours" DROP DEFAULT;
