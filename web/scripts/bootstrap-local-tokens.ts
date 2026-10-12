// Local Compose only: issues a public API token for every client that has no token file yet and writes it to
// SCHEDULER_API_TOKEN_DIR/<clientId>, which the portal reads when SCHEDULER_API_TOKEN_<CLIENT ID> is unset.
//   LOCAL_TOKEN_BOOTSTRAP=true SCHEDULER_API_TOKEN_DIR=... DATABASE_URL=... npx tsx scripts/bootstrap-local-tokens.ts
import { PrismaClient } from "@prisma/client";
import { bootstrapLocalTokens, LOCAL_TOKEN_LABEL } from "../lib/localTokenBootstrap";
import { issueTenantToken } from "../lib/tenantTokenIssue";

const prisma = new PrismaClient();

bootstrapLocalTokens(process.env, {
  clients: () => prisma.client.findMany({ select: { id: true, name: true }, orderBy: { id: "asc" } }),
  issue: async client => (await issueTenantToken(prisma, client.id, client.name, LOCAL_TOKEN_LABEL, LOCAL_TOKEN_LABEL)).token,
}).then(({ issued, kept }) => {
  console.log(`Local API tokens: issued for [${issued.join(", ")}], kept for [${kept.join(", ")}]`);
}).catch(error => { console.error(error instanceof Error ? error.message : error); process.exitCode = 1; })
  .finally(() => prisma.$disconnect());
