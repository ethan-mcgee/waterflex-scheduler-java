CREATE TABLE dealership (
  id TEXT PRIMARY KEY,
  "metroId" TEXT NOT NULL REFERENCES metro(id),
  "depotId" TEXT NOT NULL REFERENCES depot(id),
  name TEXT NOT NULL,
  departure "RouteAnchor" NOT NULL DEFAULT 'HOME',
  "returnTo" "RouteAnchor" NOT NULL DEFAULT 'HOME',
  "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "updatedAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX dealership_metroId_idx ON dealership("metroId");
CREATE TABLE dealership_endpoint_policy (
  "dealershipId" TEXT NOT NULL REFERENCES dealership(id) ON DELETE CASCADE,
  "effectiveDate" TIMESTAMP(3) NOT NULL,
  departure "RouteAnchor" NOT NULL,
  "returnTo" "RouteAnchor" NOT NULL,
  PRIMARY KEY ("dealershipId", "effectiveDate")
);

DO $$ BEGIN
  IF EXISTS (SELECT 1 FROM technician WHERE "depotId" IS NULL) THEN
    RAISE EXCEPTION 'Assign a depot to every existing technician before dealership migration';
  END IF;
  IF EXISTS (SELECT 1 FROM technician t JOIN depot d ON d.id=t."depotId" WHERE t."metroId"<>d."metroId") THEN
    RAISE EXCEPTION 'Technician and depot metros must match before dealership migration';
  END IF;
END $$;

INSERT INTO dealership (id, "metroId", "depotId", name, departure, "returnTo")
SELECT 'dealership-backfill-' || d.id, d."metroId", d.id, d.name || ' dealership', 'HOME', 'HOME'
FROM depot d WHERE EXISTS (SELECT 1 FROM technician t WHERE t."depotId"=d.id);
INSERT INTO dealership_endpoint_policy ("dealershipId", "effectiveDate", departure, "returnTo")
SELECT id, '1900-01-01', 'HOME', 'HOME' FROM dealership;
ALTER TABLE technician ADD COLUMN "dealershipId" TEXT;
UPDATE technician SET "dealershipId"='dealership-backfill-' || "depotId";
ALTER TABLE technician ALTER COLUMN "dealershipId" SET NOT NULL;
ALTER TABLE technician ADD CONSTRAINT technician_dealershipId_fkey FOREIGN KEY ("dealershipId") REFERENCES dealership(id);
ALTER TABLE technician DROP COLUMN "depotId";
ALTER TABLE technician DROP COLUMN "startLocation";
ALTER TABLE technician DROP COLUMN "endLocation";
ALTER TABLE optimization_run ADD COLUMN "endpointSnapshots" JSONB;
