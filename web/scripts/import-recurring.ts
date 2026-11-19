// Imports customers' recurring services for one client, keyed by account and first reason code: a new series is
// added, and a known one gets the file's other codes, type, interval and dates and is made active again. The accounts
// and reason codes must already be imported. Series missing from the file are left alone.
//   npx tsx scripts/import-recurring.ts --client <client-id> --file <recurring.csv>
// Columns: account_number, reason_code, optionally reason_code_2 and reason_code_3, work_order_type, every, unit
// (weeks or months), start_date (the first due date) and optionally last_visit_date.
import { readFileSync } from "node:fs";
import { createPrismaClient } from "../lib/portalDatabase";
import { parseRecurringSeries, type ImportProblem } from "../lib/waterflexImportCore";

const prisma = createPrismaClient();
const utcDay = (value: string) => new Date(`${value}T00:00:00.000Z`);

function option(args: readonly string[], name: string): string | undefined {
  const index = args.indexOf(name);
  return index < 0 ? undefined : args[index + 1];
}

async function main() {
  const args = process.argv.slice(2);
  const clientId = option(args, "--client"), file = option(args, "--file");
  if (clientId === undefined || file === undefined) throw new Error("Usage: --client <client-id> --file <recurring.csv>");
  if ((await prisma.client.findUnique({ where: { id: clientId } })) === null) throw new Error(`There is no client ${clientId}.`);
  const { series, problems } = parseRecurringSeries(readFileSync(file, "utf8"));
  const accounts = new Map((await prisma.account.findMany({ where: { clientId, accountNumber: { in: series.map(row => row.accountNumber) } },
    select: { id: true, accountNumber: true } })).map(row => [row.accountNumber, row.id]));
  const codes = new Map((await prisma.serviceCatalog.findMany({ where: { code: { in: series.flatMap(row => row.reasonCodes) } },
    select: { id: true, code: true, active: true } })).map(row => [row.code, row]));
  const skipped: ImportProblem[] = [];
  let added = 0, updated = 0;
  for (const row of series) {
    const accountId = accounts.get(row.accountNumber);
    const found = row.reasonCodes.map(code => codes.get(code));
    const missingCode = row.reasonCodes.find((code, index) => found[index]?.active !== true);
    if (accountId === undefined) { skipped.push({ line: 0, message: `Account ${row.accountNumber} is not imported for this client` }); continue; }
    if (missingCode !== undefined) { skipped.push({ line: 0, message: `Account ${row.accountNumber}: reason code ${missingCode} is unknown or retired` }); continue; }
    const [first, ...extra] = found.filter(code => code !== undefined);
    if (first === undefined) continue;
    const fields = { workOrderType: row.workOrderType, intervalUnit: row.intervalUnit, intervalCount: row.intervalCount,
      startDate: utcDay(row.startDate), lastVisitDate: row.lastVisitDate === null ? null : utcDay(row.lastVisitDate), active: true };
    const extraRows = extra.map((code, index) => ({ position: index + 2, reasonCodeId: code.id }));
    await prisma.$transaction(async tx => {
      const existing = await tx.recurringSeries.findUnique({ where: { accountId_serviceId: { accountId, serviceId: first.id } } });
      if (existing === null) {
        await tx.recurringSeries.create({ data: { clientId, accountId, serviceId: first.id, ...fields, reasonCodes: { create: extraRows } } });
        added += 1;
      } else {
        await tx.recurringReasonCode.deleteMany({ where: { seriesId: existing.id } });
        await tx.recurringSeries.update({ where: { id: existing.id }, data: { ...fields, reasonCodes: { create: extraRows } } });
        updated += 1;
      }
    });
  }
  console.log(`Imported ${added + updated} recurring series for ${clientId}: ${added} new, ${updated} updated.`);
  const all = [...problems.map(problem => `line ${problem.line}: ${problem.message}`), ...skipped.map(problem => problem.message)];
  if (all.length > 0) {
    console.log(`Skipped ${all.length} rows:`);
    for (const message of all) console.log(`  ${message}`);
  }
}

main().catch(error => { console.error(error instanceof Error ? error.message : error); process.exitCode = 1; })
  .finally(() => prisma.$disconnect());
