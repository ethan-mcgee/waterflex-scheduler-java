-- A booking test run made through the public scheduling API books for one client, chosen when the run is created, so
-- switching the portal's client cannot redirect a run in progress. Runs made before have no client: they book for the
-- Omaha metro's only client, as they always did.
ALTER TABLE "booking_test_run" ADD COLUMN "clientId" TEXT;
ALTER TABLE "booking_test_run" ADD CONSTRAINT "booking_test_run_clientId_fkey"
  FOREIGN KEY ("clientId") REFERENCES "client"("id") ON DELETE RESTRICT ON UPDATE CASCADE;
