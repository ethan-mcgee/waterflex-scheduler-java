-- Retire incompatible unconfirmed promises as a whole reservation set.
-- Confirmed appointment promises and their route facts remain untouched.
UPDATE booking_offer_set s SET "policyVersion"='legacy-incompatible',
  "supersededAt"=COALESCE(s."supersededAt",CURRENT_TIMESTAMP)
WHERE EXISTS (SELECT 1 FROM booking_offer o WHERE o."offerSetId"=s.id
  AND (o."windowEnd"-o."windowStart"<>interval '4 hours'
    OR o."overtimeAuthorized" OR o."incrementalOvertimeMinutes">0))
  OR (SELECT count(*) FROM booking_offer o WHERE o."offerSetId"=s.id)>1;
UPDATE booking_offer o SET "policyVersion"='legacy-incompatible'
WHERE EXISTS (SELECT 1 FROM booking_offer_set s WHERE s.id=o."offerSetId" AND s."policyVersion"='legacy-incompatible');
UPDATE slot_hold h SET "releasedAt"=CURRENT_TIMESTAMP
WHERE h."releasedAt" IS NULL AND EXISTS
  (SELECT 1 FROM booking_offer_set s WHERE s.id=h."offerSetId" AND s."supersededAt" IS NOT NULL);
