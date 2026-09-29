import { spawnSync } from 'node:child_process';
import { appendFileSync } from 'node:fs';
import { performance } from 'node:perf_hooks';

const [label, command, ...args] = process.argv.slice(2);
if (!label || !command) throw new Error('Usage: node infra/run-check.mjs <label> <command> [args...]');
const started = performance.now();
const result = spawnSync(command, args, { stdio: 'inherit', env: process.env, shell: false });
const seconds = ((performance.now() - started) / 1000).toFixed(1);
const status = result.status === 0 ? 'passed' : 'failed';
console.log(`${label}: ${status} in ${seconds}s`);
if (process.env.GITHUB_STEP_SUMMARY) {
  appendFileSync(process.env.GITHUB_STEP_SUMMARY, `| Smoke check | Result | Duration |\n| --- | --- | ---: |\n| ${label.replaceAll('|', '\\|')} | ${status} | ${seconds}s |\n`);
}
if (result.error) throw result.error;
process.exitCode = result.status ?? 1;
