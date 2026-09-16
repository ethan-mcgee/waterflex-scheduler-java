-- CreateEnum
CREATE TYPE "RouteAnchor" AS ENUM ('HOME', 'DEPOT');

-- CreateEnum
CREATE TYPE "JobStatus" AS ENUM ('PENDING', 'SCHEDULED', 'COMPLETED', 'CANCELLED');

-- CreateEnum
CREATE TYPE "TimeBucket" AS ENUM ('AM_PEAK', 'MIDDAY', 'PM_PEAK');

-- CreateEnum
CREATE TYPE "DayType" AS ENUM ('WEEKDAY', 'WEEKEND');

-- CreateTable
CREATE TABLE "metro" (
    "id" TEXT NOT NULL,
    "name" TEXT NOT NULL,
    "timezone" TEXT NOT NULL,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "metro_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "depot" (
    "id" TEXT NOT NULL,
    "metroId" TEXT NOT NULL,
    "name" TEXT NOT NULL,
    "lat" DOUBLE PRECISION NOT NULL,
    "lng" DOUBLE PRECISION NOT NULL,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "depot_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "technician" (
    "id" TEXT NOT NULL,
    "metroId" TEXT NOT NULL,
    "depotId" TEXT,
    "name" TEXT NOT NULL,
    "homeLat" DOUBLE PRECISION NOT NULL,
    "homeLng" DOUBLE PRECISION NOT NULL,
    "startLocation" "RouteAnchor" NOT NULL DEFAULT 'HOME',
    "endLocation" "RouteAnchor" NOT NULL DEFAULT 'HOME',
    "shiftStartMin" INTEGER NOT NULL,
    "shiftEndMin" INTEGER NOT NULL,
    "maxDailyMinutes" INTEGER NOT NULL DEFAULT 540,
    "active" BOOLEAN NOT NULL DEFAULT true,
    "externalId" TEXT,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt" TIMESTAMP(3) NOT NULL,

    CONSTRAINT "technician_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "service_catalog" (
    "id" TEXT NOT NULL,
    "code" TEXT NOT NULL,
    "name" TEXT NOT NULL,
    "description" TEXT,
    "estDurationMin" INTEGER NOT NULL,
    "bufferPct" DOUBLE PRECISION NOT NULL DEFAULT 0.15,
    "startsAtDepot" BOOLEAN NOT NULL DEFAULT false,
    "active" BOOLEAN NOT NULL DEFAULT true,
    "sortOrder" INTEGER NOT NULL DEFAULT 0,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt" TIMESTAMP(3) NOT NULL,

    CONSTRAINT "service_catalog_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "customer" (
    "id" TEXT NOT NULL,
    "firstName" TEXT NOT NULL,
    "lastName" TEXT NOT NULL,
    "email" TEXT NOT NULL,
    "phone" TEXT NOT NULL,
    "externalId" TEXT,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "customer_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "address" (
    "id" TEXT NOT NULL,
    "customerId" TEXT NOT NULL,
    "line1" TEXT NOT NULL,
    "line2" TEXT,
    "city" TEXT NOT NULL,
    "state" TEXT NOT NULL,
    "postalCode" TEXT NOT NULL,
    "lat" DOUBLE PRECISION,
    "lng" DOUBLE PRECISION,
    "geocodePrecision" TEXT,
    "geocodedAt" TIMESTAMP(3),
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "address_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "job" (
    "id" TEXT NOT NULL,
    "customerId" TEXT NOT NULL,
    "addressId" TEXT NOT NULL,
    "serviceId" TEXT NOT NULL,
    "durationMin" INTEGER NOT NULL,
    "status" "JobStatus" NOT NULL DEFAULT 'PENDING',
    "priority" INTEGER NOT NULL DEFAULT 0,
    "externalId" TEXT,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt" TIMESTAMP(3) NOT NULL,

    CONSTRAINT "job_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "appointment" (
    "id" TEXT NOT NULL,
    "jobId" TEXT NOT NULL,
    "technicianId" TEXT NOT NULL,
    "serviceDate" TIMESTAMP(3) NOT NULL,
    "windowStart" TIMESTAMP(3) NOT NULL,
    "windowEnd" TIMESTAMP(3) NOT NULL,
    "plannedStart" TIMESTAMP(3) NOT NULL,
    "plannedEnd" TIMESTAMP(3) NOT NULL,
    "sequence" INTEGER NOT NULL,
    "externalId" TEXT,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt" TIMESTAMP(3) NOT NULL,

    CONSTRAINT "appointment_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "schedule_day" (
    "id" TEXT NOT NULL,
    "technicianId" TEXT NOT NULL,
    "serviceDate" TIMESTAMP(3) NOT NULL,
    "version" INTEGER NOT NULL DEFAULT 0,

    CONSTRAINT "schedule_day_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "slot_hold" (
    "id" TEXT NOT NULL,
    "offerToken" TEXT NOT NULL,
    "technicianId" TEXT NOT NULL,
    "serviceDate" TIMESTAMP(3) NOT NULL,
    "plannedStart" TIMESTAMP(3) NOT NULL,
    "plannedEnd" TIMESTAMP(3) NOT NULL,
    "expiresAt" TIMESTAMP(3) NOT NULL,
    "releasedAt" TIMESTAMP(3),
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "slot_hold_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "travel_time" (
    "id" TEXT NOT NULL,
    "originGeohash" TEXT NOT NULL,
    "destGeohash" TEXT NOT NULL,
    "timeBucket" "TimeBucket" NOT NULL,
    "dayType" "DayType" NOT NULL,
    "seconds" INTEGER NOT NULL,
    "meters" INTEGER NOT NULL,
    "source" TEXT NOT NULL DEFAULT 'google_distance_matrix',
    "fetchedAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "travel_time_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "scoring_weight" (
    "id" TEXT NOT NULL,
    "key" TEXT NOT NULL,
    "value" DOUBLE PRECISION NOT NULL,
    "updatedAt" TIMESTAMP(3) NOT NULL,

    CONSTRAINT "scoring_weight_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "outbound_event" (
    "id" TEXT NOT NULL,
    "aggregate" TEXT NOT NULL,
    "aggregateId" TEXT NOT NULL,
    "eventType" TEXT NOT NULL,
    "payload" JSONB NOT NULL,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "deliveredAt" TIMESTAMP(3),

    CONSTRAINT "outbound_event_pkey" PRIMARY KEY ("id")
);

-- CreateIndex
CREATE UNIQUE INDEX "technician_externalId_key" ON "technician"("externalId");

-- CreateIndex
CREATE INDEX "technician_metroId_active_idx" ON "technician"("metroId", "active");

-- CreateIndex
CREATE UNIQUE INDEX "service_catalog_code_key" ON "service_catalog"("code");

-- CreateIndex
CREATE UNIQUE INDEX "customer_externalId_key" ON "customer"("externalId");

-- CreateIndex
CREATE UNIQUE INDEX "job_externalId_key" ON "job"("externalId");

-- CreateIndex
CREATE UNIQUE INDEX "appointment_jobId_key" ON "appointment"("jobId");

-- CreateIndex
CREATE UNIQUE INDEX "appointment_externalId_key" ON "appointment"("externalId");

-- CreateIndex
CREATE INDEX "appointment_technicianId_serviceDate_idx" ON "appointment"("technicianId", "serviceDate");

-- CreateIndex
CREATE UNIQUE INDEX "schedule_day_technicianId_serviceDate_key" ON "schedule_day"("technicianId", "serviceDate");

-- CreateIndex
CREATE INDEX "slot_hold_technicianId_serviceDate_idx" ON "slot_hold"("technicianId", "serviceDate");

-- CreateIndex
CREATE INDEX "slot_hold_expiresAt_idx" ON "slot_hold"("expiresAt");

-- CreateIndex
CREATE INDEX "travel_time_fetchedAt_idx" ON "travel_time"("fetchedAt");

-- CreateIndex
CREATE UNIQUE INDEX "travel_time_originGeohash_destGeohash_timeBucket_dayType_key" ON "travel_time"("originGeohash", "destGeohash", "timeBucket", "dayType");

-- CreateIndex
CREATE UNIQUE INDEX "scoring_weight_key_key" ON "scoring_weight"("key");

-- CreateIndex
CREATE INDEX "outbound_event_deliveredAt_idx" ON "outbound_event"("deliveredAt");

-- AddForeignKey
ALTER TABLE "depot" ADD CONSTRAINT "depot_metroId_fkey" FOREIGN KEY ("metroId") REFERENCES "metro"("id") ON DELETE RESTRICT ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "technician" ADD CONSTRAINT "technician_metroId_fkey" FOREIGN KEY ("metroId") REFERENCES "metro"("id") ON DELETE RESTRICT ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "technician" ADD CONSTRAINT "technician_depotId_fkey" FOREIGN KEY ("depotId") REFERENCES "depot"("id") ON DELETE SET NULL ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "address" ADD CONSTRAINT "address_customerId_fkey" FOREIGN KEY ("customerId") REFERENCES "customer"("id") ON DELETE RESTRICT ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "job" ADD CONSTRAINT "job_customerId_fkey" FOREIGN KEY ("customerId") REFERENCES "customer"("id") ON DELETE RESTRICT ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "job" ADD CONSTRAINT "job_addressId_fkey" FOREIGN KEY ("addressId") REFERENCES "address"("id") ON DELETE RESTRICT ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "job" ADD CONSTRAINT "job_serviceId_fkey" FOREIGN KEY ("serviceId") REFERENCES "service_catalog"("id") ON DELETE RESTRICT ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "appointment" ADD CONSTRAINT "appointment_jobId_fkey" FOREIGN KEY ("jobId") REFERENCES "job"("id") ON DELETE RESTRICT ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "appointment" ADD CONSTRAINT "appointment_technicianId_fkey" FOREIGN KEY ("technicianId") REFERENCES "technician"("id") ON DELETE RESTRICT ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "schedule_day" ADD CONSTRAINT "schedule_day_technicianId_fkey" FOREIGN KEY ("technicianId") REFERENCES "technician"("id") ON DELETE RESTRICT ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "slot_hold" ADD CONSTRAINT "slot_hold_technicianId_fkey" FOREIGN KEY ("technicianId") REFERENCES "technician"("id") ON DELETE RESTRICT ON UPDATE CASCADE;
