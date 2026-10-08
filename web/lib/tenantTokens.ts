import { createHash, randomBytes } from "node:crypto";

/** "wfs_" plus 32 random bytes in unpadded base64url. scheduler-service TenantTokens.FORMAT must match. */
export const TENANT_TOKEN_PATTERN = /^wfs_[A-Za-z0-9_-]{43}$/;
/** Same rule as the tenant.id CHECK constraint. */
export const TENANT_ID_PATTERN = /^[a-z0-9][a-z0-9-]{0,62}$/;

export function generateTenantToken(): string {
  return `wfs_${randomBytes(32).toString("base64url")}`;
}

/** Only this digest is stored; the plaintext token is shown once when issued. */
export function tenantTokenSha256(token: string): string {
  if (!TENANT_TOKEN_PATTERN.test(token)) throw new Error("Malformed tenant API token");
  return createHash("sha256").update(token, "utf8").digest("hex");
}
