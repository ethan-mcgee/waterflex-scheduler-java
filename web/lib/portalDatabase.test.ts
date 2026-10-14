import { test } from "node:test";
import assert from "node:assert/strict";
import { createPrismaClient, portalConnection } from "./portalDatabase";

test("a schema parameter leaves the URL and becomes the connection's schema", () => {
  assert.deepEqual(portalConnection("postgresql://u:p@127.0.0.1:15432/waterflex_test?schema=nullability_ui"),
    { connectionString: "postgresql://u:p@127.0.0.1:15432/waterflex_test", schema: "nullability_ui" });
});

test("a URL without a schema keeps its other parameters and names no schema", () => {
  assert.deepEqual(portalConnection("postgres://u:p@db:5432/waterflex?sslmode=require"),
    { connectionString: "postgres://u:p@db:5432/waterflex?sslmode=require", schema: null });
});

test("a schema that is not a plain identifier is refused rather than passed to search_path", () => {
  assert.throws(() => portalConnection("postgresql://u:p@db/waterflex?schema=public,evil"), /invalid schema/);
  assert.throws(() => portalConnection("postgresql://u:p@db/waterflex?schema="), /invalid schema/);
});

test("only PostgreSQL URLs are accepted", () => {
  assert.throws(() => portalConnection("mysql://u:p@db/waterflex"), /postgresql:\/\//);
});

test("without DATABASE_URL the client loads but refuses to connect, instead of reaching a default database", async () => {
  const saved = process.env.DATABASE_URL;
  delete process.env.DATABASE_URL;
  try {
    const client = createPrismaClient();
    await assert.rejects(client.$connect(), /DATABASE_URL is not set/);
  } finally {
    if (saved !== undefined) process.env.DATABASE_URL = saved;
  }
});
