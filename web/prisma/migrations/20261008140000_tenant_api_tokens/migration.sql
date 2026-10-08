-- Clients of the public scheduling API. Tokens are stored only as SHA-256 hex digests;
-- the plaintext is shown once when issued.
CREATE TABLE tenant (
  id TEXT PRIMARY KEY CHECK (id ~ '^[a-z0-9][a-z0-9-]{0,62}$'),
  name TEXT NOT NULL CHECK (length(btrim(name)) > 0),
  "createdAt" TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
  "disabledAt" TIMESTAMPTZ
);
CREATE TABLE tenant_api_token (
  id TEXT PRIMARY KEY,
  "tenantId" TEXT NOT NULL REFERENCES tenant(id) ON DELETE RESTRICT ON UPDATE CASCADE,
  "tokenSha256" TEXT NOT NULL UNIQUE CHECK ("tokenSha256" ~ '^[0-9a-f]{64}$'),
  label TEXT NOT NULL CHECK (length(btrim(label)) > 0),
  "createdAt" TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
  "revokedAt" TIMESTAMPTZ,
  CHECK ("revokedAt" IS NULL OR "revokedAt" >= "createdAt")
);
CREATE INDEX tenant_api_token_tenant ON tenant_api_token ("tenantId");
