ALTER TABLE "slot_hold" ADD COLUMN "bookingOptimizationId" TEXT;

CREATE TABLE "booking_optimization" (
  "id" TEXT NOT NULL,
  "jobId" TEXT NOT NULL,
  "offerToken" TEXT NOT NULL,
  "algorithmVersion" TEXT NOT NULL,
  "strategy" TEXT NOT NULL,
  "candidateCount" INTEGER NOT NULL,
  "qualifyingOfferCount" INTEGER NOT NULL,
  "returnedOffers" JSONB NOT NULL,
  "solverMs" INTEGER NOT NULL DEFAULT 0,
  "fallbackStatus" TEXT,
  "travelWarnings" JSONB NOT NULL,
  "confirmedHoldId" TEXT,
  "createdAt" TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT "booking_optimization_pkey" PRIMARY KEY ("id")
);

CREATE UNIQUE INDEX "booking_optimization_offerToken_key" ON "booking_optimization"("offerToken");
CREATE INDEX "booking_optimization_jobId_createdAt_idx" ON "booking_optimization"("jobId", "createdAt");
CREATE INDEX "slot_hold_bookingOptimizationId_idx" ON "slot_hold"("bookingOptimizationId");

CREATE TABLE "optimization_run" (
  "id" TEXT NOT NULL,
  "metroId" TEXT NOT NULL,
  "serviceDate" TIMESTAMPTZ NOT NULL,
  "scheduleVersions" JSONB NOT NULL,
  "weights" JSONB NOT NULL,
  "solverStatus" TEXT NOT NULL,
  "solveMs" INTEGER NOT NULL,
  "routeSummaryBefore" JSONB NOT NULL,
  "routeSummaryAfter" JSONB NOT NULL,
  "warnings" JSONB NOT NULL,
  "proposedAssignments" JSONB NOT NULL,
  "objectiveImprovement" INTEGER NOT NULL,
  "churnCost" INTEGER NOT NULL,
  "status" TEXT NOT NULL,
  "reason" TEXT,
  "createdAt" TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "appliedAt" TIMESTAMPTZ,
  CONSTRAINT "optimization_run_pkey" PRIMARY KEY ("id")
);

CREATE INDEX "optimization_run_metroId_serviceDate_createdAt_idx"
  ON "optimization_run"("metroId", "serviceDate", "createdAt");

CREATE TABLE "optimization_change" (
  "id" TEXT NOT NULL,
  "runId" TEXT NOT NULL,
  "appointmentId" TEXT NOT NULL,
  "fromTechnicianId" TEXT NOT NULL,
  "toTechnicianId" TEXT NOT NULL,
  "fromSequence" INTEGER NOT NULL,
  "toSequence" INTEGER NOT NULL,
  "fromPlannedArrivalMin" INTEGER NOT NULL,
  "toPlannedArrivalMin" INTEGER NOT NULL,
  CONSTRAINT "optimization_change_pkey" PRIMARY KEY ("id")
);

CREATE INDEX "optimization_change_runId_idx" ON "optimization_change"("runId");

ALTER TABLE "booking_optimization"
  ADD CONSTRAINT "booking_optimization_jobId_fkey" FOREIGN KEY ("jobId") REFERENCES "job"("id") ON DELETE RESTRICT ON UPDATE CASCADE;
ALTER TABLE "slot_hold"
  ADD CONSTRAINT "slot_hold_bookingOptimizationId_fkey" FOREIGN KEY ("bookingOptimizationId") REFERENCES "booking_optimization"("id") ON DELETE SET NULL ON UPDATE CASCADE;
ALTER TABLE "optimization_run"
  ADD CONSTRAINT "optimization_run_metroId_fkey" FOREIGN KEY ("metroId") REFERENCES "metro"("id") ON DELETE RESTRICT ON UPDATE CASCADE;
ALTER TABLE "optimization_change"
  ADD CONSTRAINT "optimization_change_runId_fkey" FOREIGN KEY ("runId") REFERENCES "optimization_run"("id") ON DELETE CASCADE ON UPDATE CASCADE;
