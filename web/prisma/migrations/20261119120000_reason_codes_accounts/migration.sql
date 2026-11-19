-- Reason codes and WaterFlex accounts. The service catalog is the reason code catalog: a job's first code stays
-- job."serviceId" (what the scheduler matches qualifications on), and its second and third codes are rows here.
-- Jobs taken in before this keep a null suggestion and say nothing about calling first.
ALTER TABLE "job" ADD COLUMN "suggestedDurationMin" INTEGER,
  ADD COLUMN "durationOverridden" BOOLEAN NOT NULL DEFAULT false,
  ADD COLUMN "workOrderType" TEXT,
  ADD COLUMN "purchaseOrder" TEXT,
  ADD COLUMN "technicianMessage" TEXT,
  ADD COLUMN "callFirst" BOOLEAN,
  ADD COLUMN "customerRequest" TEXT;

CREATE TABLE "job_reason_code" (
  "jobId" TEXT NOT NULL,
  "position" INTEGER NOT NULL,
  "reasonCodeId" TEXT NOT NULL,
  CONSTRAINT "job_reason_code_pkey" PRIMARY KEY ("jobId", "position"),
  CONSTRAINT "job_reason_code_position" CHECK ("position" IN (2, 3))
);
CREATE UNIQUE INDEX "job_reason_code_jobId_reasonCodeId_key" ON "job_reason_code"("jobId", "reasonCodeId");
ALTER TABLE "job_reason_code" ADD CONSTRAINT "job_reason_code_jobId_fkey" FOREIGN KEY ("jobId") REFERENCES "job"("id") ON DELETE CASCADE ON UPDATE CASCADE;
ALTER TABLE "job_reason_code" ADD CONSTRAINT "job_reason_code_reasonCodeId_fkey" FOREIGN KEY ("reasonCodeId") REFERENCES "service_catalog"("id") ON DELETE RESTRICT ON UPDATE CASCADE;

-- An account's coordinates are WaterFlex's: both present or both missing.
CREATE TABLE "account" (
  "id" TEXT NOT NULL,
  "clientId" TEXT NOT NULL,
  "accountNumber" TEXT NOT NULL,
  "name" TEXT,
  "phone" TEXT,
  "email" TEXT,
  "line1" TEXT NOT NULL,
  "line2" TEXT,
  "city" TEXT NOT NULL,
  "state" TEXT NOT NULL,
  "postalCode" TEXT NOT NULL,
  "lat" DOUBLE PRECISION,
  "lng" DOUBLE PRECISION,
  "source" TEXT NOT NULL,
  "importedAt" TIMESTAMPTZ(6) NOT NULL,
  CONSTRAINT "account_pkey" PRIMARY KEY ("id"),
  CONSTRAINT "account_coordinates" CHECK (("lat" IS NULL) = ("lng" IS NULL))
);
CREATE UNIQUE INDEX "account_clientId_accountNumber_key" ON "account"("clientId", "accountNumber");
ALTER TABLE "account" ADD CONSTRAINT "account_clientId_fkey" FOREIGN KEY ("clientId") REFERENCES "client"("id") ON DELETE RESTRICT ON UPDATE RESTRICT;

ALTER TABLE "customer" ADD COLUMN "accountId" TEXT;
CREATE UNIQUE INDEX "customer_accountId_key" ON "customer"("accountId");
ALTER TABLE "customer" ADD CONSTRAINT "customer_accountId_fkey" FOREIGN KEY ("accountId") REFERENCES "account"("id") ON DELETE RESTRICT ON UPDATE RESTRICT;
