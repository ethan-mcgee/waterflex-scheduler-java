// The portal's overnight worker: queues each client's due run times and works through queued runs ("Run now" and
// scheduled), one at a time, asking the scheduling API for a proposal of every day ahead. Proposals wait for a
// dispatcher; nothing is applied. Any number of workers may run; the database hands each run to one of them.
//   SCHEDULER_PUBLIC_API=true DATABASE_URL=... SCHEDULER_API_URL=... SCHEDULER_API_TOKEN_<CLIENT ID>=... npm run overnight:worker
// On SIGTERM or SIGINT the worker stops after the day in progress and abandons its run; the days done stay recorded.
import { prisma } from "../lib/prisma";
import { publicApiEnabled } from "../lib/schedulerApi";
import { abandonStaleRuns, claimRun, enqueueDueRuns, executeRun } from "../lib/overnight";

function tickMs(): number {
  const value = process.env.OVERNIGHT_TICK_SECONDS;
  if (value === undefined || value === "") return 30_000;
  if (!/^[1-9][0-9]{0,3}$/.test(value)) throw new Error(`OVERNIGHT_TICK_SECONDS must be a whole number of seconds from 1 to 9999, not "${value}"`);
  return Number(value) * 1000;
}

let stopping = false;
let wake: (() => void) | null = null;
function stop() {
  stopping = true;
  wake?.();
}
process.on("SIGTERM", stop);
process.on("SIGINT", stop);

const pause = (ms: number) => new Promise<void>(resolve => {
  const timer = setTimeout(resolve, ms);
  wake = () => { clearTimeout(timer); resolve(); };
});

async function tick() {
  const abandoned = await abandonStaleRuns();
  if (abandoned > 0) console.warn(`Abandoned ${abandoned} overnight run(s) whose worker stopped reporting`);
  const queued = await enqueueDueRuns(new Date());
  if (queued > 0) console.log(`Queued ${queued} scheduled overnight run(s)`);
  while (!stopping) {
    const claimed = await claimRun();
    if (claimed === null) return;
    console.log(`Overnight run ${claimed.runId} for client ${claimed.clientId} started`);
    const status = await executeRun(claimed.runId, claimed.clientId, { stopping: () => stopping });
    console.log(`Overnight run ${claimed.runId} ${status.toLowerCase()}`);
  }
}

async function main() {
  if (!publicApiEnabled()) throw new Error("The overnight worker schedules through the public scheduling API; set SCHEDULER_PUBLIC_API=true");
  const interval = tickMs();
  console.log(`Overnight worker started, checking every ${interval / 1000} s`);
  while (!stopping) {
    try { await tick(); }
    catch (error) { console.error("Overnight worker tick failed", error); }
    if (!stopping) await pause(interval);
  }
  console.log("Overnight worker stopped");
}

main().catch(error => { console.error(error); process.exitCode = 1; }).finally(() => prisma.$disconnect());
