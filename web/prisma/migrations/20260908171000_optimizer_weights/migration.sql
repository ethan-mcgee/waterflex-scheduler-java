INSERT INTO "scoring_weight" ("id", "key", "value", "updatedAt")
VALUES
  ('optimizer-weight-drive', 'w_opt_drive', 1.0, CURRENT_TIMESTAMP),
  ('optimizer-weight-balance', 'w_opt_balance', 1.0, CURRENT_TIMESTAMP),
  ('optimizer-weight-overtime', 'w_opt_overtime', 10.0, CURRENT_TIMESTAMP)
ON CONFLICT ("key") DO NOTHING;
