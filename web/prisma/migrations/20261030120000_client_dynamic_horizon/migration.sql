-- Each client's booking horizon mode. FIXED books bookingHorizonWeekdays weekdays from tomorrow, as before. DYNAMIC books
-- through horizonLeadWeekdays weekdays past the furthest weekday whose route spans fill horizonFullPercent of the
-- technicians' open minutes, never past bookingHorizonWeekdays, which becomes the cap. Every client with settings starts
-- on FIXED with lead 2 and full 85, so nothing changes until a client chooses DYNAMIC. From then on the values are
-- required and never assumed.
ALTER TABLE client_solver_settings
  ADD COLUMN "horizonMode" TEXT NOT NULL DEFAULT 'FIXED' CHECK ("horizonMode" IN ('FIXED', 'DYNAMIC')),
  ADD COLUMN "horizonLeadWeekdays" INTEGER NOT NULL DEFAULT 2 CHECK ("horizonLeadWeekdays" BETWEEN 1 AND 5),
  ADD COLUMN "horizonFullPercent" INTEGER NOT NULL DEFAULT 85 CHECK ("horizonFullPercent" BETWEEN 50 AND 100);
ALTER TABLE client_solver_settings
  ALTER COLUMN "horizonMode" DROP DEFAULT,
  ALTER COLUMN "horizonLeadWeekdays" DROP DEFAULT,
  ALTER COLUMN "horizonFullPercent" DROP DEFAULT;
