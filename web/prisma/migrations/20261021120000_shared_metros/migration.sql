-- Two clients may serve the same metro, each with its own dealerships, depots, technicians, customers and
-- appointments. The portal schedules them through the public API with a snapshot of one client's facts, and the
-- scheduler's own database paths refuse a metro served by more than one client (MetroTenancy). A depot still never
-- moves to another client's dealership, since its technicians belong to its client.
DROP TRIGGER depot_metro_one_client ON depot;
DROP FUNCTION depot_metro_one_client();

CREATE FUNCTION depot_client_unchanged() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF (SELECT "clientId" FROM dealership WHERE id = NEW."dealershipId")
       IS DISTINCT FROM (SELECT "clientId" FROM dealership WHERE id = OLD."dealershipId") THEN
    RAISE EXCEPTION 'A depot cannot move to another client''s dealership' USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER depot_client_unchanged BEFORE UPDATE OF "dealershipId" ON depot
  FOR EACH ROW EXECUTE FUNCTION depot_client_unchanged();
