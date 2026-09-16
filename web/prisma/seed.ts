import { PrismaClient } from "@prisma/client";
import { ensureOmahaConfiguration } from "../lib/omahaConfiguration";

const prisma = new PrismaClient();

async function main() {
  await ensureOmahaConfiguration(prisma);
  console.log("Seed complete.");
}

main()
  .catch((e) => {
    console.error(e);
    process.exit(1);
  })
  .finally(async () => {
    await prisma.$disconnect();
  });
