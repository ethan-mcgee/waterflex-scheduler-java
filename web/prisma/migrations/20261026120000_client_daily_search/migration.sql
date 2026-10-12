-- Each client's daily search method and search times, sent with every daily and repair proposal. Every client with
-- settings starts on TABU_KOPT (adopted after the Tabu variant study, run 5d15129b) with the 10 second cost search and
-- 5 second fairness search every proposal had before these choices existed. From then on the values are required and
-- never assumed. Together the two times may be at most 240 seconds; a fairness time of 0 skips that search.
ALTER TABLE client_solver_settings
  ADD COLUMN "dailySearch" TEXT NOT NULL DEFAULT 'TABU_KOPT' CHECK ("dailySearch" IN ('TABU', 'TABU_KOPT')),
  ADD COLUMN "costSearchSeconds" INTEGER NOT NULL DEFAULT 10 CHECK ("costSearchSeconds" BETWEEN 5 AND 240),
  ADD COLUMN "fairnessSearchSeconds" INTEGER NOT NULL DEFAULT 5 CHECK ("fairnessSearchSeconds" BETWEEN 0 AND 240),
  ADD CONSTRAINT client_solver_settings_search_seconds CHECK ("costSearchSeconds" + "fairnessSearchSeconds" <= 240);
ALTER TABLE client_solver_settings
  ALTER COLUMN "dailySearch" DROP DEFAULT,
  ALTER COLUMN "costSearchSeconds" DROP DEFAULT,
  ALTER COLUMN "fairnessSearchSeconds" DROP DEFAULT;
