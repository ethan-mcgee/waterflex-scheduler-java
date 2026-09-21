ALTER TABLE "optimization_run" ADD COLUMN "requestKey" TEXT;
CREATE UNIQUE INDEX "optimization_run_requestKey_key" ON "optimization_run"("requestKey");
CREATE TABLE "booking_test_run" (
  "id" TEXT PRIMARY KEY, "config" JSONB NOT NULL, "horizon" JSONB NOT NULL,
  "status" TEXT NOT NULL DEFAULT 'PAUSED', "revision" INTEGER NOT NULL DEFAULT 0,
  "error" TEXT, "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP, "updatedAt" TIMESTAMP(3) NOT NULL
);
CREATE TABLE "booking_test_request" (
  "id" TEXT PRIMARY KEY, "runId" TEXT NOT NULL REFERENCES "booking_test_run"("id"),
  "ordinal" INTEGER NOT NULL, "input" JSONB NOT NULL, "status" TEXT NOT NULL DEFAULT 'PENDING',
  "offers" JSONB NOT NULL DEFAULT '[]', "selected" JSONB, "attempts" JSONB NOT NULL DEFAULT '[]',
  "appointmentId" TEXT, "serviceDate" TEXT, "error" TEXT, "elapsedMs" INTEGER NOT NULL DEFAULT 0,
  "startedAt" TIMESTAMP(3), "completedAt" TIMESTAMP(3), UNIQUE ("runId", "ordinal")
);
CREATE TABLE "booking_test_preview" (
  "id" TEXT PRIMARY KEY, "runId" TEXT NOT NULL REFERENCES "booking_test_run"("id"),
  "serviceDate" TEXT NOT NULL, "optimizationId" TEXT, "result" JSONB, "error" TEXT,
  UNIQUE ("runId", "serviceDate")
);
