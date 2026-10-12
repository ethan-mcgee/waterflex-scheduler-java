import { randomUUID } from "node:crypto";
import type { PrismaClient } from "@prisma/client";
import { generateTenantToken, TENANT_ID_PATTERN, tenantTokenSha256 } from "./tenantTokens";

/**
 * Issues a token for the tenant, creating the tenant when it does not exist. Only the digest is stored; the plaintext is
 * returned once. With `replacingLabel`, the tenant's earlier active tokens with that label are revoked in the same
 * transaction (used when a lost token file is reissued).
 */
export async function issueTenantToken(prisma: PrismaClient, tenantId: string, name: string, label: string,
  replacingLabel?: string): Promise<{ id: string; token: string }> {
  if (!TENANT_ID_PATTERN.test(tenantId)) throw new Error("Tenant ID must be lowercase letters, digits and hyphens (1 to 63 characters)");
  if (!name.trim() || !label.trim()) throw new Error("Tenant name and token label are required");
  const token = generateTenantToken();
  const id = randomUUID();
  await prisma.$transaction(async tx => {
    const existing = await tx.tenant.findUnique({ where: { id: tenantId } });
    if (existing === null) await tx.tenant.create({ data: { id: tenantId, name: name.trim() } });
    else if (existing.disabledAt !== null) throw new Error(`Tenant ${tenantId} is disabled`);
    if (replacingLabel !== undefined)
      await tx.tenantApiToken.updateMany({ where: { tenantId, label: replacingLabel, revokedAt: null }, data: { revokedAt: new Date() } });
    await tx.tenantApiToken.create({ data: { id, tenantId, tokenSha256: tenantTokenSha256(token), label: label.trim() } });
  });
  return { id, token };
}
