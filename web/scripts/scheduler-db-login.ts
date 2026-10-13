// Creates or updates the scheduler's least-privilege database login (a member of scheduler_app). Run after the
// migrations, as the migrating user:
//   SCHEDULER_DB_LOGIN=scheduler SCHEDULER_DB_PASSWORD=... DATABASE_URL=... npx tsx scripts/scheduler-db-login.ts
import { PrismaClient } from "@prisma/client";
import { schedulerLogin, schedulerLoginStatements } from "../lib/schedulerDbLogin";

async function main() {
  const login = schedulerLogin(process.env);
  const prisma = new PrismaClient();
  try {
    for (const statement of schedulerLoginStatements(login)) await prisma.$executeRawUnsafe(statement);
    console.log(`Scheduler database login ${login.login} is a member of scheduler_app`);
  } finally {
    await prisma.$disconnect();
  }
}

main().catch(error => { console.error(error instanceof Error ? error.message : error); process.exitCode = 1; });
