import test from "node:test";
import assert from "node:assert/strict";
import { TENANT_ID_PATTERN, TENANT_TOKEN_PATTERN } from "./tenantTokens";

// The scheduler's TenantAuthenticationTest accepts the same vector.
const VECTOR = "wfs_AAAAAAAAAAAAAAAAAAAA-_b9b9b9b9b9b9b9b9b9b9z";

test("token and tenant ID formats match the scheduler's", () => {
  assert.match(VECTOR, TENANT_TOKEN_PATTERN);
  for (const token of ["", "wfs_", "wfs_short", `xyz_${"A".repeat(43)}`, `wfs_${"A".repeat(42)}=`, `wfs_${"A".repeat(44)}`])
    assert.doesNotMatch(token, TENANT_TOKEN_PATTERN);
  for (const id of ["acme", "acme-water-2", "a"]) assert.match(id, TENANT_ID_PATTERN);
  for (const id of ["", "-acme", "Acme", "acme_water", "a".repeat(64)]) assert.doesNotMatch(id, TENANT_ID_PATTERN);
});
