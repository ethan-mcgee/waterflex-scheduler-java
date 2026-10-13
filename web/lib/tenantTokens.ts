/**
 * The formats of a scheduling API token and a client (tenant) ID, checked before the portal sends a token. Tokens are
 * issued by the scheduler (java -jar scheduler-service.jar --command=issue-token); scheduler-service
 * TenantTokens.FORMAT and the tenant.id CHECK constraint must match these.
 */
export const TENANT_TOKEN_PATTERN = /^wfs_[A-Za-z0-9_-]{43}$/;
export const TENANT_ID_PATTERN = /^[a-z0-9][a-z0-9-]{0,62}$/;
