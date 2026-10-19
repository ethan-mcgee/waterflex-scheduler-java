// The scheduler's own account of where each call's time went, read from its request log. Every public API call logs
// one summary line (RequestLogging in the scheduler): the operation, status and elapsed time, and for a call that ran
// under a deadline the slot wait, routing wait, engine time, database time, the commit phase, the routing pairs with
// cache hits and fetched legs, and the thread's CPU time. This module parses those lines, in the plain console format
// or the ECS JSON the VM writes, and summarizes them per operation and status in the shape the October 14 performance
// studies used (`serverBreakdown` in docs/evidence/api-load-2026-10-14-*.json), so a run's caller-side latency can be
// read beside the server's attribution of it.
import { execFile } from "node:child_process";
import { promisify } from "node:util";
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

/** How many of a container's request lines were the tenant's: behind a proxy, which replicas served the run. */
export interface ContainerShare { readonly container: string; readonly apiLines: number }

export interface RequestLog { readonly lines: readonly ApiLine[]; readonly containers: readonly ContainerShare[] }

/** Every container's request lines pooled, with each container's share of the tenant's. */
export function requestLog(logs: readonly ContainerLog[], tenant: string): RequestLog {
  const lines: ApiLine[] = [];
  const containers: ContainerShare[] = [];
  for (const { container, log } of logs) {
    const own = parseApiLines(log);
    lines.push(...own);
    containers.push({ container, apiLines: own.filter(line => line.tenant === tenant).length });
  }
  return { lines, containers };
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
