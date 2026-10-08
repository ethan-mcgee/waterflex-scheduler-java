-- Each client's own booking and routing settings, sent with every public API calculation once booking and dispatch run
-- through it. A client without a row has not configured its settings yet, and nothing is calculated for it; no value is
-- assumed. The default client starts from the values the portal uses today: the omaha_setting rates and fairness
-- allowance, the Compose default of four offers, and the ten-weekday booking horizon.
CREATE TABLE client_solver_settings (
  "clientId" TEXT PRIMARY KEY REFERENCES client(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
  "regularHourly" NUMERIC(14, 4) NOT NULL CHECK ("regularHourly" >= 0),
  "overtimeHourly" NUMERIC(14, 4) NOT NULL CHECK ("overtimeHourly" >= 0),
  "mileagePerMile" NUMERIC(14, 4) NOT NULL CHECK ("mileagePerMile" >= 0),
  "travelBufferPct" NUMERIC(6, 4) NOT NULL CHECK ("travelBufferPct" BETWEEN 0 AND 1),
  "travelBufferMinutes" INTEGER NOT NULL CHECK ("travelBufferMinutes" BETWEEN 0 AND 120),
  "fairnessBudget" NUMERIC(6, 4) NOT NULL CHECK ("fairnessBudget" BETWEEN 0 AND 1),
  "offerLimit" INTEGER NOT NULL CHECK ("offerLimit" BETWEEN 1 AND 4),
  "bookingHorizonWeekdays" INTEGER NOT NULL CHECK ("bookingHorizonWeekdays" BETWEEN 1 AND 15),
  version INTEGER NOT NULL DEFAULT 0 CHECK (version >= 0),
  "updatedAt" TIMESTAMPTZ(6) NOT NULL DEFAULT clock_timestamp()
);

INSERT INTO client_solver_settings ("clientId", "regularHourly", "overtimeHourly", "mileagePerMile", "travelBufferPct",
  "travelBufferMinutes", "fairnessBudget", "offerLimit", "bookingHorizonWeekdays")
SELECT 'default', regular.value, overtime.value, mileage.value, buffer.value, buffer_minutes.value::integer,
  fairness.value, 4, 10
FROM omaha_setting regular, omaha_setting overtime, omaha_setting mileage, omaha_setting buffer,
  omaha_setting buffer_minutes, omaha_setting fairness
WHERE regular.key = 'regular_hourly_dollars' AND overtime.key = 'overtime_hourly_dollars'
  AND mileage.key = 'mileage_dollars_per_mile' AND buffer.key = 'travel_buffer_pct'
  AND buffer_minutes.key = 'travel_buffer_minutes_per_leg' AND fairness.key = 'fairness_cost_allowance'
  AND EXISTS (SELECT 1 FROM client WHERE id = 'default');
