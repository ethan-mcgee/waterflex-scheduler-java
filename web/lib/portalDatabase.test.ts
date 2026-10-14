import { test } from "node:test";
import assert from "node:assert/strict";
import { CONNECTION_WAIT_MS, createPrismaClient, portalConnection, portalPoolConfig } from "./portalDatabase";

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

test("pool settings bound the wait for a connection and carry the schema as the search_path", () => {
  const config = portalPoolConfig({ connectionString: "postgresql://u:p@db/waterflex", schema: "nullability_ui" });
  assert.equal(config.connectionTimeoutMillis, CONNECTION_WAIT_MS);
  assert.ok(CONNECTION_WAIT_MS > 0, "pg treats 0 as waiting forever");
  assert.equal(config.options, "-c search_path=nullability_ui");
  assert.equal(portalPoolConfig({ connectionString: "postgresql://u:p@db/waterflex", schema: null }).options, undefined);
});

test("a database that never answers fails the query within the bound instead of hanging", async () => {
  const { createServer } = await import("node:net");
  const silent = createServer(() => { /* accept and never reply */ });
  await new Promise<void>(resolve => silent.listen(0, "127.0.0.1", resolve));
  const address = silent.address();
  assert.ok(address !== null && typeof address === "object");
  const saved = process.env.DATABASE_URL;
  process.env.DATABASE_URL = `postgresql://u:p@127.0.0.1:${address.port}/waterflex`;
  const started = Date.now();
  try {
    const client = createPrismaClient();
    await assert.rejects(client.$queryRaw`SELECT 1`);
    assert.ok(Date.now() - started < CONNECTION_WAIT_MS + 5_000, "the query failed within the connection bound");
  } finally {
    if (saved === undefined) delete process.env.DATABASE_URL; else process.env.DATABASE_URL = saved;
    silent.close();
  }
});
