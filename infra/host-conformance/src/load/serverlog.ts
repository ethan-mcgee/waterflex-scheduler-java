// The scheduler's own account of where each call's time went, read from its request log. Every public API call logs
// one summary line (RequestLogging in the scheduler): the operation, status and elapsed time, and for a call that ran
// under a deadline the slot wait, routing wait, engine time, database time, the commit phase, the routing pairs with
// cache hits and fetched legs, and the thread's CPU time. This module parses those lines, in the plain console format
// or the ECS JSON the VM writes, and summarizes them per operation and status in the shape the October 14 performance
// studies used (`serverBreakdown` in docs/evidence/api-load-2026-10-14-*.json), so a run's caller-side latency can be
// read beside the server's attribution of it.
import { execFile } from "node:child_process";
import { promisify } from "node:util";
import { z } from "zod";
import * as Api from "../api.ts";
import type { SchedulerApi } from "../client.ts";
import { percentile } from "./stats.ts";

/** The attribution a call logs when it ran under a deadline. `commitMs` and `cpuMs` are null when the line has none. */
export interface Breakdown {
  readonly queueMs: number; readonly routingMs: number; readonly calculationMs: number; readonly dbMs: number; readonly dbCalls: number;
  readonly routingPairs: number; readonly cacheHits: number; readonly fetched: number;
  readonly commitMs: number | null; readonly cpuMs: number | null;
}

/** One request log line. `breakdown` is null for a call that ran under no deadline (a refused request, a replay). */
export interface ApiLine {
  readonly tenant: string;
  readonly requestId: string;
  readonly operation: string;
  readonly status: number;
  readonly elapsedMs: number;
  readonly breakdown: Breakdown | null;
}

const PLAIN = /\[tenant=(?<tenant>\S*) request=(?<request>\S*)\]/;
const MESSAGE = /api operation="(?<operation>[^"]*)" status=(?<status>\d+) elapsedMs=(?<elapsed>\d+)(?<rest>.*)$/;
const FIELD = /(\w+)=(\d+)/g;

function field(fields: Map<string, number>, name: string, where: string): number {
  const value = fields.get(name);
  if (value === undefined) throw new Error(`Request log line without ${name}: ${where}`);
  return value;
}

/** The breakdown after `elapsedMs=`, or null when the line carries none; a partial one is an error, never zeros. */
function breakdown(rest: string, where: string): Breakdown | null {
  const fields = new Map<string, number>();
  for (const match of rest.matchAll(FIELD)) {
    const name = match[1], value = match[2];
    if (name === undefined || value === undefined) throw new Error(`Unreadable request log field in ${where}`);
    if (!/^\d{1,15}$/.test(value)) throw new Error(`Unreadable request log number ${name}=${value} in ${where}`);
    fields.set(name, Number(value));
  }
  if (fields.size === 0) return null;
  const commit = fields.get("commitMs"), cpu = fields.get("cpuMs");
  return { queueMs: field(fields, "queueMs", where), routingMs: field(fields, "routingMs", where), calculationMs: field(fields, "calculationMs", where),
    dbMs: field(fields, "dbMs", where), dbCalls: field(fields, "dbCalls", where), routingPairs: field(fields, "routingPairs", where),
    cacheHits: field(fields, "cacheHits", where), fetched: field(fields, "fetched", where), commitMs: commit ?? null, cpuMs: cpu ?? null };
}

function text(value: unknown): string | null { return typeof value === "string" ? value : null; }

/**
 * Every request summary line in `log`, in the plain console format (`INFO [tenant=... request=...] ... : api
 * operation=...`) or ECS JSON (`{"message":"api operation=...","tenant":...,"requestId":...}`); other lines are skipped.
 * A summary line that cannot be read in full is an error: a run's attribution must never be partly invented.
 */
export function parseApiLines(log: string): ApiLine[] {
  const lines: ApiLine[] = [];
  for (const raw of log.split(/\r?\n/)) {
    const line = raw.trim();
    if (line === "") continue;
    let tenant: string | null, requestId: string | null, message: string;
    if (line.startsWith("{")) {
      let parsed: unknown;
      try { parsed = JSON.parse(line); } catch { continue; }
      if (typeof parsed !== "object" || parsed === null) continue;
      const record = parsed as Record<string, unknown>;
      const body = text(record.message);
      if (body === null || !body.startsWith("api operation=")) continue;
      // Spring's ECS layout writes each MDC entry as its own field, so the tenant and request ID sit beside the message.
      tenant = text(record.tenant) ?? ""; requestId = text(record.requestId) ?? ""; message = body;
    } else {
      const at = line.indexOf("api operation=");
      if (at < 0) continue;
      const context = PLAIN.exec(line);
      tenant = context?.groups?.tenant ?? null; requestId = context?.groups?.request ?? null; message = line.slice(at);
      if (tenant === null || requestId === null) throw new Error(`Request log line without its tenant and request context: ${line}`);
    }
    const match = MESSAGE.exec(message);
    const operation = match?.groups?.operation, status = match?.groups?.status, elapsed = match?.groups?.elapsed, rest = match?.groups?.rest;
    if (operation === undefined || status === undefined || elapsed === undefined || rest === undefined) throw new Error(`Unreadable request log line: ${line}`);
    lines.push({ tenant, requestId, operation, status: Number(status), elapsedMs: Number(elapsed), breakdown: breakdown(rest, line) });
  }
  return lines;
}

export interface Stat { readonly p50: number; readonly p95: number; readonly mean: number }

/** One operation and status: every call's elapsed time, and the attribution over the calls that logged one. */
export interface BreakdownSummary {
  readonly calls: number;
  readonly elapsedMs: Stat;
  /** Calls whose line carried a breakdown; the fields below are over these. */
  readonly attributed: number;
  readonly queueMs: Stat | null; readonly routingMs: Stat | null; readonly calculationMs: Stat | null; readonly dbMs: Stat | null; readonly dbCalls: Stat | null;
  readonly routingPairs: Stat | null; readonly cacheHits: Stat | null; readonly fetched: Stat | null;
  /** Over the attributed calls that logged a commit phase or CPU time; null when none did. */
  readonly commitMs: Stat | null; readonly cpuMs: Stat | null;
}

/** Keyed `"<operation> <status>"`, as the October 14 evidence is. */
export type ServerBreakdown = Record<string, BreakdownSummary>;

function stat(values: readonly number[]): Stat | null {
  if (values.length === 0) return null;
  const sorted = [...values].sort((a, b) => a - b);
  return { p50: percentile(sorted, 0.5), p95: percentile(sorted, 0.95), mean: Math.round(sorted.reduce((sum, value) => sum + value, 0) / sorted.length) };
}

export interface BreakdownFilter {
  /** Only this tenant's calls. */
  readonly tenant: string;
  /** Only these request IDs (a quality run's one variant), when given. */
  readonly requestIds?: ReadonlySet<string>;
}

/** The lines of one tenant (and request IDs, when given) summarized per operation and status. */
export function serverBreakdown(lines: readonly ApiLine[], filter: BreakdownFilter): ServerBreakdown {
  const own = lines.filter(line => line.tenant === filter.tenant && (filter.requestIds === undefined || filter.requestIds.has(line.requestId)));
  const keys = [...new Set(own.map(line => `${line.operation} ${line.status}`))].sort();
  const summary: ServerBreakdown = {};
  for (const key of keys) {
    const calls = own.filter(line => `${line.operation} ${line.status}` === key);
    const attributed = calls.flatMap(line => line.breakdown === null ? [] : [line.breakdown]);
    const elapsed = stat(calls.map(line => line.elapsedMs));
    if (elapsed === null) throw new Error(`No calls for ${key}`);
    const over = (pick: (item: Breakdown) => number | null) => stat(attributed.flatMap(item => { const value = pick(item); return value === null ? [] : [value]; }));
    summary[key] = { calls: calls.length, elapsedMs: elapsed, attributed: attributed.length,
      queueMs: over(item => item.queueMs), routingMs: over(item => item.routingMs), calculationMs: over(item => item.calculationMs),
      dbMs: over(item => item.dbMs), dbCalls: over(item => item.dbCalls), routingPairs: over(item => item.routingPairs),
      cacheHits: over(item => item.cacheHits), fetched: over(item => item.fetched), commitMs: over(item => item.commitMs), cpuMs: over(item => item.cpuMs) };
  }
  return summary;
}

/** The tenant the token names, as the request log names it; the log is filtered to it. */
export async function tenantOf(api: SchedulerApi): Promise<string> {
  const reply = await api.call("GET", "/api/v1/whoami", undefined, { timeoutMs: 10_000 });
  const parsed = Api.WhoAmI.safeParse(reply.json);
  if (reply.status !== 200 || !parsed.success) throw new Error(`whoami answered ${reply.status}: the request log cannot be filtered to this tenant`);
  return parsed.data.tenantId;
}

export interface ContainerLog { readonly container: string; readonly log: string }

/** The log of each named container since `since`; `docker logs` writes the application's stdout and stderr. */
export async function containerLogs(containers: readonly string[], since: Date): Promise<ContainerLog[]> {
  const run = promisify(execFile);
  const logs: ContainerLog[] = [];
  for (const name of containers) {
    const { stdout, stderr } = await run("docker", ["logs", "--since", since.toISOString(), name], { maxBuffer: 512 * 1024 * 1024 });
    logs.push({ container: name, log: `${stdout}\n${stderr}` });
  }
  return logs;
}

// A study's readings (SCHEDULER_STUDY_RECEIPTS=true on the daily pool): each daily calculation logs "Study receipt
// {json}" (StudyReceipts in the scheduler), and with TIMEFOLD_LOG_LEVEL=INFO each phase's solver logs "Solving ended:
// time spent (N), ... move evaluation speed (N/sec)". Both run on the calculation's thread, which carries the call's
// tenant and requestId, so a receipt takes the solver lines of its request logged before it.

const Unavailable = z.strictObject({ unavailable: z.enum(["INVALID_PLAN", "UNASSIGNED_VISITS"]) });
const Decimal = z.string().regex(/^\d+(\.\d+)?$/);
// The spread, absolute deviation and load balance come with every measured plan once the reference is measured (the
// harness's FairnessReadings); a receipt from an image before them carries the variance and highest utilization only.
const Measured = z.strictObject({ costCents: z.number().int().nonnegative(), overtimeMinutes: z.number().int().nonnegative(),
  variance: Decimal, maximumUtilization: Decimal, utilizationSpread: Decimal.optional(), absoluteDeviation: Decimal.optional(), loadBalance: Decimal.optional() });
// With SCHEDULER_STUDY_PROFILES on, each phase's flight recording (file name in the replica's profiles directory) and the
// thread the phases ran on, or why the recordings cannot stand for the phases.
const Profile = z.union([z.strictObject({ thread: z.string().min(1), recordings: z.record(z.string().min(1), z.string().min(1)) }),
  z.strictObject({ unavailable: z.string().min(1) })]);
export const StudyReceiptBody = z.strictObject({
  proposalId: z.string().min(1), serviceDate: z.string().regex(/^\d{4}-\d{2}-\d{2}$/), variant: z.string().min(1), seed: z.number().int(),
  technicians: z.number().int().nonnegative(), visits: z.number().int().nonnegative(),
  phases: z.array(z.strictObject({ name: z.string().min(1), budgetMs: z.number().int().nonnegative(), solveMs: z.number().int().nonnegative(), termination: z.string().min(1) })),
  fairness: z.string().min(1), accepted: z.boolean(), reason: z.string().min(1),
  reference: z.union([Measured, Unavailable]), costCeilingCents: z.number().int().nonnegative().optional(), proposed: z.union([Measured, Unavailable]),
  profile: Profile.optional(),
});
export type StudyReceiptBody = z.infer<typeof StudyReceiptBody>;

/** One solver's end line: Timefold's own time and move evaluation speed for one phase. */
export interface SolverSpeed { readonly timeSpentMs: number; readonly movesPerSecond: number }

/** A receipt with its request context and the solver lines of its request, in the order they ran. */
export interface StudyReceipt { readonly tenant: string; readonly requestId: string; readonly body: StudyReceiptBody; readonly solves: readonly SolverSpeed[] }

const SOLVING_ENDED = /^Solving ended: time spent \((?<time>\d+)\),.* move evaluation speed \((?<speed>\d+)\/sec\)/;
const RECEIPT = "Study receipt ";

/** The tenant, request ID and message of a plain or ECS line, or null for a line without them. */
function contextOf(line: string): { tenant: string; requestId: string; message: string } | null {
  if (line.startsWith("{")) {
    let parsed: unknown;
    try { parsed = JSON.parse(line); } catch { return null; }
    if (typeof parsed !== "object" || parsed === null) return null;
    const record = parsed as Record<string, unknown>;
    const message = text(record.message), tenant = text(record.tenant), requestId = text(record.requestId);
    return message === null || tenant === null || requestId === null ? null : { tenant, requestId, message };
  }
  const context = PLAIN.exec(line);
  const at = line.indexOf(" : ");
  const tenant = context?.groups?.tenant, requestId = context?.groups?.request;
  return tenant === undefined || requestId === undefined || at < 0 ? null : { tenant, requestId, message: line.slice(at + 3) };
}

/**
 * Every study receipt in `log`, each with the solver lines its request logged since its previous receipt. A receipt
 * line that does not read in full, or a receipt without its request context, is an error: a study's readings are
 * never partly invented. Solver lines with no receipt after them (receipts off) are left out.
 */
export function parseStudyReceipts(log: string): StudyReceipt[] {
  const receipts: StudyReceipt[] = [];
  const pending = new Map<string, SolverSpeed[]>();
  for (const raw of log.split(/\r?\n/)) {
    const line = raw.trim();
    if (line === "" || (!line.includes(RECEIPT) && !line.includes("Solving ended: "))) continue;
    const context = contextOf(line);
    if (context === null) {
      if (line.includes(`${RECEIPT}{`)) throw new Error(`Study receipt without its tenant and request context: ${line}`);
      continue;
    }
    const key = `${context.tenant}\n${context.requestId}`;
    const ended = SOLVING_ENDED.exec(context.message);
    if (ended !== null) {
      const time = ended.groups?.time, speed = ended.groups?.speed;
      if (time === undefined || speed === undefined) throw new Error(`Unreadable solver end line: ${line}`);
      const list = pending.get(key) ?? [];
      list.push({ timeSpentMs: Number(time), movesPerSecond: Number(speed) });
      pending.set(key, list);
      continue;
    }
    if (!context.message.startsWith(RECEIPT)) continue;
    let json: unknown;
    try { json = JSON.parse(context.message.slice(RECEIPT.length)); } catch { throw new Error(`Unreadable study receipt: ${line}`); }
    const body = StudyReceiptBody.safeParse(json);
    if (!body.success) throw new Error(`Study receipt out of contract (${body.error.message}): ${line}`);
    receipts.push({ tenant: context.tenant, requestId: context.requestId, body: body.data, solves: pending.get(key) ?? [] });
    pending.delete(key);
  }
  return receipts;
}

export interface StudyPhase { readonly phase: string; readonly runs: number; readonly solveMs: Stat; readonly movesPerSecond: Stat | null; readonly terminations: Record<string, number> }

export interface StudySummary {
  readonly receipts: number;
  readonly decisions: Record<string, number>;
  readonly phases: readonly StudyPhase[];
  /** Receipts whose solver lines did not number its phases (Timefold at WARN, or an extra solve), so no phase took their speeds. */
  readonly unpairedSpeeds: number;
  /** Receipts whose every phase has a flight recording (SCHEDULER_STUDY_PROFILES on). */
  readonly profiled: number;
  /** Medians over the receipts whose plan was measured; null when none was. */
  readonly referenceCostCents: number | null; readonly proposedCostCents: number | null;
  readonly referenceVariance: string | null; readonly proposedVariance: string | null;
  readonly referenceMaximumUtilization: string | null; readonly proposedMaximumUtilization: string | null;
  readonly unmeasured: Record<string, number>;
}

/** The lower middle of decimal strings, compared exactly as scaled integers (the DECIMAL128 readings), never rounded. */
export function medianDecimal(values: readonly string[]): string | null {
  if (values.length === 0) return null;
  const scale = Math.max(...values.map(value => value.split(".")[1]?.length ?? 0));
  const scaled = (value: string) => { const [whole = "0", part = ""] = value.split("."); return BigInt(`${whole}${part.padEnd(scale, "0")}`); };
  const sorted = [...values].sort((a, b) => { const x = scaled(a), y = scaled(b); return x < y ? -1 : x > y ? 1 : 0; });
  return sorted[Math.floor((sorted.length - 1) / 2)] ?? null;
}

/** One tenant's receipts summarized: decisions, each phase's time and speed, and the plans' readings. */
export function studySummary(all: readonly StudyReceipt[], tenant: string): StudySummary {
  const receipts = all.filter(receipt => receipt.tenant === tenant);
  const decisions: Record<string, number> = {}, unmeasured: Record<string, number> = {};
  const byPhase = new Map<string, { solve: number[]; speed: number[]; terminations: Record<string, number> }>();
  let unpairedSpeeds = 0, profiled = 0;
  const cost = { reference: [] as number[], proposed: [] as number[] };
  const variance = { reference: [] as string[], proposed: [] as string[] }, maximum = { reference: [] as string[], proposed: [] as string[] };
  for (const { body, solves } of receipts) {
    const decision = body.accepted ? "ACCEPTED" : body.reason;
    decisions[decision] = (decisions[decision] ?? 0) + 1;
    const paired = solves.length === body.phases.length;
    if (!paired) unpairedSpeeds++;
    const profile = body.profile;
    if (profile !== undefined && "recordings" in profile && body.phases.every(phase => phase.name in profile.recordings)) profiled++;
    body.phases.forEach((phase, index) => {
      const entry = byPhase.get(phase.name) ?? { solve: [], speed: [], terminations: {} };
      entry.solve.push(phase.solveMs);
      const speed = paired ? solves[index] : undefined;
      if (speed !== undefined) entry.speed.push(speed.movesPerSecond);
      entry.terminations[phase.termination] = (entry.terminations[phase.termination] ?? 0) + 1;
      byPhase.set(phase.name, entry);
    });
    for (const side of ["reference", "proposed"] as const) {
      const plan = body[side];
      if ("unavailable" in plan) { const name = `${side} ${plan.unavailable}`; unmeasured[name] = (unmeasured[name] ?? 0) + 1; continue; }
      cost[side].push(plan.costCents); variance[side].push(plan.variance); maximum[side].push(plan.maximumUtilization);
    }
  }
  const phases = [...byPhase.entries()].map(([phase, entry]) => {
    const solveMs = stat(entry.solve);
    if (solveMs === null) throw new Error(`No runs of phase ${phase}`);
    return { phase, runs: entry.solve.length, solveMs, movesPerSecond: stat(entry.speed), terminations: entry.terminations };
  });
  const middle = (values: number[]) => stat(values)?.p50 ?? null;
  return { receipts: receipts.length, decisions, phases, unpairedSpeeds, profiled,
    referenceCostCents: middle(cost.reference), proposedCostCents: middle(cost.proposed),
    referenceVariance: medianDecimal(variance.reference), proposedVariance: medianDecimal(variance.proposed),
    referenceMaximumUtilization: medianDecimal(maximum.reference), proposedMaximumUtilization: medianDecimal(maximum.proposed), unmeasured };
}

const tally = (record: Record<string, number>) => Object.entries(record).map(([key, value]) => `${key} ${value}`).join(", ") || "none";

export function studyLines(summary: StudySummary): string[] {
  const or = (value: number | string | null) => value === null ? "-" : String(value);
  const pair = (value: Stat | null) => value === null ? "-" : `${value.p50} / ${value.p95}`;
  return ["## Study receipts (daily replica)", "",
    `- Receipts ${summary.receipts}; decisions: ${tally(summary.decisions)}${summary.unpairedSpeeds === 0 ? "" : `; ${summary.unpairedSpeeds} without per-phase speeds`}${summary.profiled === 0 ? "" : `; ${summary.profiled} with every phase flight-recorded`}`,
    `- Cost p50: reference ${or(summary.referenceCostCents)} c, proposed ${or(summary.proposedCostCents)} c`,
    `- Variance p50: reference ${or(summary.referenceVariance)}, proposed ${or(summary.proposedVariance)}`,
    `- Highest utilization p50: reference ${or(summary.referenceMaximumUtilization)}, proposed ${or(summary.proposedMaximumUtilization)}`,
    ...(Object.keys(summary.unmeasured).length === 0 ? [] : [`- Not measured: ${tally(summary.unmeasured)}`]), "",
    "| Phase | Runs | Solve ms p50 / p95 | Moves per second p50 / p95 | Terminations |", "| --- | ---: | --- | --- | --- |",
    ...summary.phases.map(p => `| ${p.phase} | ${p.runs} | ${pair(p.solveMs)} | ${pair(p.movesPerSecond)} | ${tally(p.terminations)} |`)];
}

/** How many of a container's request lines were the tenant's: behind a proxy, which replicas served the run. */
export interface ContainerShare { readonly container: string; readonly apiLines: number }

export interface RequestLog { readonly lines: readonly ApiLine[]; readonly containers: readonly ContainerShare[]; readonly receipts: readonly StudyReceipt[] }

/** Every container's request lines pooled, with each container's share of the tenant's. */
export function requestLog(logs: readonly ContainerLog[], tenant: string): RequestLog {
  const lines: ApiLine[] = [];
  const containers: ContainerShare[] = [];
  const receipts: StudyReceipt[] = [];
  for (const { container, log } of logs) {
    const own = parseApiLines(log);
    lines.push(...own);
    containers.push({ container, apiLines: own.filter(line => line.tenant === tenant).length });
    receipts.push(...parseStudyReceipts(log));
  }
  return { lines, containers, receipts };
}

/** The named containers' request log since `since`, read with `docker logs`. */
export async function readRequestLog(containers: readonly string[], since: Date, tenant: string): Promise<RequestLog> {
  return requestLog(await containerLogs(containers, since), tenant);
}

export const containerShareLines = (shares: readonly ContainerShare[]): string[] =>
  ["## Request lines by scheduler container", "", "| Container | This tenant's request lines |", "| --- | ---: |",
    ...shares.map(share => `| ${share.container} | ${share.apiLines} |`)];

const ms = (value: Stat | null) => value === null ? "-" : `${value.p50} / ${value.p95}`;

/** The October 14 table: each call's server-side p50 / p95 and where the p50 went. */
export function breakdownLines(breakdown: ServerBreakdown, heading = "## Server time by call (request log)"): string[] {
  const rows = Object.entries(breakdown).map(([key, s]) =>
    `| ${key} | ${s.calls} | ${ms(s.elapsedMs)} | ${s.attributed} | ${ms(s.queueMs)} | ${ms(s.routingMs)} | ${ms(s.calculationMs)} | ${ms(s.dbMs)} | ${ms(s.commitMs)} | ` +
    `${s.routingPairs === null || s.fetched === null ? "-" : `${s.routingPairs.p50} / ${s.fetched.p50}`} | ${s.cpuMs === null ? "-" : s.cpuMs.p50} |`);
  return [heading, "",
    "| Call | Calls | Server p50 / p95 ms | Attributed | Slot wait p50 / p95 | Routing | Engine | Database | Commit | Pairs needed / fetched p50 | CPU ms p50 |",
    "| --- | ---: | --- | ---: | --- | --- | --- | --- | --- | --- | ---: |", ...rows];
}
