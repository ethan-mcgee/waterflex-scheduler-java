-- The scheduler's least-privilege runtime role and its retention purge.
--
-- scheduler_app holds exactly what the running scheduler needs: read tenants and token digests, keep the route cache,
-- act as scheduler_tenant (whose row-level policies confine every thin store row to one tenant), and run the purge.
-- It cannot change the schema or write tenants. A deployment logs the scheduler in as a member of scheduler_app.
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'scheduler_app') THEN
    CREATE ROLE scheduler_app NOLOGIN NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE NOINHERIT;
  END IF;
  EXECUTE format('GRANT USAGE ON SCHEMA %I TO scheduler_app', current_schema());
END $$;
GRANT SELECT ON tenant, tenant_api_token TO scheduler_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON road_route_cache TO scheduler_app;
-- NOINHERIT: scheduler_tenant's table grants apply only after SET LOCAL ROLE scheduler_tenant, never to the login directly.
GRANT scheduler_tenant TO scheduler_app;

-- Deletes one batch per thin store table of rows older than cutoff, children before parents, and returns how many
-- rows it deleted; the caller repeats it until it returns 0. It runs as the table owner, so it sees every tenant's
-- rows, and it can only delete by age: a cutoff less than a day old is refused. Rows another replica's purge has
-- locked are skipped. A row still referenced by a newer one (a receipt for an older offer set) stays until both age.
CREATE FUNCTION scheduler_purge(cutoff TIMESTAMPTZ, batch INTEGER) RETURNS INTEGER
LANGUAGE plpgsql SECURITY DEFINER AS $$
DECLARE
  n INTEGER;
  total INTEGER := 0;
BEGIN
  IF cutoff IS NULL OR batch IS NULL OR batch < 1 OR batch > 50000 OR cutoff > clock_timestamp() - INTERVAL '1 day' THEN
    RAISE EXCEPTION 'scheduler_purge needs a cutoff at least one day old and a batch of 1 to 50000';
  END IF;

  DELETE FROM api_booking_receipt WHERE ctid IN (
    SELECT ctid FROM api_booking_receipt WHERE "createdAt" < cutoff LIMIT batch FOR UPDATE SKIP LOCKED);
  GET DIAGNOSTICS n = ROW_COUNT; total := total + n;

  -- An offer set's offers go with it (ON DELETE CASCADE).
  DELETE FROM api_booking_offer_set WHERE ctid IN (
    SELECT s.ctid FROM api_booking_offer_set s WHERE s."createdAt" < cutoff AND NOT EXISTS (
      SELECT 1 FROM api_booking_receipt r JOIN api_booking_offer o ON o."tenantId" = r."tenantId" AND o.id = r."offerId"
      WHERE o."tenantId" = s."tenantId" AND o."offerSetId" = s.id)
    LIMIT batch FOR UPDATE SKIP LOCKED);
  GET DIAGNOSTICS n = ROW_COUNT; total := total + n;

  DELETE FROM api_commit_receipt WHERE ctid IN (
    SELECT ctid FROM api_commit_receipt WHERE "createdAt" < cutoff LIMIT batch FOR UPDATE SKIP LOCKED);
  GET DIAGNOSTICS n = ROW_COUNT; total := total + n;

  -- A proposal's technician-days go with it (ON DELETE CASCADE).
  DELETE FROM api_daily_proposal WHERE ctid IN (
    SELECT p.ctid FROM api_daily_proposal p WHERE p."createdAt" < cutoff AND NOT EXISTS (
      SELECT 1 FROM api_commit_receipt c WHERE c."tenantId" = p."tenantId" AND c."proposalId" = p.id)
    LIMIT batch FOR UPDATE SKIP LOCKED);
  GET DIAGNOSTICS n = ROW_COUNT; total := total + n;

  DELETE FROM api_request WHERE ctid IN (
    SELECT q.ctid FROM api_request q
    WHERE (q."completedAt" < cutoff OR (q.state = 'IN_PROGRESS' AND q."leaseExpiresAt" < cutoff))
      AND NOT EXISTS (SELECT 1 FROM api_daily_proposal p WHERE p."tenantId" = q."tenantId" AND p."requestId" = q."requestId")
      AND NOT EXISTS (SELECT 1 FROM api_commit_receipt c WHERE c."tenantId" = q."tenantId" AND c."requestId" = q."requestId")
      AND NOT EXISTS (SELECT 1 FROM api_booking_offer_set s WHERE s."tenantId" = q."tenantId" AND s."requestId" = q."requestId")
      AND NOT EXISTS (SELECT 1 FROM api_booking_receipt r WHERE r."tenantId" = q."tenantId" AND r."requestId" = q."requestId")
    LIMIT batch FOR UPDATE SKIP LOCKED);
  GET DIAGNOSTICS n = ROW_COUNT; total := total + n;

  -- A booking day untouched this long has no live hold; the next search for it starts from the host's snapshot.
  DELETE FROM api_booking_day WHERE ctid IN (
    SELECT ctid FROM api_booking_day WHERE "updatedAt" < cutoff LIMIT batch FOR UPDATE SKIP LOCKED);
  GET DIAGNOSTICS n = ROW_COUNT; total := total + n;

  RETURN total;
END $$;

-- A definer function must not resolve names through the caller's search path.
DO $$
BEGIN
  EXECUTE format('ALTER FUNCTION scheduler_purge(TIMESTAMPTZ, INTEGER) SET search_path = %I, pg_temp', current_schema());
END $$;
REVOKE ALL ON FUNCTION scheduler_purge(TIMESTAMPTZ, INTEGER) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION scheduler_purge(TIMESTAMPTZ, INTEGER) TO scheduler_app;
