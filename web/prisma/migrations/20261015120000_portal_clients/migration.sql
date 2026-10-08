-- Clients the portal schedules for. The portal stands in for WaterFlex Software and keeps each client's master data
-- apart: a dealership, a technician and a customer each belong to one client, and everything else (depots, jobs,
-- appointments, time off) belongs to a client through them. Existing rows belong to the client "default".

CREATE TABLE client (
  id TEXT PRIMARY KEY CHECK (id ~ '^[a-z0-9]([a-z0-9-]{0,62}[a-z0-9])?$'),
  name TEXT NOT NULL CHECK (length(btrim(name)) BETWEEN 1 AND 200),
  "createdAt" TIMESTAMPTZ(6) NOT NULL DEFAULT clock_timestamp()
);
INSERT INTO client (id, name) VALUES ('default', 'Default client');

ALTER TABLE dealership ADD COLUMN "clientId" TEXT;
UPDATE dealership SET "clientId" = 'default';
ALTER TABLE dealership ALTER COLUMN "clientId" SET NOT NULL;
ALTER TABLE dealership ADD CONSTRAINT dealership_client_fkey FOREIGN KEY ("clientId") REFERENCES client(id) ON DELETE RESTRICT ON UPDATE RESTRICT;
CREATE INDEX dealership_client ON dealership ("clientId");

ALTER TABLE technician ADD COLUMN "clientId" TEXT;
UPDATE technician SET "clientId" = 'default';
ALTER TABLE technician ALTER COLUMN "clientId" SET NOT NULL;
ALTER TABLE technician ADD CONSTRAINT technician_client_fkey FOREIGN KEY ("clientId") REFERENCES client(id) ON DELETE RESTRICT ON UPDATE RESTRICT;
CREATE INDEX technician_client ON technician ("clientId");

ALTER TABLE customer ADD COLUMN "clientId" TEXT;
UPDATE customer SET "clientId" = 'default';
ALTER TABLE customer ALTER COLUMN "clientId" SET NOT NULL;
ALTER TABLE customer ADD CONSTRAINT customer_client_fkey FOREIGN KEY ("clientId") REFERENCES client(id) ON DELETE RESTRICT ON UPDATE RESTRICT;
CREATE INDEX customer_client ON customer ("clientId");

-- A row never changes client: moving it would carry its history into another client's records.
CREATE FUNCTION client_unchanged() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW."clientId" <> OLD."clientId" THEN
    RAISE EXCEPTION '%.clientId cannot change', TG_TABLE_NAME USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER dealership_client_unchanged BEFORE UPDATE OF "clientId" ON dealership FOR EACH ROW EXECUTE FUNCTION client_unchanged();
CREATE TRIGGER technician_client_unchanged BEFORE UPDATE OF "clientId" ON technician FOR EACH ROW EXECUTE FUNCTION client_unchanged();
CREATE TRIGGER customer_client_unchanged BEFORE UPDATE OF "clientId" ON customer FOR EACH ROW EXECUTE FUNCTION client_unchanged();

-- A technician works only at its own client's depots.
CREATE FUNCTION technician_depot_same_client() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF (SELECT d."clientId" FROM depot p JOIN dealership d ON d.id = p."dealershipId" WHERE p.id = NEW."depotId")
     IS DISTINCT FROM (SELECT "clientId" FROM technician WHERE id = NEW."technicianId") THEN
    RAISE EXCEPTION 'A technician can only be assigned to a depot of its own client' USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER technician_depot_assignment_same_client BEFORE INSERT OR UPDATE ON technician_depot_assignment
  FOR EACH ROW EXECUTE FUNCTION technician_depot_same_client();

-- Until scheduling runs through the public API (S6 P4 and P5), the scheduler's database path routes every technician
-- of a metro together, so each metro serves one client. A later migration drops the metro rule. A depot also never moves
-- to another client's dealership, since its technicians belong to its client.
CREATE FUNCTION depot_metro_one_client() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP = 'UPDATE' AND (SELECT "clientId" FROM dealership WHERE id = NEW."dealershipId")
       IS DISTINCT FROM (SELECT "clientId" FROM dealership WHERE id = OLD."dealershipId") THEN
    RAISE EXCEPTION 'A depot cannot move to another client''s dealership' USING ERRCODE = 'check_violation';
  END IF;
  -- Serializes depot changes per metro, so two clients cannot claim the same metro concurrently.
  PERFORM 1 FROM metro WHERE id = NEW."metroId" FOR UPDATE;
  IF EXISTS (SELECT 1 FROM depot p JOIN dealership d ON d.id = p."dealershipId"
             WHERE p."metroId" = NEW."metroId" AND p.id <> NEW.id
               AND d."clientId" <> (SELECT "clientId" FROM dealership WHERE id = NEW."dealershipId")) THEN
    RAISE EXCEPTION 'Metro % already serves another client; each metro serves one client until scheduling runs through the public API', NEW."metroId"
      USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER depot_metro_one_client BEFORE INSERT OR UPDATE OF "metroId", "dealershipId" ON depot
  FOR EACH ROW EXECUTE FUNCTION depot_metro_one_client();
