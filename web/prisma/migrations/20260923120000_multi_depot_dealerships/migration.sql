-- Run the owner audit in docs/dealerships.md before deploying. Never infer an owner.
DO $$ BEGIN
  IF EXISTS (SELECT 1 FROM depot p LEFT JOIN dealership d ON d."depotId"=p.id WHERE d.id IS NULL) THEN
    RAISE EXCEPTION 'Depot without dealership owner. Map every depot explicitly before migration';
  END IF;
  IF EXISTS (SELECT 1 FROM depot p JOIN dealership d ON d."depotId"=p.id GROUP BY p.id HAVING count(*)<>1) THEN
    RAISE EXCEPTION 'Depot has multiple dealership owners. Resolve ownership before migration';
  END IF;
  IF EXISTS (SELECT 1 FROM technician t JOIN dealership d ON d.id=t."dealershipId" JOIN depot p ON p.id=d."depotId" WHERE t."metroId"<>p."metroId") THEN
    RAISE EXCEPTION 'Technician metro does not match its assigned depot';
  END IF;
END $$;

ALTER TABLE depot ADD COLUMN "dealershipId" TEXT;
UPDATE depot p SET "dealershipId"=d.id FROM dealership d WHERE d."depotId"=p.id;
ALTER TABLE depot ALTER COLUMN "dealershipId" SET NOT NULL;
ALTER TABLE depot ADD CONSTRAINT depot_dealershipId_fkey FOREIGN KEY ("dealershipId") REFERENCES dealership(id);
CREATE INDEX depot_dealershipId_idx ON depot("dealershipId");

CREATE TABLE depot_endpoint_policy (
  "depotId" TEXT NOT NULL REFERENCES depot(id) ON DELETE CASCADE,
  "effectiveDate" TIMESTAMP(3) NOT NULL,
  departure "RouteAnchor" NOT NULL,
  "returnTo" "RouteAnchor" NOT NULL,
  PRIMARY KEY ("depotId", "effectiveDate")
);
INSERT INTO depot_endpoint_policy ("depotId", "effectiveDate", departure, "returnTo")
SELECT p.id, policy."effectiveDate", policy.departure, policy."returnTo"
FROM depot p JOIN dealership_endpoint_policy policy ON policy."dealershipId"=p."dealershipId";

CREATE TABLE technician_depot_assignment (
  "technicianId" TEXT NOT NULL REFERENCES technician(id) ON DELETE CASCADE,
  "effectiveDate" TIMESTAMP(3) NOT NULL,
  "depotId" TEXT NOT NULL REFERENCES depot(id),
  PRIMARY KEY ("technicianId", "effectiveDate")
);
INSERT INTO technician_depot_assignment ("technicianId", "effectiveDate", "depotId")
SELECT t.id, '1900-01-01', d."depotId" FROM technician t JOIN dealership d ON d.id=t."dealershipId";
CREATE INDEX technician_depot_assignment_depotId_effectiveDate_idx ON technician_depot_assignment("depotId", "effectiveDate");

ALTER TABLE technician DROP CONSTRAINT technician_dealershipId_fkey;
DROP INDEX IF EXISTS technician_metroId_active_idx;
ALTER TABLE technician DROP COLUMN "dealershipId", DROP COLUMN "metroId";
CREATE INDEX technician_active_idx ON technician(active);
DROP TABLE dealership_endpoint_policy;
DROP INDEX IF EXISTS dealership_metroId_idx;
ALTER TABLE dealership DROP COLUMN "depotId", DROP COLUMN "metroId", DROP COLUMN departure, DROP COLUMN "returnTo";
