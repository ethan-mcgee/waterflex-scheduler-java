// The scheduler's own view of a run, from its Prometheus endpoint on the private management port: per-operation
// server time, admission rejections, routing work, and the JVM's CPU and heap sampled through the run.

/** One sample line: metric name, its labels and value. */
export interface Sample { readonly name: string; readonly labels: Readonly<Record<string, string>>; readonly value: number }

const LINE = /^([a-zA-Z_:][a-zA-Z0-9_:]*)(?:\{(.*)\})?\s+(\S+)(?:\s+\d+)?$/;
const LABEL = /([a-zA-Z_][a-zA-Z0-9_]*)="((?:[^"\\]|\\.)*)"/g;

/** Parses the Prometheus text format; comments are skipped and a line it cannot read fails the parse. */
export function parsePrometheus(text: string): Sample[] {
  const samples: Sample[] = [];
  for (const raw of text.split("\n")) {
    const line = raw.trim();
    if (line === "" || line.startsWith("#")) continue;
    const match = LINE.exec(line);
    if (match === null || match[1] === undefined || match[3] === undefined) throw new Error(`Unreadable metrics line: ${line.slice(0, 120)}`);
    const value = Number(match[3]);
    if (Number.isNaN(value) && match[3] !== "NaN") throw new Error(`Unreadable metrics value: ${line.slice(0, 120)}`);
    const labels: Record<string, string> = {};
    for (const label of (match[2] ?? "").matchAll(LABEL)) if (label[1] !== undefined && label[2] !== undefined) labels[label[1]] = label[2];
    samples.push({ name: match[1], labels, value });
  }
  return samples;
}

/** Sum of a metric's samples matching every given label. */
export function total(samples: readonly Sample[], name: string, match: Readonly<Record<string, string>> = {}): number {
  return samples.filter(sample => sample.name === name && Object.entries(match).every(([k, v]) => sample.labels[k] === v))
    .reduce((sum, sample) => sum + sample.value, 0);
}

export interface ServerOperation { readonly uri: string; readonly method: string; readonly status: string; readonly calls: number; readonly meanMs: number }

export interface ServerView {
  readonly operations: readonly ServerOperation[];
  readonly admissionRejections: number;
  readonly routingPairs: number;
  readonly routingLegRequests: number;
  readonly routingIdentityRequests: number;
  /** The scheduler process's CPU share (0 to 1 of the machine) sampled through the run; null when never sampled. */
  readonly processCpu: { readonly mean: number; readonly max: number } | null;
  readonly heapUsedMaxBytes: number | null;
}

/** The difference between two scrapes for the counters, and the samples taken in between for the gauges. */
export function serverView(before: readonly Sample[], after: readonly Sample[], during: readonly (readonly Sample[])[]): ServerView {
  const operations: ServerOperation[] = [];
  for (const sample of after) {
    if (sample.name !== "http_server_requests_seconds_count" || !(sample.labels.uri ?? "").startsWith("/api/v1")) continue;
    const match = { uri: sample.labels.uri ?? "", method: sample.labels.method ?? "", status: sample.labels.status ?? "", outcome: sample.labels.outcome ?? "" };
    const calls = sample.value - total(before, "http_server_requests_seconds_count", match);
    if (calls <= 0) continue;
    const seconds = total(after, "http_server_requests_seconds_sum", match) - total(before, "http_server_requests_seconds_sum", match);
    operations.push({ uri: match.uri, method: match.method, status: match.status, calls, meanMs: Math.round((seconds / calls) * 1000) });
  }
  operations.sort((a, b) => `${a.uri} ${a.method} ${a.status}`.localeCompare(`${b.uri} ${b.method} ${b.status}`));
  const delta = (name: string) => total(after, name) - total(before, name);
  const cpu = during.map(samples => samples.find(sample => sample.name === "process_cpu_usage")?.value).filter((value): value is number => value !== undefined);
  const heap = during.map(samples => total(samples, "jvm_memory_used_bytes", { area: "heap" })).filter(value => value > 0);
  return {
    operations,
    admissionRejections: delta("scheduler_admission_rejections_total"),
    routingPairs: delta("scheduler_routing_pairs_total"),
    routingLegRequests: delta("scheduler_routing_leg_requests_total"),
    routingIdentityRequests: delta("scheduler_routing_identity_requests_total"),
    processCpu: cpu.length === 0 ? null : { mean: cpu.reduce((sum, value) => sum + value, 0) / cpu.length, max: Math.max(...cpu) },
    heapUsedMaxBytes: heap.length === 0 ? null : Math.max(...heap),
  };
}

export async function scrape(url: string): Promise<Sample[]> {
  const response = await fetch(url, { signal: AbortSignal.timeout(5000) });
  if (!response.ok) throw new Error(`Metrics scrape answered HTTP ${response.status}`);
  return parsePrometheus(await response.text());
}
