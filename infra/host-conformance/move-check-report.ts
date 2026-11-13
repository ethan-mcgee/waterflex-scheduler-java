// Builds the move check timing study's report from one run directory (infra/run-move-check-timing.ps1 calls it):
//   npx tsx move-check-report.ts --run-dir <dir> --evidence <research/evidence/move-check-timing-<stamp>.json>
// It reads runs.jsonl (one row per drag, web/timing/moveCheck.timing.ts), scheduler.log (`docker logs --timestamps` of
// the booking replicas) and environment.json, and writes report.md beside them and the evidence file. An existing
// evidence file is never overwritten.
import { existsSync, readFileSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { z } from "zod";
import { evaluateLines, groups, join as joinDrags, parseRows, stepTables } from "./src/load/movecheck.ts";

function argument(name: string): string {
  const at = process.argv.indexOf(name);
  const value = at < 0 ? undefined : process.argv[at + 1];
  if (value === undefined || value.startsWith("--")) throw new Error(`Missing ${name}`);
  return value;
}

const runDir = argument("--run-dir");
const evidencePath = argument("--evidence");
const tenant = process.argv.includes("--tenant") ? argument("--tenant") : "move-timing";
if (existsSync(evidencePath)) throw new Error(`Evidence already exists: ${evidencePath}`);

const environment = z.object({ runId: z.string().min(1), stack: z.string().min(1), commit: z.string().min(1), dirty: z.boolean(),
  startedAt: z.iso.datetime(), schedulerUrl: z.string().min(1), portalUrl: z.string().min(1), delaysMs: z.array(z.int().min(0)) }).loose()
  .parse(JSON.parse(readFileSync(join(runDir, "environment.json"), "utf8")));
const rows = parseRows(readFileSync(join(runDir, "runs.jsonl"), "utf8"));
const lines = evaluateLines(readFileSync(join(runDir, "scheduler.log"), "utf8"), tenant);
const { joined, paired, reason } = joinDrags(rows, lines);
const list = groups(joined);
const invalid = joined.filter(item => item.invalid !== null && !item.row.discarded);
const delayCases = environment.delaysMs.filter(ms => ms > 0);

const report = [
  "# Move check timing",
  `Run \`${environment.runId}\` on ${environment.stack}, commit \`${environment.commit}\`${environment.dirty ? " with uncommitted changes" : ""}, started ${environment.startedAt}. The portal ran as a production build at ${environment.portalUrl} and called the scheduler at ${environment.schedulerUrl}. Every drag moved a visit from a technician with six visits to another technician on the same day; the portal checked the move with \`POST /api/v1/routes/evaluate\` and saved nothing.`,
  "## How the time is split",
  [
    "- **Drop to painted:** the browser's measure from the mouse release to the answer drawn on screen.",
    "- **Browser handler:** from the drop to the request leaving the page.",
    "- **To portal and back:** the request's Resource Timing duration minus the portal's own total, so travel both ways plus the portal server's work outside the route handler. Derived.",
    "- **Portal:** the route handler's total from its Server-Timing header, split into scope (client and metro checks), loading the day's facts from Postgres, assembling the snapshot, and the scheduler call.",
    "- **Proxy and transport:** the portal's scheduler call minus the scheduler's own elapsed time: the HTTP hop through nginx to the booking replica and back, and the portal's JSON work. Derived.",
    "- **Scheduler:** the request log line: slot wait, drive time lookups (cache reads and the routing service), timing the routes, database time (part of it inside the drive time lookups), and the rest of the call. Other is derived.",
    "- **Render:** from the response's last byte to the end of the measure. Derived. The measure ends in the animation frame after the one that paints the answer, so render, and drop to painted with it, overstate the wait by up to one frame (about 17 ms at 60 Hz).",
  ].join("\n"),
  "## Simulated delay",
  `${delayCases.length === 0 ? "No case added a simulated delay." : `The ${delayCases.map(ms => `+${ms}`).join(", ")} ms cases add that round trip delay to the browser's connection to the portal through Chromium's network emulation.`} The delays are simulated on one workstation. The portal to scheduler hop stays local in every case; a portal far from the scheduler would add its own round trip to the scheduler call.`,
  "## Validity",
  `${rows.length} drags were recorded and the scheduler logged ${lines.length} route evaluations for the tenant. ${paired ? "The counts agree, so each one-at-a-time drag is paired with its log line in order." : `The drags are not paired with the log (${reason ?? "unknown reason"}), so every scheduler column is not measured.`} ${invalid.length === 0 ? "Every measured drag passed its checks." : `${invalid.length} drags failed a check and are left out of the statistics; they are listed at the end.`} Concurrent drags are never paired with log lines.`,
  "## Medians",
  "The tables show the median of each step per case, over the drags that passed their checks.",
  stepTables(list, "median"),
  "## 95th percentiles",
  "The tables show the 95th percentile of each step per case, by nearest rank.",
  stepTables(list, "p95"),
  ...(invalid.length === 0 ? [] : ["## Drags left out", "The list names each drag that failed a check.",
    invalid.map(item => `- **Drag ${item.row.seq} (${item.row.case}, ${item.row.expected}, +${item.row.delayMs} ms):** ${item.invalid ?? ""}.`).join("\n")]),
].join("\n\n") + "\n";

writeFileSync(join(runDir, "report.md"), report);
writeFileSync(evidencePath, `${JSON.stringify({ study: "move-check-timing", generatedAt: new Date().toISOString(), environment,
  drags: rows.length, evaluateLines: lines.length, paired, pairingProblem: reason,
  invalid: invalid.map(item => ({ seq: item.row.seq, case: item.row.case, reason: item.invalid })), groups: list }, null, 2)}\n`);
console.log(`Wrote ${join(runDir, "report.md")} and ${evidencePath}`);
