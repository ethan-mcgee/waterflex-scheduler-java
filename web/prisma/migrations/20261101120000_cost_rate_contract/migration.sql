-- The scheduler's cost rate contract (spec 1.7.0): at most 500 an hour and 25 a mile, which the scheduler prices exactly
-- in integer arithmetic. The columns already keep four decimal places. A stored rate above a cap fails this migration
-- rather than being changed: such a client's settings must be corrected by hand, and nothing is assumed for it.
ALTER TABLE client_solver_settings
  ADD CONSTRAINT client_solver_settings_regular_hourly_cap CHECK ("regularHourly" <= 500),
  ADD CONSTRAINT client_solver_settings_overtime_hourly_cap CHECK ("overtimeHourly" <= 500),
  ADD CONSTRAINT client_solver_settings_mileage_cap CHECK ("mileagePerMile" <= 25);
