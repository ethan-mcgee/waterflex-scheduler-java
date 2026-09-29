import assert from 'node:assert/strict';
import { spawn, execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { mkdirSync, openSync, closeSync, readFileSync, writeFileSync } from 'node:fs';
import { resolve, join } from 'node:path';
import { setTimeout as delay } from 'node:timers/promises';

const root = resolve('.');
const variant = process.env.FIELD_VARIANT ?? 'INSERTION';
assert.ok(['INSERTION', 'BOUNDED', 'EXPANDED', 'RUIN_RECREATE', 'SHARED'].includes(variant));
const database = new URL(process.env.DATABASE_URL);
assert.equal(database.pathname, '/waterflex_test');
const port = Number(process.env.FIELD_PORT ?? 18020);
assert.ok(Number.isInteger(port) && port >= 18020 && port <= 18100);
const revision = execFileSync('git', ['rev-parse', 'HEAD'], { encoding: 'utf8' }).trim();
const output = resolve(process.env.FIELD_OUTPUT ?? 'docs/evidence/scheduler-field-2026-09-29');
mkdirSync(output, { recursive: true });
const jar = resolve(process.env.FIELD_JAR ?? 'scheduler-service/target/scheduler-service-0.1.0-SNAPSHOT.jar');
const hash = createHash('sha256').update(readFileSync(jar)).digest('hex');
const java = process.env.JAVA_HOME ? join(process.env.JAVA_HOME, 'bin/java.exe') : 'java';
function launch(command, args, env, log, cwd = root) {
  const handle = openSync(log, 'a');
  const process = spawn(command, args, { cwd, env, windowsHide: true, stdio: ['ignore', handle, handle] });
  closeSync(handle);
  const done = new Promise((resolve, reject) => { process.once('error', reject); process.once('exit', resolve); });
  return { process, done };
}
for (const seed of (process.env.FIELD_SEEDS ?? '17,23,41').split(',')) {
  assert.match(seed, /^\d+$/);
  const schema = `benchmark_field_${variant.toLowerCase()}_${seed}_${Date.now()}`;
  database.searchParams.set('schema', schema);
  const jdbc = new URL(database); jdbc.username = ""; jdbc.password = ""; jdbc.search = `?currentSchema=${schema}`;
  const name = `booking-${variant.toLowerCase()}-${seed}-${process.env.FIELD_STAGE ?? 'screen'}`;
  const serverLog = join(output, `${name}.server.log`);
  const env = { ...process.env, DATABASE_URL: database.toString(), JDBC_DATABASE_URL: `jdbc:${jdbc}`, JAVA_HOME: process.env.JAVA_HOME,
    ROUTING_URL: process.env.ROUTING_URL ?? 'http://127.0.0.1:8001', ENGINE_URL: `http://127.0.0.1:${port}`,
    BENCHMARK_REVISION: revision, BENCHMARK_VARIANT: variant, BENCHMARK_ARTIFACT_SHA256: hash,
    BENCHMARK_OUTPUT: join(output, `${name}.jsonl`), BENCHMARK_LOG_PATH: serverLog,
    BENCHMARK_SIZES: process.env.FIELD_SIZES ?? '10,20', BENCHMARK_WORKLOADS: process.env.FIELD_WORKLOADS ?? 'CLUSTERED',
    BENCHMARK_SEED: seed, BENCHMARK_CONCURRENCY: process.env.FIELD_CONCURRENCY ?? '1',
    BENCHMARK_CACHES: process.env.FIELD_CACHES ?? 'warm', BENCHMARK_REQUESTS: process.env.FIELD_REQUESTS ?? '3', BENCHMARK_DURABLE: 'true' };
  const migrate = launch(process.execPath, [resolve('web/node_modules/prisma/build/index.js'), 'migrate', 'deploy'], env, join(output, `${name}.migrate.log`), resolve('web'));
  assert.equal(await migrate.done, 0, 'Migration failed');
  const server = launch(java, ['-Xmx1536m', '-jar', jar, `--server.port=${port}`, '--spring.profiles.active=benchmark',
    '--booking.search.bounded=true', `--booking.search.variant=${variant}`], env, serverLog);
  try {
    let ready = false;
    for (let attempt = 0; attempt < 60; attempt++) {
      if (server.process.exitCode !== null) throw new Error('Benchmark scheduler exited before health check');
      try { ready = (await fetch(`${env.ENGINE_URL}/health`, { signal: AbortSignal.timeout(1000) })).ok; } catch { /* Not bound yet. */ }
      if (ready) break;
      await delay(1000);
    }
    assert.ok(ready, 'Benchmark scheduler failed to start');
    writeFileSync(join(output, `${name}.runtime.json`), JSON.stringify({ revision, jarSha256: hash, schema, port, variant, seed,
      routingUrl: env.ROUTING_URL, sizes: env.BENCHMARK_SIZES, concurrency: env.BENCHMARK_CONCURRENCY, caches: env.BENCHMARK_CACHES,
      requests: env.BENCHMARK_REQUESTS, startedAt: new Date().toISOString(), concurrentLocalBenchmarks: true }, null, 2));
    const benchmark = launch(process.execPath, [resolve('web/node_modules/tsx/dist/cli.mjs'), 'scripts/benchmark-scheduling.ts'], env,
      join(output, `${name}.client.log`), resolve('web'));
    assert.equal(await benchmark.done, 0, `Benchmark failed; inspect ${name}.client.log and retained schema ${schema}`);
  } finally { server.process.kill(); await server.done; }
}
