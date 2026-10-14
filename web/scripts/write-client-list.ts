// Local Compose only: writes the portal's clients as [{"id","name"}] for the scheduler's token bootstrap
// (java -jar scheduler-service.jar --command=bootstrap-local-tokens <file> <dir>), which never reads this database.
//   DATABASE_URL=... npx tsx scripts/write-client-list.ts /run/portal-api-tokens/clients.json
import { renameSync, writeFileSync } from "node:fs";
import { createPrismaClient } from "../lib/portalDatabase";

async function main() {
  const target = process.argv[2];
  if (target === undefined || target === "") throw new Error("Usage: write-client-list.ts <output file>");
  const prisma = createPrismaClient();
  try {
    const clients = await prisma.client.findMany({ select: { id: true, name: true }, orderBy: { id: "asc" } });
    const temporary = `${target}.tmp`;
    writeFileSync(temporary, `${JSON.stringify(clients)}\n`, { mode: 0o600 });
    renameSync(temporary, target);
    console.log(`Wrote ${clients.length} client(s) to ${target}`);
  } finally {
    await prisma.$disconnect();
  }
}

main().catch(error => { console.error(error instanceof Error ? error.message : error); process.exitCode = 1; });
