CREATE TABLE "booking_test_generation" (
  "runId" TEXT PRIMARY KEY REFERENCES "booking_test_run"("id") ON DELETE CASCADE,
  "acceptedInputs" JSONB NOT NULL DEFAULT '[]',
  "randomState" BIGINT NOT NULL,
  "roundRobinCursor" INTEGER NOT NULL DEFAULT 0,
  "pendingCandidates" JSONB,
  "candidatesTried" BIGINT NOT NULL DEFAULT 0,
  "batches" BIGINT NOT NULL DEFAULT 0,
  "elapsedMs" BIGINT NOT NULL DEFAULT 0,
  "consecutiveNoProgressBatches" BIGINT NOT NULL DEFAULT 0,
  "completedAt" TIMESTAMP(3)
);
