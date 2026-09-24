CREATE TABLE booking_search_request (
  id TEXT PRIMARY KEY,
  "jobId" TEXT NOT NULL REFERENCES job(id) ON DELETE CASCADE,
  "deadlineAt" TIMESTAMP(3) NOT NULL,
  "cancelledAt" TIMESTAMP(3),
  "acknowledgedAt" TIMESTAMP(3),
  "offerSetId" TEXT REFERENCES booking_offer_set(id) ON DELETE SET NULL,
  "cleanedAt" TIMESTAMP(3),
  "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX booking_search_request_cleanup_idx ON booking_search_request ("cancelledAt", "cleanedAt");
CREATE INDEX booking_search_request_deadline_idx ON booking_search_request ("deadlineAt");
