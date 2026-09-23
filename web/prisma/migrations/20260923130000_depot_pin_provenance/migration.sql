ALTER TABLE depot
  ADD COLUMN "addressLine1" TEXT,
  ADD COLUMN "addressCity" TEXT,
  ADD COLUMN "addressState" TEXT,
  ADD COLUMN "addressPostalCode" TEXT,
  ADD COLUMN "geocodeLat" DOUBLE PRECISION,
  ADD COLUMN "geocodeLng" DOUBLE PRECISION,
  ADD COLUMN "geocodePrecision" TEXT,
  ADD COLUMN "pinConfirmedAt" TIMESTAMP(3);
