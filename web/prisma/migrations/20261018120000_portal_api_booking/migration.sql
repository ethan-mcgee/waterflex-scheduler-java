-- Booking through the public scheduling API: the portal, acting as the host, remembers the offers the scheduler
-- returned for a job, and writes a confirmed booking's receipt with a compare-and-set on technician-day lastModified.

-- A day stamp now takes a share lock on its technician. A host writing a receipt locks the technician rows (after the
-- schedule_day lock rows, the scheduler's order) and then compares lastModified, so no change to those days can land
-- between the comparison and the write.
CREATE OR REPLACE FUNCTION technician_day_changed(p_technician TEXT, p_day TIMESTAMP) RETURNS void LANGUAGE plpgsql AS $$
DECLARE
  v_facts TIMESTAMPTZ;
BEGIN
  IF p_technician IS NULL OR p_day IS NULL THEN RETURN; END IF;
  SELECT t."factsChangedAt" INTO v_facts FROM technician t WHERE t.id = p_technician FOR SHARE;
  IF NOT FOUND THEN RETURN; END IF;
  INSERT INTO technician_day_change ("technicianId", "serviceDate", "changedAt")
  VALUES (p_technician, p_day, GREATEST(clock_timestamp(), v_facts + interval '1 microsecond'))
  ON CONFLICT ("technicianId", "serviceDate") DO UPDATE SET "changedAt" = GREATEST(clock_timestamp(),
    technician_day_change."changedAt" + interval '1 microsecond', v_facts + interval '1 microsecond');
END $$;

-- One search's offers for a job, as the scheduler returned them. A new search for the job ends the earlier set.
CREATE TABLE portal_api_offer_set (
  id TEXT PRIMARY KEY,
  "clientId" TEXT NOT NULL REFERENCES client(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
  "jobId" TEXT NOT NULL REFERENCES job(id) ON DELETE CASCADE ON UPDATE CASCADE,
  "metroId" TEXT NOT NULL REFERENCES metro(id) ON DELETE RESTRICT ON UPDATE CASCADE,
  "requestId" UUID NOT NULL UNIQUE,
  "expiresAt" TIMESTAMPTZ(6) NOT NULL,
  "searchComplete" BOOLEAN NOT NULL,
  "holdId" TEXT,
  "selectedOfferId" TEXT,
  "receiptId" TEXT UNIQUE,
  "endedAt" TIMESTAMPTZ(6),
  "createdAt" TIMESTAMPTZ(6) NOT NULL DEFAULT clock_timestamp(),
  CHECK (("holdId" IS NULL) = ("selectedOfferId" IS NULL)),
  CHECK ("receiptId" IS NULL OR "holdId" IS NOT NULL)
);
CREATE INDEX portal_api_offer_set_job ON portal_api_offer_set ("jobId", "createdAt");

CREATE TABLE portal_api_offer (
  id TEXT PRIMARY KEY,
  "offerSetId" TEXT NOT NULL REFERENCES portal_api_offer_set(id) ON DELETE CASCADE ON UPDATE CASCADE,
  "serviceDate" DATE NOT NULL,
  "windowStart" TIMESTAMPTZ(6) NOT NULL,
  "windowEnd" TIMESTAMPTZ(6) NOT NULL,
  CHECK ("windowStart" < "windowEnd")
);
CREATE INDEX portal_api_offer_set_offers ON portal_api_offer ("offerSetId");
ALTER TABLE portal_api_offer_set ADD CONSTRAINT portal_api_offer_set_selected_fkey
  FOREIGN KEY ("selectedOfferId") REFERENCES portal_api_offer(id) ON DELETE RESTRICT ON UPDATE CASCADE DEFERRABLE INITIALLY DEFERRED;
