INSERT INTO omaha_setting (key, value, "updatedAt") VALUES
  ('regular_window_threshold', 2, CURRENT_TIMESTAMP),
  ('confirmed_utilization_threshold', 0.9, CURRENT_TIMESTAMP),
  ('fairness_cost_allowance', 0.02, CURRENT_TIMESTAMP),
  ('booking_deadline_ms', 5000, CURRENT_TIMESTAMP)
ON CONFLICT (key) DO NOTHING;

ALTER TABLE optimization_run ADD COLUMN "policyAnalysis" jsonb;

ALTER TABLE omaha_setting ADD CONSTRAINT scheduling_policy_bounds CHECK (
  CASE key
    WHEN 'regular_window_threshold' THEN value >= 0 AND value = floor(value)
    WHEN 'confirmed_utilization_threshold' THEN value BETWEEN 0 AND 1
    WHEN 'fairness_cost_allowance' THEN value BETWEEN 0 AND 1
    WHEN 'booking_deadline_ms' THEN value BETWEEN 1000 AND 5000 AND value = floor(value)
    ELSE true
  END
);
