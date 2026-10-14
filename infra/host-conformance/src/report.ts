// The run's evidence: a JSON report for machines and a markdown report for the people signing off the integration.
import { CaseFailure, type Case, type Context } from "./cases.ts";

export interface CaseResult {
  readonly group: Case["group"];
  readonly name: string;
  readonly proves: string;
  readonly status: "PASS" | "FAIL";
  readonly ms: number;
  /** Why the case failed; null when it passed. */
  readonly failure: string | null;
}

export interface Report {
  readonly kit: string;
  readonly scheduler: string;
  readonly metroId: string;
  readonly timeZone: string;
  readonly points: string;
  readonly run: string;
  readonly startedAt: string;
  readonly finishedAt: string;
  readonly passed: number;
  readonly failed: number;
  readonly results: readonly CaseResult[];
}

export const KIT_VERSION = "1.1.0";

/** Runs one case; an unexpected error (a timeout, a refused connection) fails it like a broken expectation. */
export async function runCase(item: Case, context: Context): Promise<CaseResult> {
  const started = performance.now();
  let failure: string | null = null;
  try {
    await item.run(context);
  } catch (error) {
    failure = error instanceof CaseFailure ? error.message : `Unexpected error: ${error instanceof Error ? `${error.name}: ${error.message}` : String(error)}`;
  }
  return { group: item.group, name: item.name, proves: item.proves, status: failure === null ? "PASS" : "FAIL",
    ms: Math.round(performance.now() - started), failure };
}

const cell = (text: string) => text.replaceAll("|", "\\|").replaceAll("\n", " ");

export function markdown(report: Report): string {
  const lines = [
    "# Scheduler host conformance report", "",
    `- Result: **${report.failed === 0 ? "PASS" : "FAIL"}** (${report.passed} passed, ${report.failed} failed)`,
    `- Scheduler: ${report.scheduler}`,
    `- Metro: ${report.metroId} (${report.timeZone}), points: ${report.points}`,
    `- Run: ${report.run}, ${report.startedAt} to ${report.finishedAt}`,
    `- Kit: ${report.kit}`, "",
    "| Group | Case | Result | Time | Proves |", "| --- | --- | --- | ---: | --- |",
    ...report.results.map(result => `| ${result.group} | ${cell(result.name)} | ${result.status} | ${(result.ms / 1000).toFixed(1)} s | ${cell(result.proves)} |`),
  ];
  const failures = report.results.filter(result => result.failure !== null);
  if (failures.length > 0) {
    lines.push("", "## Failures", "");
    for (const result of failures) lines.push(`- **${result.group}: ${cell(result.name)}**: ${cell(result.failure ?? "")}`);
  }
  return `${lines.join("\n")}\n`;
}
