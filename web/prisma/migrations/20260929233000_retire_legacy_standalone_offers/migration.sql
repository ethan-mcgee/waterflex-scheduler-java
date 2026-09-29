-- The pre-reservation booking path wrote standalone offers and holds.
-- They have no common arrangement provenance. Retire them at policy cutover.
-- Appointment rows, including confirmed historical promises, are not changed.
UPDATE booking_offer SET "policyVersion"='legacy-incompatible',
  "expiresAt"=LEAST("expiresAt",CURRENT_TIMESTAMP)
WHERE "offerSetId" IS NULL;
UPDATE slot_hold SET "releasedAt"=CURRENT_TIMESTAMP
WHERE "offerSetId" IS NULL AND "releasedAt" IS NULL;
