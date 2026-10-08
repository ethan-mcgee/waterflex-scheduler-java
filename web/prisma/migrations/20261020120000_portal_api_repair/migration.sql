-- Time-off repair through the public scheduling API: a repair proposal is kept like a daily proposal, tied to the
-- time-off request it was analyzed for. Every existing row is a daily proposal.
ALTER TABLE portal_api_daily_proposal ADD COLUMN kind TEXT;
UPDATE portal_api_daily_proposal SET kind = 'DAILY';
ALTER TABLE portal_api_daily_proposal ALTER COLUMN kind SET NOT NULL;
ALTER TABLE portal_api_daily_proposal ADD CONSTRAINT portal_api_daily_proposal_kind CHECK (kind IN ('DAILY', 'REPAIR'));
ALTER TABLE portal_api_daily_proposal ADD COLUMN "timeOffRequestId" TEXT REFERENCES time_off_request(id) ON DELETE CASCADE ON UPDATE CASCADE;
ALTER TABLE portal_api_daily_proposal ADD CONSTRAINT portal_api_daily_proposal_repair_request CHECK ((kind = 'REPAIR') = ("timeOffRequestId" IS NOT NULL));
CREATE INDEX portal_api_daily_proposal_time_off ON portal_api_daily_proposal ("timeOffRequestId");

-- Only approved time off is an absence, so only approval and its reversal move a technician-day. Moving it on every
-- status change (pending, ready) made each time-off analysis stale against its own snapshot.
CREATE OR REPLACE FUNCTION time_off_request_changed() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF (OLD.status = 'APPROVED' OR NEW.status = 'APPROVED')
     AND (OLD.status, OLD."technicianId") IS DISTINCT FROM (NEW.status, NEW."technicianId") THEN
    PERFORM technician_day_changed(t.technician, i."serviceDate")
    FROM time_off_interval i CROSS JOIN (SELECT DISTINCT unnest(ARRAY[OLD."technicianId", NEW."technicianId"]) AS technician) t
    WHERE i."requestId" = NEW.id ORDER BY t.technician, i."serviceDate";
  END IF;
  RETURN NULL;
END $$;

-- An interval is an absence only while its request is approved.
CREATE OR REPLACE FUNCTION time_off_interval_changed() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP <> 'INSERT' THEN
    PERFORM technician_day_changed((SELECT "technicianId" FROM time_off_request WHERE id = OLD."requestId" AND status = 'APPROVED'), OLD."serviceDate");
  END IF;
  IF TG_OP <> 'DELETE' THEN
    PERFORM technician_day_changed((SELECT "technicianId" FROM time_off_request WHERE id = NEW."requestId" AND status = 'APPROVED'), NEW."serviceDate");
  END IF;
  RETURN NULL;
END $$;

-- Deleting an approved request removes its absences. Its intervals are deleted by cascade after the request row is
-- gone, when the interval trigger can no longer find the request, so the days move here, before the delete.
CREATE FUNCTION time_off_request_deleted() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF OLD.status = 'APPROVED' THEN
    PERFORM technician_day_changed(OLD."technicianId", i."serviceDate")
    FROM time_off_interval i WHERE i."requestId" = OLD.id ORDER BY i."serviceDate";
  END IF;
  RETURN OLD;
END $$;
CREATE TRIGGER time_off_request_deleted BEFORE DELETE ON time_off_request FOR EACH ROW EXECUTE FUNCTION time_off_request_deleted();
