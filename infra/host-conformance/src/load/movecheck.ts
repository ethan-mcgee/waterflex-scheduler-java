// The move check timing study's analysis: each drag the timing spec recorded (web/timing/moveCheck.timing.ts) joined
// with the scheduler's request log line for its route evaluation, checked, and summarized per case. Nothing measured is
// adjusted: a drag that fails a check is listed as invalid and left out of the statistics, and a value the run did not
// measure is reported as such, never as zero.
import { z } from "zod";
import { parseApiLines, type ApiLine } from "./serverlog.ts";
import { percentile } from "./stats.ts";

export const EVALUATE = "POST /api/v1/routes/evaluate";

const phases = z.record(z.string(), z.number());
export const dragRow = z.object({
  seq: z.int().min(0), case: z.string().regex(/^(first|warmup|warm|cold|concurrency-\d+)$/), delayMs: z.int().min(0), rep: z.int().min(0),
  discarded: z.boolean(), date: z.iso.date(), target: z.enum(["roomy", "full"]), expected: z.enum(["FEASIBLE", "NOT_FEASIBLE"]), outcome: z.string(),
  startedAt: z.iso.datetime(), endedAt: z.iso.datetime(), browserMs: z.number().min(0),
  resource: z.object({ fetchStart: z.number(), requestStart: z.number(), responseStart: z.number(), responseEnd: z.number(), duration: z.number().min(0) }).nullable(),
  portal: phases,
}).strict();
export type DragRow = z.infer<typeof dragRow>;

export function parseRows(jsonl: string): DragRow[] {
  return jsonl.split(/\r?\n/).filter(line => line.trim() !== "").map((line, index) => {
    const parsed = dragRow.safeParse(JSON.parse(line));
    if (!parsed.success) throw new Error(`runs.jsonl line ${index + 1}: ${parsed.error.message}`);
    return parsed.data;
  }).sort((a, b) => a.seq - b.seq);
}

/** A scheduler log line with the time docker stamped on it. */
export interface StampedLine extends ApiLine { readonly at: string }

/** The tenant's route evaluation lines from `docker logs --timestamps` output, in time order. */
export function evaluateLines(dockerLog: string, tenant: string): StampedLine[] {
  const lines: StampedLine[] = [];
  for (const raw of dockerLog.split(/\r?\n/)) {
    const match = /^(\d{4}-\d{2}-\d{2}T\S+Z)\s(.*)$/.exec(raw.trim());
    if (match === null) continue;
    const at = match[1], body = match[2];
    if (at === undefined || body === undefined) continue;
    for (const line of parseApiLines(body)) if (line.tenant === tenant && line.operation === EVALUATE) lines.push({ ...line, at });
  }
  return lines.sort((a, b) => a.at.localeCompare(b.at));
}

/** Every step of one drag, in milliseconds; null where the run did not measure it. */
export interface Steps {
  readonly dropToPainted: number; readonly handler: number | null; readonly toPortal: number | null; readonly render: number | null;
  readonly portalTotal: number | null; readonly scope: number | null; readonly facts: number | null; readonly assemble: number | null;
  readonly schedulerCall: number | null; readonly proxy: number | null;
  readonly schedulerTotal: number | null; readonly queue: number | null; readonly routing: number | null; readonly calculation: number | null;
  readonly database: number | null; readonly schedulerOther: number | null; readonly routingPairs: number | null; readonly fetched: number | null;
}

export interface Joined { readonly row: DragRow; readonly line: StampedLine | null; readonly steps: Steps; readonly invalid: string | null }

const sequential = (row: DragRow) => !row.case.startsWith("concurrency-");

/**
 * Each drag with its scheduler line and steps. One drag at a time on its own tenant makes the k-th evaluate line the
 * k-th drag's, so the drags are paired in order only when the counts agree; concurrent drags are never paired.
 */
export function join(rows: readonly DragRow[], lines: readonly StampedLine[]): { joined: Joined[]; paired: boolean; reason: string | null } {
  const ordered = rows.filter(sequential);
  const paired = lines.length === rows.length;
  const reason = paired ? null : `${lines.length} route evaluation lines for ${rows.length} drags`;
  const joined = rows.map(row => {
    const index = ordered.indexOf(row);
    const line = paired && index >= 0 ? lines[index] ?? null : null;
    return { row, line, ...stepsOf(row, line) };
  });
  return { joined, paired, reason };
}

function stepsOf(row: DragRow, line: StampedLine | null): { steps: Steps; invalid: string | null } {
  const portal = (name: string) => row.portal[name] ?? null;
  const total = portal("total"), call = portal("scheduler");
  const resource = row.resource;
  const breakdown = line?.breakdown ?? null;
  const steps: Steps = {
    dropToPainted: row.browserMs,
    handler: resource?.fetchStart ?? null,
    toPortal: resource === null || total === null ? null : resource.duration - total,
    render: resource === null ? null : row.browserMs - resource.responseEnd,
    portalTotal: total, scope: portal("scope"), facts: portal("facts"), assemble: portal("assemble"), schedulerCall: call,
    proxy: call === null || line === null ? null : call - line.elapsedMs,
    schedulerTotal: line?.elapsedMs ?? null, queue: breakdown?.queueMs ?? null, routing: breakdown?.routingMs ?? null,
    calculation: breakdown?.calculationMs ?? null, database: breakdown?.dbMs ?? null,
    schedulerOther: line === null || breakdown === null ? null : line.elapsedMs - breakdown.queueMs - breakdown.routingMs - breakdown.calculationMs,
    routingPairs: breakdown?.routingPairs ?? null, fetched: breakdown?.fetched ?? null,
  };
  return { steps, invalid: invalidity(row, line, steps) };
}

function invalidity(row: DragRow, line: StampedLine | null, steps: Steps): string | null {
  if (row.outcome !== row.expected) return `answered ${row.outcome}, expected ${row.expected}`;
  if (row.resource === null) return "no Resource Timing entry for the request";
  if (steps.portalTotal === null || steps.schedulerCall === null) return "no portal Server-Timing phases";
  if (!sequential(row)) return null;
  if (line === null) return null;
  if (line.status !== 200) return `the scheduler answered ${line.status}`;
  if (line.breakdown === null) return "the scheduler line has no breakdown";
  // The portal times its call in fractions of a millisecond and the scheduler logs whole ones, so allow one.
  if (line.elapsedMs > steps.schedulerCall + 1) return `the scheduler took ${line.elapsedMs} ms of a ${steps.schedulerCall.toFixed(1)} ms call`;
  if (row.case === "cold" && line.breakdown.fetched === 0) return "a cold drag fetched no drive times";
  if (row.case === "warm" && line.breakdown.fetched > 0) return `a warm drag fetched ${line.breakdown.fetched} drive times`;
  return null;
}

export interface Stat { readonly n: number; readonly median: number; readonly p95: number; readonly min: number; readonly max: number }

function stat(values: readonly number[]): Stat | null {
  if (values.length === 0) return null;
  const sorted = [...values].sort((a, b) => a - b);
  const min = sorted[0], max = sorted.at(-1);
  if (min === undefined || max === undefined) throw new Error("No values for a statistic");
  return { n: sorted.length, median: percentile(sorted, 0.5), p95: percentile(sorted, 0.95), min, max };
}

export const STEP_NAMES = ["dropToPainted", "handler", "toPortal", "render", "portalTotal", "scope", "facts", "assemble", "schedulerCall", "proxy",
  "schedulerTotal", "queue", "routing", "calculation", "database", "schedulerOther", "routingPairs", "fetched"] as const;
export type StepName = typeof STEP_NAMES[number];

/** One value per step, made by `make`, typed without a cast. */
function byStep<T>(make: (name: StepName) => T): Record<StepName, T> {
  return { dropToPainted: make("dropToPainted"), handler: make("handler"), toPortal: make("toPortal"), render: make("render"),
    portalTotal: make("portalTotal"), scope: make("scope"), facts: make("facts"), assemble: make("assemble"), schedulerCall: make("schedulerCall"),
    proxy: make("proxy"), schedulerTotal: make("schedulerTotal"), queue: make("queue"), routing: make("routing"), calculation: make("calculation"),
    database: make("database"), schedulerOther: make("schedulerOther"), routingPairs: make("routingPairs"), fetched: make("fetched") };
}

export interface Group {
  readonly key: string; readonly label: string; readonly drags: number; readonly invalid: number;
  readonly stats: Record<StepName, Stat | null>;
}

function label(row: DragRow): string {
  const outcome = row.expected === "FEASIBLE" ? "feasible" : "not feasible";
  if (row.case === "first") return "First call after start";
  if (row.case === "warm") return `Warm, ${outcome}, +${row.delayMs} ms`;
  if (row.case === "cold") return `Cold, ${outcome}`;
  return `${row.case.replace("concurrency-", "")} at once, ${outcome}`;
}

/** The valid drags of each case, outcome and delay, in the order the run made them; discarded warm-up drags are left out. */
export function groups(joined: readonly Joined[]): Group[] {
  const keys: string[] = [];
  const members = new Map<string, Joined[]>();
  for (const item of joined) {
    if (item.row.discarded) continue;
    const key = `${item.row.case}|${item.row.expected}|${item.row.delayMs}`;
    if (!members.has(key)) { keys.push(key); members.set(key, []); }
    members.get(key)?.push(item);
  }
  return keys.map(key => {
    const items = members.get(key) ?? [];
    const valid = items.filter(item => item.invalid === null);
    const first = items[0];
    if (first === undefined) throw new Error(`Empty group ${key}`);
    const stats = byStep(name => stat(valid.flatMap(item => { const value = item.steps[name]; return value === null ? [] : [value]; })));
    return { key, label: label(first.row), drags: items.length, invalid: items.length - valid.length, stats };
  });
}

/** An aligned Markdown table: the first column left-aligned, the others right-aligned, every cell padded to its column. */
export function table(header: readonly string[], rows: ReadonlyArray<readonly string[]>): string {
  const widths = header.map((cell, column) => Math.max(3, cell.length, ...rows.map(row => (row[column] ?? "").length)));
  const pad = (cells: readonly string[]) => `| ${cells.map((cell, column) => column === 0 ? cell.padEnd(widths[column] ?? 0) : cell.padStart(widths[column] ?? 0)).join(" | ")} |`;
  const rule = `| ${widths.map((width, column) => column === 0 ? "-".repeat(width) : `${"-".repeat(width - 1)}:`).join(" | ")} |`;
  return [pad(header), rule, ...rows.map(pad)].join("\n");
}

const NOT_MEASURED = "not measured";
const shown = (value: Stat | null, pick: "median" | "p95", digits: number) => value === null ? NOT_MEASURED : value[pick].toFixed(digits);

/** The three step tables for one statistic. Their rows repeat the first column, as the repository's tables do. */
export function stepTables(list: readonly Group[], pick: "median" | "p95"): string {
  const row = (group: Group, names: readonly StepName[], digits: number) => [group.label, ...names.map(name => shown(group.stats[name], pick, digits))];
  const browser = ["dropToPainted", "handler", "toPortal", "portalTotal", "render"] as const;
  const portal = ["scope", "facts", "assemble", "schedulerCall", "proxy"] as const;
  const scheduler = ["schedulerTotal", "queue", "routing", "calculation", "database", "schedulerOther"] as const;
  return [
    table(["Case", "Drags", "Drop to painted (ms)", "Browser handler (ms)", "To portal and back (ms)", "Portal (ms)", "Render (ms)"],
      list.map(group => { const cells = row(group, browser, 1); return [cells[0] ?? "", String(group.drags - group.invalid), ...cells.slice(1)]; })),
    "The same rows with the portal's steps:",
    table(["Case", "Scope (ms)", "Load facts (ms)", "Assemble (ms)", "Scheduler call (ms)", "Proxy and transport (ms)"], list.map(group => row(group, portal, 1))),
    "The same rows with the scheduler's steps:",
    table(["Case", "Scheduler (ms)", "Slot wait (ms)", "Drive times (ms)", "Evaluation (ms)", "Database (ms)", "Other (ms)"], list.map(group => row(group, scheduler, 0))),
  ].join("\n\n");
}
