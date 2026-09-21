import { PrismaClient } from "@prisma/client";

// Standard Next.js dev-mode singleton to avoid exhausting connections
// across hot reloads.
declare global { var waterflexPrisma: PrismaClient | undefined; }
const globalForPrisma = globalThis;

export const prisma =
  globalForPrisma.waterflexPrisma ??
  new PrismaClient({
    log: process.env.NODE_ENV === "development" ? ["warn", "error"] : ["error"],
  });

if (process.env.NODE_ENV !== "production") {
  globalForPrisma.waterflexPrisma = prisma;
}
