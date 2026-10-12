// Issues or revokes public API tokens. The plaintext token is printed once and never stored.
//   npx tsx scripts/issue-tenant-token.ts issue <tenantId> "<tenant name>" "<token label>"
//   npx tsx scripts/issue-tenant-token.ts revoke <tokenId>
import { PrismaClient } from "@prisma/client";
import { issueTenantToken } from "../lib/tenantTokenIssue";

const prisma = new PrismaClient();

async function issue(tenantId: string, name: string, label: string) {
  const { id, token } = await issueTenantToken(prisma, tenantId, name, label);
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
