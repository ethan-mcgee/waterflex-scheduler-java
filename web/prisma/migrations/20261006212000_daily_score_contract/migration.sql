-- Keep historical proposals and their original evidence readable, but require a fresh
-- preview before applying a proposal calculated with a different score contract.
UPDATE optimization_run SET status='STALE', reason='SCORE_MODEL_CHANGED'
WHERE status IN ('PREVIEW','REPAIR_PREVIEW')
  AND (weights->>'scoreModelVersion') IS DISTINCT FROM 'bendable-decimal-repair-v2';
