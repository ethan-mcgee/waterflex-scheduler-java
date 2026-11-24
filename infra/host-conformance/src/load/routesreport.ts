// The drive-time run's evidence: every call (so a summarizer can line calls up with the server's log by time) and
// per-operation summaries, as JSON, and a markdown view of the summaries.
import type { Placement, RouteMode, RouteRecord } from "./routes.ts";
import { percentile, summarize, type OperationSummary } from "./stats.ts";

export interface Spread { readonly p50: number; readonly p95: number; readonly p99: number; readonly max: number }

export interface RouteOperation extends OperationSummary {
  /** Time to the answer's status and headers, over answered calls. */
  readonly headersMs: Spread | null;
  readonly bytes: Spread | null;
  /** Paced moves only: how late each started against its tick. */
  readonly lagMs: Spread | null;
  /** Calls answered 200, per second of the run. */
  readonly okPerSecond: number;
}

export interface RouteReport {
  readonly label: string | null;
  readonly mode: RouteMode;
  readonly placement: Placement;
  readonly scheduler: string;
  readonly metroId: string;
  readonly day: { readonly source: string; readonly technicianDays: number; readonly appointments: number };
  readonly settings: { readonly callers: number; readonly paceMs: number | null; readonly durationS: number; readonly maxMoves: number | null;
    readonly maxOutstanding: number; readonly timeoutMs: number; readonly seed: number };
  readonly startedAt: string;
  readonly finishedAt: string;
  readonly machine: { readonly cpus: number; readonly cpuModel: string; readonly node: string };
  readonly moves: number;
  readonly skippedTicks: number;
  readonly noPlacement: number;
  readonly operations: readonly RouteOperation[];
  readonly contractBreaches: Readonly<Record<string, number>>;
  readonly anomalies: Readonly<Record<string, number>>;
  readonly records: readonly RouteRecord[];
}

export function spread(values: readonly number[]): Spread | null {
  if (values.length === 0) return null;
  const sorted = [...values].sort((a, b) => a - b);
  return { p50: percentile(sorted, 0.5), p95: percentile(sorted, 0.95), p99: percentile(sorted, 0.99), max: percentile(sorted, 1) };
}

export function routeOperations(records: readonly RouteRecord[], seconds: number): RouteOperation[] {
  if (!(seconds > 0)) throw new Error("A run lasts more than zero seconds");
  return summarize(records).map(summary => {
    const own = records.filter(record => record.operation === summary.operation);
    const answered = own.filter(record => record.status !== null);
    const values = (pick: (record: RouteRecord) => number | null) => answered.flatMap(record => { const value = pick(record); return value === null ? [] : [value]; });
    return { ...summary, headersMs: spread(values(record => record.headersMs)), bytes: spread(values(record => record.bytes)),
      lagMs: spread(own.flatMap(record => record.lagMs === null ? [] : [record.lagMs])),
      okPerSecond: Math.round((own.filter(record => record.status === 200 && record.error === null).length / seconds) * 100) / 100 };
  });
}

const entries = (record: Readonly<Record<string, number>>) => Object.entries(record).map(([key, value]) => `${key} ${value}`).join(", ") || "none";
const cell = (value: number | undefined) => value === undefined ? "n/a" : String(value);

export function routeMarkdown(report: RouteReport): string {
  const s = report.settings;
  const seconds = (Date.parse(report.finishedAt) - Date.parse(report.startedAt)) / 1000;
  const lines = [
    `# Drive-time run: ${report.mode}${report.label === null ? "" : `, ${report.label}`}`, "",
    `- ${s.callers} ${s.callers === 1 ? "dispatcher" : "dispatchers"}, ${s.paceMs === null ? "each moving again as soon as answered" : `each moving every ${s.paceMs} ms on a fixed clock`}; ` +
      `${report.moves} moves in ${seconds.toFixed(0)} s${s.maxMoves === null ? "" : ` (at most ${s.maxMoves})`}`,
    `- Day: ${report.day.source}, ${report.day.technicianDays} technician-days, ${report.day.appointments} appointments; jobs ${report.placement}`,
    `- Scheduler ${report.scheduler}, metro ${report.metroId}; client ${report.machine.cpus} CPUs (${report.machine.cpuModel}), Node ${report.machine.node}`,
    `- Ticks skipped with ${s.maxOutstanding} moves open: ${report.skippedTicks}; sessions with nowhere to drop: ${report.noPlacement}`, "",
    "The table shows each operation's caller-side time from send to the full answer, and its outcomes.", "",
    "| Operation | Calls | Statuses | OK per s | p50 (ms) | p95 (ms) | p99 (ms) | max (ms) |",
    "| --- | ---: | --- | ---: | ---: | ---: | ---: | ---: |",
    ...report.operations.map(op => `| ${op.operation} | ${op.calls} | ${entries(op.statuses)} | ${op.okPerSecond} | ${cell(op.ms?.p50)} | ${cell(op.ms?.p95)} | ${cell(op.ms?.p99)} | ${cell(op.ms?.max)} |`),
    "", "The same rows with time to headers, answer size and pacing lag:", "",
    "| Operation | Headers p50 (ms) | Headers p95 (ms) | Bytes p50 | Lag p95 (ms) |",
    "| --- | ---: | ---: | ---: | ---: |",
    ...report.operations.map(op => `| ${op.operation} | ${cell(op.headersMs?.p50)} | ${cell(op.headersMs?.p95)} | ${cell(op.bytes?.p50)} | ${op.lagMs === null ? "n/a" : op.lagMs.p95} |`),
  ];
  const reasons = report.operations.flatMap(op => Object.entries(op.reasons).map(([reason, n]) => `- ${op.operation}: ${reason} (${n})`));
  if (reasons.length > 0) lines.push("", "Error reasons:", "", ...reasons);
  if (Object.keys(report.contractBreaches).length > 0 || Object.keys(report.anomalies).length > 0)
    lines.push("", "## Defects found", "", `- Contract: ${entries(report.contractBreaches)}`, `- Answers not matching the day sent: ${entries(report.anomalies)}`);
  return `${lines.join("\n")}\n`;
}
