// Prisma 7 reads the CLI's connection and seed command here; the schema no longer names a URL. The URL is read only
// when a command needs the database (migrate, seed, db): `prisma generate` runs without one, as in the Docker build.
import { defineConfig } from "prisma/config";

export default defineConfig({
  schema: "prisma/schema.prisma",
  migrations: { path: "prisma/migrations", seed: "tsx prisma/seed.ts" },
  datasource: { url: process.env.DATABASE_URL },
});
