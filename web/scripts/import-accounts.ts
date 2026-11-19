// Imports a client's WaterFlex accounts from WaterFlex's account export, keyed by account number: a new number is
// added and a known one is updated. Accounts without coordinates are kept without them; rows that cannot be read are
// listed and skipped.
//   npx tsx scripts/import-accounts.ts --client <clientId> --file <accounts.csv>
// Columns: account_number, house_number, street, city, state, postal_code (or zip), lattitude (or latitude), longitude,
// and optionally line2, name, phone and email.
import { readFileSync } from "node:fs";
import { createPrismaClient } from "../lib/portalDatabase";
import { parseAccounts } from "../lib/waterflexImportCore";

const prisma = createPrismaClient();
const BATCH = 200;

function option(args: readonly string[], name: string): string {
  const index = args.indexOf(name);
  const value = index < 0 ? undefined : args[index + 1];
  if (value === undefined || value.startsWith("--")) throw new Error("Usage: --client <clientId> --file <accounts.csv>");
  return value;
}

async function main() {
  const args = process.argv.slice(2);
  const clientId = option(args, "--client"), file = option(args, "--file");
  if ((await prisma.client.findUnique({ where: { id: clientId } })) === null) throw new Error(`There is no client ${clientId}`);
  const { accounts, problems, missingCoordinates } = parseAccounts(readFileSync(file, "utf8"));
  const importedAt = new Date();
  const known = new Set((await prisma.account.findMany({ where: { clientId }, select: { accountNumber: true } })).map(row => row.accountNumber));
  for (let start = 0; start < accounts.length; start += BATCH) {
    await prisma.$transaction(accounts.slice(start, start + BATCH).map(({ accountNumber, ...fields }) => prisma.account.upsert({
      where: { clientId_accountNumber: { clientId, accountNumber } },
      create: { clientId, accountNumber, ...fields, source: "WATERFLEX_IMPORT", importedAt },
      update: { ...fields, source: "WATERFLEX_IMPORT", importedAt },
    })));
  }
  const added = accounts.filter(account => !known.has(account.accountNumber)).length;
  console.log(`Imported ${accounts.length} accounts for ${clientId}: ${added} new, ${accounts.length - added} updated, ${missingCoordinates} without coordinates (follow-up only).`);
  if (problems.length > 0) {
    console.log(`Skipped ${problems.length} rows:`);
    for (const problem of problems) console.log(`  line ${problem.line}: ${problem.message}`);
  }
}

main().catch(error => { console.error(error instanceof Error ? error.message : error); process.exitCode = 1; })
  .finally(() => prisma.$disconnect());
