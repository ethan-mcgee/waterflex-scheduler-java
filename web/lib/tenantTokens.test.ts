import test from "node:test";
import assert from "node:assert/strict";
import { generateTenantToken, TENANT_ID_PATTERN, TENANT_TOKEN_PATTERN, tenantTokenSha256 } from "./tenantTokens";

// Shared with scheduler-service TenantTokensTest so both sides hash identically.
const VECTOR = "wfs_AAAAAAAAAAAAAAAAAAAA-_b9b9b9b9b9b9b9b9b9b9z";
const VECTOR_SHA256 = "e272c126d6d2fa192e81378fa257a85bd6b44830492c8c2141cc6928235f35db";

test("tenant token digest matches the Java scheduler", () => {
  assert.equal(tenantTokenSha256(VECTOR), VECTOR_SHA256);
});

test("generated tokens are well formed and distinct", () => {
  const first = generateTenantToken(), second = generateTenantToken();
  assert.match(first, TENANT_TOKEN_PATTERN);
  assert.notEqual(first, second);
  assert.match(tenantTokenSha256(first), /^[0-9a-f]{64}$/);
});

test("malformed tokens and tenant IDs are rejected", () => {
  for (const token of ["", "wfs_", "wfs_short", `xyz_${"A".repeat(43)}`, `wfs_${"A".repeat(42)}=`, `wfs_${"A".repeat(44)}`])
    assert.throws(() => tenantTokenSha256(token));
  for (const id of ["acme", "acme-water-2", "a"]) assert.match(id, TENANT_ID_PATTERN);
  for (const id of ["", "-acme", "Acme", "acme_water", "a".repeat(64)]) assert.doesNotMatch(id, TENANT_ID_PATTERN);
});
