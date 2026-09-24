CREATE TABLE "reservation_arrangement" (
    "id" TEXT NOT NULL,
    "metroId" TEXT NOT NULL,
    "serviceDate" TIMESTAMP(3) NOT NULL,
    "version" INTEGER NOT NULL DEFAULT 0 CHECK ("version" >= 0),
    "state" JSONB,
    "updatedAt" TIMESTAMP(3) NOT NULL,
    CONSTRAINT "reservation_arrangement_pkey" PRIMARY KEY ("id"),
    CONSTRAINT "reservation_arrangement_metroId_fkey" FOREIGN KEY ("metroId") REFERENCES "metro"("id") ON DELETE RESTRICT ON UPDATE CASCADE
);
CREATE UNIQUE INDEX "reservation_arrangement_metroId_serviceDate_key" ON "reservation_arrangement"("metroId", "serviceDate");

CREATE TABLE "reservation_dependency" (
    "arrangementId" TEXT NOT NULL,
    "holdId" TEXT NOT NULL,
    "technicianId" TEXT NOT NULL,
    "serviceId" TEXT NOT NULL,
    "serviceDate" TIMESTAMP(3) NOT NULL,
    CONSTRAINT "reservation_dependency_pkey" PRIMARY KEY ("holdId", "technicianId", "serviceId"),
    CONSTRAINT "reservation_dependency_arrangementId_fkey" FOREIGN KEY ("arrangementId") REFERENCES "reservation_arrangement"("id") ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT "reservation_dependency_holdId_fkey" FOREIGN KEY ("holdId") REFERENCES "slot_hold"("id") ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT "reservation_dependency_technicianId_fkey" FOREIGN KEY ("technicianId") REFERENCES "technician"("id") ON DELETE RESTRICT ON UPDATE CASCADE,
    CONSTRAINT "reservation_dependency_serviceId_fkey" FOREIGN KEY ("serviceId") REFERENCES "service_catalog"("id") ON DELETE RESTRICT ON UPDATE CASCADE
);
CREATE INDEX "reservation_dependency_technicianId_serviceDate_idx" ON "reservation_dependency"("technicianId", "serviceDate");
CREATE INDEX "reservation_dependency_arrangementId_idx" ON "reservation_dependency"("arrangementId");
