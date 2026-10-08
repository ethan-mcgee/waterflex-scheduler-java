// Creates or renames a portal client. The client's public API token stays in the environment, never in this database.
//   npx tsx scripts/create-client.ts <clientId> "<client name>"
import { PrismaClient } from "@prisma/client";
import { CLIENT_ID } from "../lib/clients";

const prisma = new PrismaClient();

async function main() {
  const [clientId, name, ...extra] = process.argv.slice(2);
  if (extra.length !== 0 || clientId === undefined || name === undefined) throw new Error('Usage: <clientId> "<client name>"');
  if (!CLIENT_ID.test(clientId)) throw new Error("Client ID must be lowercase letters, digits and hyphens (1 to 64 characters)");
  if (!name.trim()) throw new Error("Client name is required");
  const client = await prisma.client.upsert({ where: { id: clientId }, update: { name: name.trim() }, create: { id: clientId, name: name.trim() } });
  console.log(`Client ${client.id} (${client.name}) is ready.`);
}

main().catch(error => { console.error(error instanceof Error ? error.message : error); process.exitCode = 1; })
  .finally(() => prisma.$disconnect());
