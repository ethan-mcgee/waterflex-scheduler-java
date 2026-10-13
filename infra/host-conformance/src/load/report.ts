// The load run's evidence: JSON for comparison across runs, markdown for docs/api-load.md and reviews.
import type { Snapshot } from "../snapshot.ts";
import type { ServerView } from "./metrics.ts";
import type { BookingTally, ContainerUsage, DailyTally } from "./scenarios.ts";
import type { OperationSummary } from "./stats.ts";

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
    readonly appointmentsPerDay: number; readonly horizonDates: number; readonly dailyTechnicians: number; readonly jobs: "cold" | "warm";
    readonly confirmRate: number; readonly offerLimit: number; readonly windowHours: number; readonly policy: Snapshot["policy"]; readonly seed: number;
  };
  readonly machine: { readonly cpus: number; readonly cpuModel: string; readonly memoryBytes: number; readonly node: string };
  readonly operations: readonly OperationSummary[];
  readonly booking: BookingTally | null;
  readonly daily: DailyTally | null;
  readonly server: ServerView | null;
  readonly metricFailures: number;
  readonly containers: readonly ContainerUsage[] | null;
  readonly contractBreaches: readonly string[];
  readonly hostAnomalies: readonly string[];
}

const gib = (bytes: number) => `${(bytes / 1024 ** 3).toFixed(2)} GiB`;
const entries = (record: Record<string, number>) => Object.entries(record).map(([key, value]) => `${key} ${value}`).join(", ") || "none";

export function loadMarkdown(report: LoadReport): string {
  const seconds = (Date.parse(report.finishedAt) - Date.parse(report.startedAt)) / 1000;
  const s = report.settings;
  const lines = [
    `# Load run ${report.run}${report.label === null ? "" : `: ${report.label}`}`, "",
    `- Scenario **${report.scenario}**, ${s.concurrency} ${report.scenario === "daily" ? "planners" : "booking desks"}` +
      `${s.dailyConcurrency === null ? "" : ` and ${s.dailyConcurrency} daily planners`}, ${s.durationS} s (ran ${seconds.toFixed(0)} s)`,
    `- Scheduler ${report.scheduler}, metro ${report.metroId} (${report.timeZone}), points ${report.points}`,
    `- Booking metro: ${s.technicians} technicians x ${s.horizonDates} dates, ${s.appointmentsPerDay} visits per day booked; jobs ${s.jobs}; ` +
      `confirm ${Math.round(s.confirmRate * 100)} percent; offerLimit ${s.offerLimit}, windowHours ${s.windowHours}`,
    `- Daily day: ${s.dailyTechnicians} technicians x ${s.appointmentsPerDay} visits; search ${s.policy.costSearchSeconds} + ${s.policy.fairnessSearchSeconds} s; ` +
      `bookingDeadlineMs ${s.policy.bookingDeadlineMs}`,
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
      `- Offers per answered search: ${answered === 0 ? "-" : (b.offers / answered).toFixed(2)}; searches with no offer: ${b.noOffers}`,
      `- Selected ${b.selected}, confirmed and written ${b.confirmed}, HOLD_UNAVAILABLE ${b.holdUnavailable}, host write conflicts ${b.hostWriteConflicts}, released ${b.released}`);
  }
  if (report.daily !== null)
    lines.push("", "## Daily outcomes", "", `- Proposals requested ${report.daily.proposals}; decisions: ${entries(report.daily.decisions)}`);
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
  if (report.contractBreaches.length > 0 || report.hostAnomalies.length > 0)
    lines.push("", "## Defects found", "", ...report.contractBreaches.map(item => `- Contract: ${item}`), ...report.hostAnomalies.map(item => `- Receipt: ${item}`));
  return `${lines.join("\n")}\n`;
}
