-- Cancellation of a synchronous calculation from any replica. Behind a load balancer the host's DELETE
-- /api/v1/requests/{requestId} may reach a replica other than the one running the call; that replica records the
-- cancel on the request's claim, and the replica running it reads the flag (scheduler_cancel_requested) and stops it.
-- A claim taken over after its lease passed starts without the flag.

ALTER TABLE api_request ADD COLUMN "cancelRequestedAt" TIMESTAMPTZ;

-- Of the requests a replica runs, the ones whose cancellation was recorded. A replica runs calculations for many
-- tenants, so it reads across them here instead of as one tenant; it learns only the keys it already holds.
CREATE FUNCTION scheduler_cancel_requested(tenants TEXT[], requests TEXT[])
RETURNS TABLE (cancel_tenant TEXT, cancel_request TEXT)
LANGUAGE plpgsql STABLE SECURITY DEFINER AS $$
BEGIN
  IF tenants IS NULL OR requests IS NULL OR cardinality(tenants) <> cardinality(requests) OR cardinality(tenants) > 1000 THEN
    RAISE EXCEPTION 'scheduler_cancel_requested needs two arrays of the same length, at most 1000';
  END IF;
  RETURN QUERY
    SELECT r."tenantId", r."requestId"
      FROM api_request r JOIN unnest(tenants, requests) AS k(tenant, request) ON r."tenantId" = k.tenant AND r."requestId" = k.request
     WHERE r.state = 'IN_PROGRESS' AND r."cancelRequestedAt" IS NOT NULL;
END $$;

DO $$
BEGIN
  EXECUTE format('ALTER FUNCTION scheduler_cancel_requested(TEXT[], TEXT[]) SET search_path = %I, pg_temp', current_schema());
END $$;
REVOKE ALL ON FUNCTION scheduler_cancel_requested(TEXT[], TEXT[]) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION scheduler_cancel_requested(TEXT[], TEXT[]) TO scheduler_app;
