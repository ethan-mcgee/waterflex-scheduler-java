-- Recurring demand: customers' recurring services and the time reserved for them in technicians' days. A reservation
-- is a planning mark the scheduler does not know yet; booking one takes its work order in.
CREATE TYPE "RecurrenceUnit" AS ENUM ('WEEK', 'MONTH');
CREATE TYPE "ReservationStatus" AS ENUM ('RESERVED', 'BOOKED', 'RELEASED');

CREATE TABLE "recurring_series" (
  "id" TEXT NOT NULL,
  "clientId" TEXT NOT NULL,
  "accountId" TEXT NOT NULL,
  "serviceId" TEXT NOT NULL,
  "workOrderType" TEXT,
  "intervalUnit" "RecurrenceUnit" NOT NULL,
  "intervalCount" INTEGER NOT NULL,
  "startDate" DATE NOT NULL,
  "lastVisitDate" DATE,
  "active" BOOLEAN NOT NULL DEFAULT true,
  "createdAt" TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT "recurring_series_pkey" PRIMARY KEY ("id"),
  CONSTRAINT "recurring_series_interval" CHECK (("intervalUnit" = 'WEEK' AND "intervalCount" BETWEEN 1 AND 52)
    OR ("intervalUnit" = 'MONTH' AND "intervalCount" BETWEEN 1 AND 24))
);
CREATE UNIQUE INDEX "recurring_series_accountId_serviceId_key" ON "recurring_series"("accountId", "serviceId");
CREATE INDEX "recurring_series_client" ON "recurring_series"("clientId");
ALTER TABLE "recurring_series" ADD CONSTRAINT "recurring_series_clientId_fkey" FOREIGN KEY ("clientId") REFERENCES "client"("id") ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE "recurring_series" ADD CONSTRAINT "recurring_series_accountId_fkey" FOREIGN KEY ("accountId") REFERENCES "account"("id") ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE "recurring_series" ADD CONSTRAINT "recurring_series_serviceId_fkey" FOREIGN KEY ("serviceId") REFERENCES "service_catalog"("id") ON DELETE RESTRICT ON UPDATE CASCADE;

CREATE TABLE "recurring_reason_code" (
  "seriesId" TEXT NOT NULL,
  "position" INTEGER NOT NULL,
  "reasonCodeId" TEXT NOT NULL,
  CONSTRAINT "recurring_reason_code_pkey" PRIMARY KEY ("seriesId", "position"),
  CONSTRAINT "recurring_reason_code_position" CHECK ("position" IN (2, 3))
);
CREATE UNIQUE INDEX "recurring_reason_code_seriesId_reasonCodeId_key" ON "recurring_reason_code"("seriesId", "reasonCodeId");
ALTER TABLE "recurring_reason_code" ADD CONSTRAINT "recurring_reason_code_seriesId_fkey" FOREIGN KEY ("seriesId") REFERENCES "recurring_series"("id") ON DELETE CASCADE ON UPDATE CASCADE;
ALTER TABLE "recurring_reason_code" ADD CONSTRAINT "recurring_reason_code_reasonCodeId_fkey" FOREIGN KEY ("reasonCodeId") REFERENCES "service_catalog"("id") ON DELETE RESTRICT ON UPDATE CASCADE;

CREATE TABLE "reservation" (
  "id" TEXT NOT NULL,
  "seriesId" TEXT NOT NULL,
  "dueDate" DATE NOT NULL,
  "depotId" TEXT NOT NULL,
  "technicianId" TEXT NOT NULL,
  "serviceDate" DATE NOT NULL,
  "startMin" INTEGER NOT NULL,
  "endMin" INTEGER NOT NULL,
  "status" "ReservationStatus" NOT NULL DEFAULT 'RESERVED',
  "jobId" TEXT,
  "createdAt" TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT "reservation_pkey" PRIMARY KEY ("id"),
  CONSTRAINT "reservation_minutes" CHECK ("startMin" >= 0 AND "endMin" > "startMin" AND "endMin" <= 1440),
  CONSTRAINT "reservation_booked_job" CHECK (("status" = 'BOOKED') = ("jobId" IS NOT NULL))
);
CREATE UNIQUE INDEX "reservation_jobId_key" ON "reservation"("jobId");
CREATE INDEX "reservation_depot_day" ON "reservation"("depotId", "serviceDate");
CREATE INDEX "reservation_technician_day" ON "reservation"("technicianId", "serviceDate");
-- A series holds at most one live reservation for each due date.
CREATE UNIQUE INDEX "reservation_live_due" ON "reservation"("seriesId", "dueDate") WHERE "status" <> 'RELEASED';
ALTER TABLE "reservation" ADD CONSTRAINT "reservation_seriesId_fkey" FOREIGN KEY ("seriesId") REFERENCES "recurring_series"("id") ON DELETE CASCADE ON UPDATE CASCADE;
ALTER TABLE "reservation" ADD CONSTRAINT "reservation_depotId_fkey" FOREIGN KEY ("depotId") REFERENCES "depot"("id") ON DELETE RESTRICT ON UPDATE CASCADE;
ALTER TABLE "reservation" ADD CONSTRAINT "reservation_technicianId_fkey" FOREIGN KEY ("technicianId") REFERENCES "technician"("id") ON DELETE RESTRICT ON UPDATE CASCADE;
ALTER TABLE "reservation" ADD CONSTRAINT "reservation_jobId_fkey" FOREIGN KEY ("jobId") REFERENCES "job"("id") ON DELETE CASCADE ON UPDATE CASCADE;
