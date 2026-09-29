import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { mkdtempSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

const envFile = join(mkdtempSync(join(tmpdir(), 'booking-offer-compose-')), '.env');
function effective(shell, file = '') {
  writeFileSync(envFile, file);
  const env = { ...process.env };
  delete env.BOOKING_OFFER_LIMIT;
  if (shell !== undefined) env.BOOKING_OFFER_LIMIT = shell;
  const config = JSON.parse(execFileSync('docker', ['compose', '--env-file', envFile, 'config', '--format', 'json'], { env, encoding: 'utf8' }));
  return config.services['scheduler-service'].environment.BOOKING_OFFER_LIMIT;
}
assert.equal(effective(undefined), '4');
for (const limit of ['1', '2', '4']) {
  assert.equal(effective(limit), limit);
  assert.equal(effective(undefined, `BOOKING_OFFER_LIMIT=${limit}\n`), limit);
}
assert.equal(effective('1', 'BOOKING_OFFER_LIMIT=2\n'), '1');
assert.equal(effective(''), '', 'Explicit empty environment must reach startup validation');
assert.equal(effective(undefined, 'BOOKING_OFFER_LIMIT=\n'), '');
console.log('Compose offer limit default, allowed values, precedence, and explicit blanks passed');
