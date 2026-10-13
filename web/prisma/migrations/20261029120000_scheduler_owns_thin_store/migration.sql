-- The scheduler now creates and migrates its own database (scheduler-service db/migration, run by
-- java -jar scheduler-service.jar --command=migrate), and the portal reaches it only through /api/v1. The portal's
-- copies of the scheduler's tables go: tenants and token digests, the thin store, the route cache and the purge.
-- Nothing in them is portal data. Tokens are reissued in the scheduler's database (local Compose does it on start),
-- and route cache rows are recomputed by routing.
--
-- The roles scheduler_tenant and scheduler_app are kept: roles belong to the whole server, and a scheduler database
-- on the same server may use them. Only their access to this schema is removed.
DROP FUNCTION IF EXISTS scheduler_purge(TIMESTAMPTZ, INTEGER);

DROP TABLE api_booking_receipt;
DROP TABLE api_booking_offer;
DROP TABLE api_booking_offer_set;
DROP TABLE api_booking_day;
DROP TABLE api_commit_receipt;
DROP TABLE api_proposal_technician_day;
DROP TABLE api_daily_proposal;
DROP TABLE api_request;
DROP TABLE tenant_api_token;
DROP TABLE tenant;
DROP TABLE road_route_cache;

DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'scheduler_app') THEN
    EXECUTE format('REVOKE USAGE ON SCHEMA %I FROM scheduler_app', current_schema());
  END IF;
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'scheduler_tenant') THEN
    EXECUTE format('REVOKE USAGE ON SCHEMA %I FROM scheduler_tenant', current_schema());
  END IF;
END $$;
