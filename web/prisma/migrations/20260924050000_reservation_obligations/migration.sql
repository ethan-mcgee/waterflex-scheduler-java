-- Existing holds and pending route dependencies use the same guard semantics.
-- The dependent service may differ from the service requested by the hold's job.
CREATE VIEW "reservation_obligation" AS
SELECT h.id, h."jobId", h."technicianId", h."serviceDate", j."serviceId", h."expiresAt", h."releasedAt"
FROM "slot_hold" h JOIN "job" j ON j.id=h."jobId"
UNION
SELECT h.id, h."jobId", d."technicianId", h."serviceDate", d."serviceId", h."expiresAt", h."releasedAt"
FROM "reservation_dependency" d JOIN "slot_hold" h ON h.id=d."holdId";
