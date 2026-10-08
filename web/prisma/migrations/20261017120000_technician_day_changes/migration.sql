-- The portal acts as the host for the public scheduling API, so it must send a `lastModified` for every technician-day
-- that changes whenever anything affecting that technician-day changes; commits compare it for exact equality.
--
-- Two stamps make it: technician."factsChangedAt" moves when a fact that applies to all of a technician's days changes
-- (home, limits, qualifications, weekly availability, depot assignment, the depot's location or endpoint policy), and
-- technician_day_change."changedAt" moves when that one day changes (its appointments, shift override or time off).
-- The day stamps have their own table so the scheduler's schedule_day lock rows are never written or locked here.
-- A technician-day's lastModified is the later of the two. Each stamp is moved strictly past both its own previous
-- value and the other stamp, so the combined value always changes, even within one microsecond. Triggers catch the
-- scheduler's own writes as well as the portal's.

ALTER TABLE technician ADD COLUMN "factsChangedAt" TIMESTAMPTZ(6) NOT NULL DEFAULT clock_timestamp();
-- No row means the day has never changed on its own; the technician stamp then decides (GREATEST ignores nulls).
CREATE TABLE technician_day_change (
  "technicianId" TEXT NOT NULL REFERENCES technician(id) ON DELETE CASCADE ON UPDATE CASCADE,
  "serviceDate" TIMESTAMP(3) NOT NULL,
  "changedAt" TIMESTAMPTZ(6) NOT NULL,
  PRIMARY KEY ("technicianId", "serviceDate")
);

CREATE FUNCTION technician_facts_changed(p_technician TEXT) RETURNS void LANGUAGE plpgsql AS $$
BEGIN
  IF p_technician IS NULL THEN RETURN; END IF;
  UPDATE technician t SET "factsChangedAt" = GREATEST(clock_timestamp(), t."factsChangedAt" + interval '1 microsecond',
    (SELECT max(d."changedAt") FROM technician_day_change d WHERE d."technicianId" = t.id) + interval '1 microsecond')
  WHERE t.id = p_technician;
END $$;

CREATE FUNCTION technician_day_changed(p_technician TEXT, p_day TIMESTAMP) RETURNS void LANGUAGE plpgsql AS $$
DECLARE
  v_facts TIMESTAMPTZ;
BEGIN
  IF p_technician IS NULL OR p_day IS NULL THEN RETURN; END IF;
  SELECT t."factsChangedAt" INTO v_facts FROM technician t WHERE t.id = p_technician;
  IF NOT FOUND THEN RETURN; END IF;
  INSERT INTO technician_day_change ("technicianId", "serviceDate", "changedAt")
  VALUES (p_technician, p_day, GREATEST(clock_timestamp(), v_facts + interval '1 microsecond'))
  ON CONFLICT ("technicianId", "serviceDate") DO UPDATE SET "changedAt" = GREATEST(clock_timestamp(),
    technician_day_change."changedAt" + interval '1 microsecond', v_facts + interval '1 microsecond');
END $$;

-- Technician-wide facts on the technician row. A BEFORE trigger stamps the row being written.
CREATE FUNCTION technician_row_facts() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF (OLD."homeLat", OLD."homeLng", OLD."shiftStartMin", OLD."shiftEndMin", OLD."maxDailyMinutes", OLD."maxOvertimeMinutes", OLD.active)
     IS DISTINCT FROM (NEW."homeLat", NEW."homeLng", NEW."shiftStartMin", NEW."shiftEndMin", NEW."maxDailyMinutes", NEW."maxOvertimeMinutes", NEW.active) THEN
    NEW."factsChangedAt" := GREATEST(clock_timestamp(), OLD."factsChangedAt" + interval '1 microsecond',
      (SELECT max(d."changedAt") FROM technician_day_change d WHERE d."technicianId" = OLD.id) + interval '1 microsecond');
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER technician_row_facts BEFORE UPDATE ON technician FOR EACH ROW EXECUTE FUNCTION technician_row_facts();

-- Rows that belong to one technician: qualifications, weekly availability versions and depot assignments.
CREATE FUNCTION technician_owned_row_changed() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP <> 'INSERT' THEN PERFORM technician_facts_changed(OLD."technicianId"); END IF;
  IF TG_OP <> 'DELETE' AND (TG_OP = 'INSERT' OR NEW."technicianId" IS DISTINCT FROM OLD."technicianId") THEN
    PERFORM technician_facts_changed(NEW."technicianId");
  END IF;
  RETURN NULL;
END $$;
CREATE TRIGGER technician_qualification_changed AFTER INSERT OR UPDATE OR DELETE ON technician_qualification
  FOR EACH ROW EXECUTE FUNCTION technician_owned_row_changed();
CREATE TRIGGER technician_availability_version_changed AFTER INSERT OR UPDATE OR DELETE ON technician_availability_version
  FOR EACH ROW EXECUTE FUNCTION technician_owned_row_changed();
CREATE TRIGGER technician_depot_assignment_changed AFTER INSERT OR UPDATE OR DELETE ON technician_depot_assignment
  FOR EACH ROW EXECUTE FUNCTION technician_owned_row_changed();

-- Weekly availability days belong to a version. A day removed with its version is covered by the version's trigger.
CREATE FUNCTION technician_availability_day_changed() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP <> 'INSERT' THEN
    PERFORM technician_facts_changed((SELECT "technicianId" FROM technician_availability_version WHERE id = OLD."versionId"));
  END IF;
  IF TG_OP <> 'DELETE' THEN
    PERFORM technician_facts_changed((SELECT "technicianId" FROM technician_availability_version WHERE id = NEW."versionId"));
  END IF;
  RETURN NULL;
END $$;
CREATE TRIGGER technician_availability_day_changed AFTER INSERT OR UPDATE OR DELETE ON technician_availability_day
  FOR EACH ROW EXECUTE FUNCTION technician_availability_day_changed();

-- A depot's location and endpoint policy are facts of every technician ever assigned to it.
CREATE FUNCTION depot_technicians_changed(p_depot TEXT) RETURNS void LANGUAGE plpgsql AS $$
DECLARE
  v_assigned TEXT;
BEGIN
  FOR v_assigned IN SELECT DISTINCT a."technicianId" FROM technician_depot_assignment a WHERE a."depotId" = p_depot ORDER BY 1 LOOP
    PERFORM technician_facts_changed(v_assigned);
  END LOOP;
END $$;
CREATE FUNCTION depot_row_changed() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF (OLD.lat, OLD.lng, OLD."metroId") IS DISTINCT FROM (NEW.lat, NEW.lng, NEW."metroId") THEN
    PERFORM depot_technicians_changed(NEW.id);
  END IF;
  RETURN NULL;
END $$;
CREATE TRIGGER depot_row_changed AFTER UPDATE ON depot FOR EACH ROW EXECUTE FUNCTION depot_row_changed();
CREATE FUNCTION depot_endpoint_policy_changed() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP <> 'INSERT' THEN PERFORM depot_technicians_changed(OLD."depotId"); END IF;
  IF TG_OP <> 'DELETE' AND (TG_OP = 'INSERT' OR NEW."depotId" IS DISTINCT FROM OLD."depotId") THEN
    PERFORM depot_technicians_changed(NEW."depotId");
  END IF;
  RETURN NULL;
END $$;
CREATE TRIGGER depot_endpoint_policy_changed AFTER INSERT OR UPDATE OR DELETE ON depot_endpoint_policy
  FOR EACH ROW EXECUTE FUNCTION depot_endpoint_policy_changed();

-- Rows that belong to one technician-day: appointments and shift overrides.
CREATE FUNCTION technician_day_row_changed() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP <> 'INSERT' THEN PERFORM technician_day_changed(OLD."technicianId", OLD."serviceDate"); END IF;
  IF TG_OP <> 'DELETE' AND (TG_OP = 'INSERT' OR (NEW."technicianId", NEW."serviceDate") IS DISTINCT FROM (OLD."technicianId", OLD."serviceDate")) THEN
    PERFORM technician_day_changed(NEW."technicianId", NEW."serviceDate");
  END IF;
  RETURN NULL;
END $$;
CREATE TRIGGER appointment_day_changed AFTER INSERT OR UPDATE OR DELETE ON appointment
  FOR EACH ROW EXECUTE FUNCTION technician_day_row_changed();
CREATE TRIGGER technician_shift_override_day_changed AFTER INSERT OR UPDATE OR DELETE ON technician_shift_override
  FOR EACH ROW EXECUTE FUNCTION technician_day_row_changed();

-- A job's duration, service or address, and an address's coordinates, are facts of the day its appointment is on.
CREATE FUNCTION job_appointment_day_changed() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF (OLD."durationMin", OLD."serviceId", OLD."addressId") IS DISTINCT FROM (NEW."durationMin", NEW."serviceId", NEW."addressId") THEN
    PERFORM technician_day_changed(a."technicianId", a."serviceDate") FROM appointment a WHERE a."jobId" = NEW.id ORDER BY a."technicianId", a."serviceDate";
  END IF;
  RETURN NULL;
END $$;
CREATE TRIGGER job_appointment_day_changed AFTER UPDATE ON job FOR EACH ROW EXECUTE FUNCTION job_appointment_day_changed();
CREATE FUNCTION address_appointment_day_changed() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF (OLD.lat, OLD.lng, OLD.line1, OLD.city, OLD.state, OLD."postalCode") IS DISTINCT FROM (NEW.lat, NEW.lng, NEW.line1, NEW.city, NEW.state, NEW."postalCode") THEN
    PERFORM technician_day_changed(a."technicianId", a."serviceDate") FROM appointment a JOIN job j ON j.id = a."jobId" WHERE j."addressId" = NEW.id ORDER BY a."technicianId", a."serviceDate";
  END IF;
  RETURN NULL;
END $$;
CREATE TRIGGER address_appointment_day_changed AFTER UPDATE ON address FOR EACH ROW EXECUTE FUNCTION address_appointment_day_changed();

-- Approved time off is an absence on each of its days; any change to an interval, or to its request's status, moves them.
CREATE FUNCTION time_off_interval_changed() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP <> 'INSERT' THEN
    PERFORM technician_day_changed((SELECT "technicianId" FROM time_off_request WHERE id = OLD."requestId"), OLD."serviceDate");
  END IF;
  IF TG_OP <> 'DELETE' THEN
    PERFORM technician_day_changed((SELECT "technicianId" FROM time_off_request WHERE id = NEW."requestId"), NEW."serviceDate");
  END IF;
  RETURN NULL;
END $$;
CREATE TRIGGER time_off_interval_changed AFTER INSERT OR UPDATE OR DELETE ON time_off_interval
  FOR EACH ROW EXECUTE FUNCTION time_off_interval_changed();
CREATE FUNCTION time_off_request_changed() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF (OLD.status, OLD."technicianId") IS DISTINCT FROM (NEW.status, NEW."technicianId") THEN
    PERFORM technician_day_changed(t.technician, i."serviceDate")
    FROM time_off_interval i CROSS JOIN (SELECT DISTINCT unnest(ARRAY[OLD."technicianId", NEW."technicianId"]) AS technician) t
    WHERE i."requestId" = NEW.id ORDER BY t.technician, i."serviceDate";
  END IF;
  RETURN NULL;
END $$;
CREATE TRIGGER time_off_request_changed AFTER UPDATE ON time_off_request FOR EACH ROW EXECUTE FUNCTION time_off_request_changed();
