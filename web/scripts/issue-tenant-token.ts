// Issues or revokes public API tokens. The plaintext token is printed once and never stored.
//   npx tsx scripts/issue-tenant-token.ts issue <tenantId> "<tenant name>" "<token label>"
//   npx tsx scripts/issue-tenant-token.ts revoke <tokenId>
import { randomUUID } from "node:crypto";
import { PrismaClient } from "@prisma/client";
import { generateTenantToken, TENANT_ID_PATTERN, tenantTokenSha256 } from "../lib/tenantTokens";

const prisma = new PrismaClient();

async function issue(tenantId: string, name: string, label: string) {
  if (!TENANT_ID_PATTERN.test(tenantId)) throw new Error("Tenant ID must be lowercase letters, digits and hyphens (1 to 63 characters)");
  if (!name.trim() || !label.trim()) throw new Error("Tenant name and token label are required");
  const token = generateTenantToken();
  const id = randomUUID();
  await prisma.$transaction(async tx => {
    const existing = await tx.tenant.findUnique({ where: { id: tenantId } });
    if (existing === null) await tx.tenant.create({ data: { id: tenantId, name: name.trim() } });
    else if (existing.disabledAt !== null) throw new Error(`Tenant ${tenantId} is disabled`);
    await tx.tenantApiToken.create({ data: { id, tenantId, tokenSha256: tenantTokenSha256(token), label: label.trim() } });
  });
  console.log(`Token ${id} issued for tenant ${tenantId}. Store it now; it cannot be shown again:\n${token}`);
}

async function revoke(tokenId: string) {
  const result = await prisma.tenantApiToken.updateMany({ where: { id: tokenId, revokedAt: null }, data: { revokedAt: new Date() } });
  if (result.count !== 1) throw new Error(`No active token ${tokenId}`);
  console.log(`Token ${tokenId} revoked.`);
}

async function main() {
  const [command, first, second, third, ...extra] = process.argv.slice(2);
  if (extra.length === 0 && command === "issue" && first !== undefined && second !== undefined && third !== undefined) await issue(first, second, third);
  else if (extra.length === 0 && command === "revoke" && first !== undefined && second === undefined) await revoke(first);
  else throw new Error('Usage: issue <tenantId> "<tenant name>" "<token label>" | revoke <tokenId>');
}

main().catch(error => { console.error(error instanceof Error ? error.message : error); process.exitCode = 1; })
  .finally(() => prisma.$disconnect());
