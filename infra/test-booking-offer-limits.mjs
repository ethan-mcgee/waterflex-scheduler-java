import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { closeSync, mkdtempSync, openSync, readFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { createServer } from 'node:net';
import { join, resolve } from 'node:path';
import { setTimeout as delay } from 'node:timers/promises';

// Run against migrated waterflex_test and the fixture router. Each instance owns only test ports.
assert.equal(new URL(process.env.DATABASE_URL).pathname, '/waterflex_test');
assert.equal(new URL(process.env.JDBC_DATABASE_URL.replace(/^jdbc:/, '')).pathname, '/waterflex_test');
assert.ok(process.env.ROUTING_URL, 'Set ROUTING_URL to the fixture router');
const logs = mkdtempSync(join(tmpdir(), 'booking-offer-limits-'));
const jar = resolve('scheduler-service/target/scheduler-service-0.1.0-SNAPSHOT.jar');
const tsx = resolve('web/node_modules/tsx/dist/cli.mjs');
const java = process.env.JAVA_HOME
  ? join(process.env.JAVA_HOME, 'bin', process.platform === 'win32' ? 'java.exe' : 'java') : 'java';

async function start(limit, reservations, port) {
  await new Promise((available, reject) => {
    const probe = createServer();
    probe.once('error', reject);
    probe.listen(port, '127.0.0.1', () => probe.close(available));
  });
  const log = join(logs, `${reservations}-${limit}.log`);
  const output = openSync(log, 'w');
  const child = spawn(java, ['-Xmx512m', '-jar', jar, `--server.port=${port}`], {
    windowsHide: true, stdio: ['ignore', output, output],
    env: { ...process.env, BOOKING_OFFER_LIMIT: String(limit), BOOKING_RESERVATIONS_ENABLED: String(reservations), BOOKING_SEARCH_BOUNDED: String(reservations) },
  });
  closeSync(output);
  const stopped = new Promise(resolveExit => child.once('exit', resolveExit));
  let startError;
  child.once('error', error => { startError = error; });
  const stop = async () => { if (child.exitCode === null && !startError) { child.kill(); await stopped; } };
  const base = `http://127.0.0.1:${port}`;
  try {
    for (let attempt = 0; attempt < 90; attempt++) {
      if (startError) throw startError;
      if (child.exitCode !== null) throw new Error(`Scheduler exited: ${child.exitCode}`);
      try {
        if ((await fetch(`${base}/health`, { signal: AbortSignal.timeout(1000) })).ok) return { base, stop, log };
      } catch { /* Wait for this test instance to bind its port. */ }
      await delay(1000);
    }
    throw new Error(`Scheduler on ${port} did not become healthy`);
  } catch (error) {
    await stop();
    console.error(readFileSync(log, 'utf8'));
    throw error;
  }
}

async function smoke(instance, limit, reservations, previous) {
  const child = spawn(process.execPath, [tsx, 'scripts/smoke-java-booking.ts'], {
    cwd: resolve('web'), windowsHide: true, stdio: 'inherit',
    env: { ...process.env, SCHEDULER_TEST_URL: instance.base, BOOKING_OFFER_LIMIT: String(limit),
      SCHEDULER_RESERVATIONS_TEST: String(reservations), SCHEDULER_PREVIOUS_LIMIT_URL: previous.base },
  });
  const status = await new Promise((resolveExit, reject) => { child.once('error', reject); child.once('exit', resolveExit); });
  if (status !== 0) {
    console.error(readFileSync(instance.log, 'utf8'));
    throw new Error(`Booking lifecycle failed for limit=${limit}, reservations=${reservations}`);
  }
}

for (const reservations of [false, true]) {
  const previous = await start(4, reservations, 18010);
  try {
    await smoke(previous, 4, reservations, previous);
    for (const limit of [1, 2]) {
      const current = await start(limit, reservations, 18011);
      try { await smoke(current, limit, reservations, previous); }
      finally { await current.stop(); }
    }
  } finally { await previous.stop(); }
}
console.log('All six offer-limit/path combinations passed. Scheduler logs:', logs);
