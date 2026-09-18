ALTER TABLE optimization_run ADD COLUMN "baselineAssignments" JSONB NOT NULL DEFAULT '[]'::jsonb;
