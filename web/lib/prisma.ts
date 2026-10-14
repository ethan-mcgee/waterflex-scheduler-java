import type { PrismaClient } from "@prisma/client";
import { createPrismaClient } from "@/lib/portalDatabase";

// Standard Next.js dev-mode singleton to avoid exhausting connections
// across hot reloads.
declare global { var waterflexPrisma: PrismaClient | undefined; }
const globalForPrisma = globalThis;

export const prisma =
  globalForPrisma.waterflexPrisma ??
  createPrismaClient({
    log: process.env.NODE_ENV === "development" ? ["warn", "error"] : ["error"],
  });

if (process.env.NODE_ENV !== "production") {
  globalForPrisma.waterflexPrisma = prisma;
}
