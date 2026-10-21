// The load run's evidence: JSON for comparison across runs, markdown for docs/api-load.md and reviews.
import type { Snapshot } from "../snapshot.ts";
import type { ServerView } from "./metrics.ts";
import { breakdownLines, containerShareLines, studyLines, type ContainerShare, type ServerBreakdown, type StudyReceipt, type StudySummary } from "./serverlog.ts";
import type { BookingTally, ContainerUsage, DailyTally } from "./scenarios.ts";
import { percentile, type OperationSummary } from "./stats.ts";

export interface LoadReport {
  readonly label: string | null;
  readonly scenario: "booking" | "daily" | "mixed";
  readonly scheduler: string;
  readonly metroId: string;
  readonly timeZone: string;
  readonly points: string;
  readonly run: string;
  readonly startedAt: string;
  readonly finishedAt: string;
  readonly settings: {
    readonly durationS: number; readonly concurrency: number; readonly dailyConcurrency: number | null; readonly technicians: number;
    readonly appointmentsPerDay: number; readonly horizonDates: number; readonly dailyTechnicians: number; readonly dailyMode: "sync" | "async" | null; readonly jobs: "cold" | "warm";
    /** The proposal count the daily planners stopped at (--daily-proposals); null when they ran for the duration. */
    readonly dailyProposals: number | null;
    /** Each desk searched its own run of dates instead of the same ones. */
    readonly dateSpread: boolean;
    /**
     * How each search's dates were chosen. `weekdays` is the fixed length or the dynamic cap, null for --horizon-days;
     * `horizonDates` above is the first search's count. Booked weekdays started with `bookedAppointments` visits per day.
     */
    readonly horizon: { readonly mode: "fixed" | "dynamic"; readonly weekdays: number | null; readonly leadWeekdays: number | null;
      readonly fullPercent: number | null; readonly bookedWeekdays: number; readonly bookedAppointments: number | null };
    readonly confirmRate: number; readonly offerLimit: number; readonly windowHours: number; readonly policy: Snapshot["policy"]; readonly seed: number;
  };
  readonly machine: { readonly cpus: number; readonly cpuModel: string; readonly memoryBytes: number; readonly node: string };
  readonly operations: readonly OperationSummary[];
  readonly booking: BookingTally | null;
  readonly daily: DailyTally | null;
  readonly server: ServerView | null;
  readonly metricFailures: number;
  readonly containers: readonly ContainerUsage[] | null;
  /** The scheduler's own attribution of this tenant's calls, from its request log (--scheduler-log); null when not read. */
  readonly serverBreakdown: ServerBreakdown | null;
  /** Each scheduler container's share of this tenant's request lines (which replicas served the run); null when not read. */
  readonly serverLog: readonly ContainerShare[] | null;
  /** This tenant's study receipts from the daily replica and their summary; null when none were logged or the log was not read. */
  readonly study: { readonly summary: StudySummary; readonly receipts: readonly StudyReceipt[] } | null;
  readonly contractBreaches: readonly string[];
  readonly hostAnomalies: readonly string[];
}

const gib = (bytes: number) => `${(bytes / 1024 ** 3).toFixed(2)} GiB`;
const entries = (record: Record<string, number>) => Object.entries(record).map(([key, value]) => `${key} ${value}`).join(", ") || "none";

/** The first pass's window counts (a histogram keyed by count) as p50, min and max over the searches that reported one. */
export function windowsLine(histogram: Record<string, number>): string {
  const counts = Object.entries(histogram).flatMap(([count, searches]) => Array.from({ length: searches }, () => Number(count))).sort((a, b) => a - b);
  const first = counts[0], last = counts[counts.length - 1];
  return first === undefined || last === undefined ? "not reported" : `p50 ${percentile(counts, 0.5)}, ${first} to ${last}, over ${counts.length} searches`;
}

/** Searches by horizon length, so refinement outcomes and incompleteness read against how many dates were searched. */
export function horizonLines(s: LoadReport["settings"], b: BookingTally): string[] {
  const h = s.horizon;
  const rows = Object.entries(b.byHorizon).sort(([a], [z]) => Number(a) - Number(z)).map(([dates, t]) => {
    const ms = [...t.offersMs].sort((x, y) => x - y);
    return `| ${dates} | ${t.searches} | ${t.failed} | ${t.searchIncomplete} | ${t.incompleteDates} | ${ms.length === 0 ? "-" : (t.offers / ms.length).toFixed(2)} | ` +
      `${t.noOffers} | ${entries(t.refinements)} | ${entries(t.refinementStops)} | ${ms.length === 0 ? "-" : percentile(ms, 0.5)} | ${ms.length === 0 ? "-" : percentile(ms, 0.95)} |`;
  });
  return ["## Horizon", "",
    h.mode === "dynamic" ? `- Dynamic: ${h.leadWeekdays} weekdays past the furthest weekday at least ${h.fullPercent} percent full, at most ${h.weekdays} weekdays`
      : `- Fixed: ${h.weekdays === null ? `${s.horizonDates} dates` : `${h.weekdays} weekdays`}`,
    "", "| Dates | Searches | Failed | searchComplete false | incompleteDates | Offers per answered | No offer | Refinement | Stopped by | p50 ms | p95 ms |",
    "| ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- | --- | ---: | ---: |", ...rows];
}

export function loadMarkdown(report: LoadReport): string {
  const seconds = (Date.parse(report.finishedAt) - Date.parse(report.startedAt)) / 1000;
  const s = report.settings;
  const lines = [
    `# Load run ${report.run}${report.label === null ? "" : `: ${report.label}`}`, "",
    `- Scenario **${report.scenario}**, ${s.concurrency} ${report.scenario === "daily" ? "planners" : "booking desks"}` +
      `${s.dailyConcurrency === null ? "" : ` and ${s.dailyConcurrency} daily planners`}, ${s.durationS} s (ran ${seconds.toFixed(0)} s)`,
    `- Scheduler ${report.scheduler}, metro ${report.metroId} (${report.timeZone}), points ${report.points}`,
    `- Booking metro: ${s.technicians} technicians x ${s.horizonDates} dates${s.horizon.mode === "dynamic" ? " at the start" : ""}${s.dateSpread ? " per desk, spread" : ""}, ` +
      `${s.appointmentsPerDay} visits per day booked${s.horizon.bookedWeekdays === 0 ? "" : ` (${s.horizon.bookedAppointments} on the first ${s.horizon.bookedWeekdays} weekdays)`}; jobs ${s.jobs}; ` +
      `confirm ${Math.round(s.confirmRate * 100)} percent; offerLimit ${s.offerLimit}, windowHours ${s.windowHours}`,
    `- Daily day: ${s.dailyTechnicians} technicians x ${s.appointmentsPerDay} visits${s.dailyMode === "async" ? ", queued (Prefer: respond-async)" : ""}; search ${s.policy.costSearchSeconds} + ${s.policy.fairnessSearchSeconds} s; ` +
      `bookingDeadlineMs ${s.policy.bookingDeadlineMs}, regularWindowThreshold ${s.policy.regularWindowThreshold}, bookingRefinementMs ${s.policy.bookingRefinementMs ?? "the scheduler's"}`,
    `- Machine: ${report.machine.cpus} CPUs (${report.machine.cpuModel}), ${gib(report.machine.memoryBytes)}, Node ${report.machine.node}`, "",
    "## Caller-side latency", "",
    "| Operation | Calls | Statuses | Errors | Waited on BUSY | p50 ms | p95 ms | p99 ms | max ms |", "| --- | ---: | --- | --- | ---: | ---: | ---: | ---: | ---: |",
    ...report.operations.map(op => `| ${op.operation} | ${op.calls} | ${entries(op.statuses)} | ${entries(op.errors)} | ${op.retriedBusy} | ` +
      `${op.ms?.p50 ?? "-"} | ${op.ms?.p95 ?? "-"} | ${op.ms?.p99 ?? "-"} | ${op.ms?.max ?? "-"} |`),
  ];
  const reasons = report.operations.flatMap(op => Object.entries(op.reasons).map(([reason, n]) => `- ${op.operation}: ${reason} (${n})`));
  if (reasons.length > 0) lines.push("", "Error reasons (most frequent, final answers only):", "", ...reasons);
  if (report.booking !== null) {
    const b = report.booking;
    const answered = b.searches - b.failedSearches;
    lines.push("", "## Booking outcomes", "",
      `- Searches ${b.searches} (${(b.searches / (seconds / 60)).toFixed(1)} per minute), answered ${answered}, failed ${b.failedSearches}`,
      `- searchComplete false: ${b.searchIncomplete} (${answered === 0 ? "-" : ((b.searchIncomplete / answered) * 100).toFixed(1)} percent of answered)`,
      `- Refinement: ${entries(b.refinements)}; SHORTENED stopped by: ${entries(b.refinementStops)}`,
      `- Regular windows the first pass found: ${windowsLine(b.windowsFound)}`,
      `- Offers per answered search: ${answered === 0 ? "-" : (b.offers / answered).toFixed(2)}; searches with no offer: ${b.noOffers}`,
      `- Selected ${b.selected}, confirmed and written ${b.confirmed}, HOLD_UNAVAILABLE ${b.holdUnavailable}, host write conflicts ${b.hostWriteConflicts}, released ${b.released}, sent again after STALE ${b.stale}`);
    lines.push("", ...horizonLines(s, b));
  }
  if (report.daily !== null) {
    lines.push("", "## Daily outcomes", "", `- Proposals requested ${report.daily.proposals}; decisions: ${entries(report.daily.decisions)}`);
    const waits = [...report.daily.queueWaitsMs].sort((a, b) => a - b);
    if (waits.length > 0)
      lines.push(`- Queued proposals: ${waits.length}; wait before the calculation started p50 ${percentile(waits, 0.5)} ms, max ${waits[waits.length - 1] ?? 0} ms`);
  }
  if (report.server !== null) {
    const v = report.server;
    lines.push("", "## Scheduler view (Prometheus)", "", "| Route | Method | Status | Calls | Mean server ms |", "| --- | --- | --- | ---: | ---: |",
      ...v.operations.map(op => `| ${op.uri} | ${op.method} | ${op.status} | ${op.calls} | ${op.meanMs} |`), "",
      `- Admission rejections ${v.admissionRejections}; routing pairs fetched ${v.routingPairs} in ${v.routingLegRequests} batches; identity checks ${v.routingIdentityRequests}`,
      `- Scheduler process CPU: ${v.processCpu === null ? "not sampled" : `mean ${(v.processCpu.mean * 100).toFixed(0)} percent, max ${(v.processCpu.max * 100).toFixed(0)} percent of the machine`}; ` +
        `heap used max ${v.heapUsedMaxBytes === null ? "not sampled" : gib(v.heapUsedMaxBytes)}${report.metricFailures === 0 ? "" : `; ${report.metricFailures} failed scrapes`}`);
  }
  if (report.containers !== null)
    lines.push("", "## Containers (docker stats)", "", "| Container | CPU mean | CPU max | Memory max |", "| --- | ---: | ---: | ---: |",
      ...report.containers.map(c => `| ${c.name} | ${c.cpuPercent.mean.toFixed(0)} percent | ${c.cpuPercent.max.toFixed(0)} percent | ${gib(c.memoryMaxBytes)} |`));
  if (report.serverBreakdown !== null) lines.push("", ...breakdownLines(report.serverBreakdown));
  if (report.serverLog !== null) lines.push("", ...containerShareLines(report.serverLog));
  if (report.study !== null) lines.push("", ...studyLines(report.study.summary));
  if (report.contractBreaches.length > 0 || report.hostAnomalies.length > 0)
    lines.push("", "## Defects found", "", ...report.contractBreaches.map(item => `- Contract: ${item}`), ...report.hostAnomalies.map(item => `- Receipt: ${item}`));
  return `${lines.join("\n")}\n`;
}
