// Imports the reason code catalog, keyed by code: a new code is added and a known one gets the file's label and
// minutes. A buffer left blank gives a new code the catalog's default and leaves a known code's buffer as it is.
// Codes missing from the file are left alone; retire them on the Reason codes settings page.
//   npx tsx scripts/import-reason-codes.ts --file <reason-codes.csv>
// Columns: code, label, minutes, and optionally buffer_pct (0.15 or 15%).
import { readFileSync } from "node:fs";
import { createPrismaClient } from "../lib/portalDatabase";
import { parseReasonCodes } from "../lib/waterflexImportCore";

const prisma = createPrismaClient();

async function main() {
  const args = process.argv.slice(2);
  const index = args.indexOf("--file");
  const file = index < 0 ? undefined : args[index + 1];
  if (file === undefined) throw new Error("Usage: --file <reason-codes.csv>");
  const { codes, problems } = parseReasonCodes(readFileSync(file, "utf8"));
  const known = new Set((await prisma.serviceCatalog.findMany({ select: { code: true } })).map(row => row.code));
  await prisma.$transaction(codes.map(({ code, name, estDurationMin, bufferPct }) => prisma.serviceCatalog.upsert({
    where: { code },
    create: { code, name, estDurationMin, ...(bufferPct === null ? {} : { bufferPct }) },
    update: { name, estDurationMin, ...(bufferPct === null ? {} : { bufferPct }) },
  })));
  const added = codes.filter(code => !known.has(code.code)).length;
  console.log(`Imported ${codes.length} reason codes: ${added} new, ${codes.length - added} updated.`);
  if (problems.length > 0) {
    console.log(`Skipped ${problems.length} rows:`);
    for (const problem of problems) console.log(`  line ${problem.line}: ${problem.message}`);
  }
}

main().catch(error => { console.error(error instanceof Error ? error.message : error); process.exitCode = 1; })
  .finally(() => prisma.$disconnect());
