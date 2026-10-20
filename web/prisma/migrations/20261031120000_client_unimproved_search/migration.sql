-- Each client's optional unimproved limit: a daily cost or fairness search also ends once its best plan has not improved
-- for this many seconds (spec 1.6.0). NULL is the client's explicit choice that every search spends its whole time,
-- which is how every client starts, so nothing changes until a client sets it. When set it is 1 to the cost search time.
ALTER TABLE client_solver_settings
  ADD COLUMN "unimprovedSearchSeconds" INTEGER CHECK ("unimprovedSearchSeconds" BETWEEN 1 AND 240),
  ADD CONSTRAINT client_solver_settings_unimproved_within_cost
    CHECK ("unimprovedSearchSeconds" IS NULL OR "unimprovedSearchSeconds" <= "costSearchSeconds");
-- TABU_KOPT_SIZE_3 (TABU_KOPT with entity tabu size 3) becomes a client choice beside TABU and TABU_KOPT.
ALTER TABLE client_solver_settings DROP CONSTRAINT "client_solver_settings_dailySearch_check";
ALTER TABLE client_solver_settings
  ADD CONSTRAINT "client_solver_settings_dailySearch_check" CHECK ("dailySearch" IN ('TABU', 'TABU_KOPT', 'TABU_KOPT_SIZE_3'));
