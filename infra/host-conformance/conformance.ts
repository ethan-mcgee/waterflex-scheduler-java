// Host conformance kit: runs every case against a scheduler over its public API and writes a JSON and markdown report.
//   npx tsx conformance.ts --url https://scheduler.internal --token-file ./token --metro metro-omaha \
//     --time-zone America/Chicago --area 41.20,-96.10,41.30,-95.95
// Exit status: 0 when every case passed, 1 when any failed, 2 for a usage error.
import { randomUUID } from "node:crypto";
import { mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { join, resolve } from "node:path";
import { parseArgs } from "node:util";
import { Id } from "./src/api.ts";
import { CASES, type Case, type Context } from "./src/cases.ts";
import { SchedulerApi } from "./src/client.ts";
import { areaPoints, FIXTURE_POINTS, parseArea, type Point } from "./src/points.ts";
import { KIT_VERSION, markdown, runCase, type CaseResult, type Report } from "./src/report.ts";
import { requireTimeZone, todayIn } from "./src/time.ts";

const USAGE = `Usage: npx tsx conformance.ts --url <scheduler base URL> --token-file <file> --metro <metroId> --time-zone <IANA zone>
                              (--area <south,west,north,east> | --points fixture) [--out <directory>] [--only <group,...>]`;

class UsageError extends Error { }

function required(values: Record<string, string | boolean | undefined>, name: string): string {
  const value = values[name];
  if (typeof value !== "string" || value.trim() === "") throw new UsageError(`--${name} is required`);
  return value.trim();
}

function options() {
  let parsed;
  try {
    parsed = parseArgs({ strict: true, allowPositionals: false, options: {
      url: { type: "string" }, "token-file": { type: "string" }, metro: { type: "string" }, "time-zone": { type: "string" },
      area: { type: "string" }, points: { type: "string" }, out: { type: "string" }, only: { type: "string" }, help: { type: "boolean" },
    } });
  } catch (error) {
    throw new UsageError(error instanceof Error ? error.message : String(error));
  }
  const values = parsed.values;
  if (values.help === true) {
    console.log(USAGE);
    process.exit(0);
  }
  const url = required(values, "url");
  const tokenFile = required(values, "token-file");
  const metroId = required(values, "metro");
  if (!Id.safeParse(metroId).success) throw new UsageError("--metro is not a valid ID");
  const timeZone = requireTimeZone(required(values, "time-zone"));
  if ((values.area === undefined) === (values.points === undefined)) throw new UsageError("Give exactly one of --area and --points fixture");
  let points: readonly Point[];
  let pointsLabel: string;
  if (values.points !== undefined) {
    if (values.points !== "fixture") throw new UsageError("--points takes only the value fixture (infra/fixture-routing.mjs)");
    points = FIXTURE_POINTS;
    pointsLabel = "fixture";
  } else {
    const area = parseArea(required(values, "area"));
    points = areaPoints(area, 4);
    pointsLabel = `area ${area.south},${area.west},${area.north},${area.east}`;
  }
  let token: string;
  try {
    token = readFileSync(tokenFile, "utf8").trim();
  } catch (error) {
    throw new UsageError(`Cannot read --token-file: ${error instanceof Error ? error.message : String(error)}`);
  }
  const groups = new Set(CASES.map(item => item.group as string));
  const only = values.only === undefined ? null : new Set(values.only.split(",").map(group => group.trim()));
  for (const group of only ?? []) if (!groups.has(group)) throw new UsageError(`--only names an unknown group "${group}"; groups: ${[...groups].join(", ")}`);
  return { api: new SchedulerApi(url, token), metroId, timeZone, points, pointsLabel, out: resolve(values.out ?? "conformance-report"), only };
}

async function main(): Promise<number> {
  let settings;
  try {
    settings = options();
  } catch (error) {
    console.error(error instanceof Error ? error.message : String(error));
    console.error(USAGE);
    return 2;
  }
  const startedAt = new Date();
  const context: Context = { api: settings.api, metroId: settings.metroId, timeZone: settings.timeZone, points: settings.points,
    run: `conformance-${randomUUID().slice(0, 8)}`, today: todayIn(settings.timeZone, startedAt.getTime()), lastModified: startedAt.toISOString() };
  const selected: readonly Case[] = settings.only === null ? CASES : CASES.filter(item => settings.only?.has(item.group) === true);
  console.log(`Conformance run ${context.run} against ${settings.api.base}, metro ${context.metroId}, ${selected.length} cases`);
  const results: CaseResult[] = [];
  for (const item of selected) {
    const result = await runCase(item, context);
    results.push(result);
    console.log(`${result.status} ${item.group}: ${item.name} (${(result.ms / 1000).toFixed(1)} s)${result.failure === null ? "" : `\n     ${result.failure}`}`);
  }
  const failed = results.filter(result => result.status === "FAIL").length;
  const report: Report = { kit: KIT_VERSION, scheduler: settings.api.base, metroId: context.metroId, timeZone: context.timeZone,
    points: settings.pointsLabel, run: context.run, startedAt: startedAt.toISOString(), finishedAt: new Date().toISOString(),
    passed: results.length - failed, failed, results };
  mkdirSync(settings.out, { recursive: true });
  writeFileSync(join(settings.out, "conformance-report.json"), `${JSON.stringify(report, null, 2)}\n`);
  writeFileSync(join(settings.out, "conformance-report.md"), markdown(report));
  console.log(`${failed === 0 ? "PASS" : "FAIL"}: ${report.passed} passed, ${failed} failed. Report: ${settings.out}`);
  return failed === 0 ? 0 : 1;
}

main().then(code => { process.exitCode = code; }, (error: unknown) => {
  console.error(error);
  process.exitCode = 1;
});
