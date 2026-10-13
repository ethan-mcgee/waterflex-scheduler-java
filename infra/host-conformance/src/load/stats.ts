// Latency and outcome summaries for one operation across a load run.

export interface CallRecord {
  readonly operation: string;
  /** The final HTTP status after any BUSY retries; null when the call failed without an answer (timeout, refused). */
  readonly status: number | null;
  /** The Problem's error code, or the transport failure's name; null for a success. */
  readonly error: string | null;
  /** The Problem's message for an error answer, for the report's reasons; null otherwise. */
  readonly message: string | null;
  /** Caller-side milliseconds from the first attempt to the final answer, BUSY waits included. */
  readonly ms: number;
  readonly attempts: number;
}

export interface OperationSummary {
  readonly operation: string;
  readonly calls: number;
  readonly statuses: Record<string, number>;
  readonly errors: Record<string, number>;
  /** The most frequent error messages, "CODE: message", with their counts. */
  readonly reasons: Record<string, number>;
  /** Calls that waited for at least one 429 BUSY before their final answer. */
  readonly retriedBusy: number;
  readonly ms: { readonly p50: number; readonly p95: number; readonly p99: number; readonly max: number; readonly mean: number } | null;
}

/** The nearest-rank percentile of sorted values; `fraction` in (0, 1]. */
export function percentile(sorted: readonly number[], fraction: number): number {
  if (sorted.length === 0) throw new Error("No values for a percentile");
  if (!(fraction > 0 && fraction <= 1)) throw new Error(`Percentile fraction out of range: ${fraction}`);
  const value = sorted[Math.ceil(fraction * sorted.length) - 1];
  if (value === undefined) throw new Error("Percentile index out of range");
  return value;
}

function count(values: readonly string[]): Record<string, number> {
  const counted: Record<string, number> = {};
  for (const value of values) counted[value] = (counted[value] ?? 0) + 1;
  return counted;
}

export function summarize(records: readonly CallRecord[]): OperationSummary[] {
  const operations = [...new Set(records.map(record => record.operation))].sort();
  return operations.map(operation => {
    const own = records.filter(record => record.operation === operation);
    const sorted = own.map(record => record.ms).sort((a, b) => a - b);
    return {
      operation, calls: own.length,
      statuses: count(own.map(record => record.status === null ? "none" : String(record.status))),
      errors: count(own.flatMap(record => record.error === null ? [] : [record.error])),
      reasons: Object.fromEntries(Object.entries(count(own.flatMap(record => record.error === null || record.message === null ? [] : [`${record.error}: ${record.message}`])))
        .sort(([, a], [, b]) => b - a).slice(0, 5)),
      retriedBusy: own.filter(record => record.attempts > 1).length,
      ms: sorted.length === 0 ? null : { p50: percentile(sorted, 0.5), p95: percentile(sorted, 0.95), p99: percentile(sorted, 0.99),
        max: percentile(sorted, 1), mean: Math.round(sorted.reduce((sum, value) => sum + value, 0) / sorted.length) },
    };
  });
}
