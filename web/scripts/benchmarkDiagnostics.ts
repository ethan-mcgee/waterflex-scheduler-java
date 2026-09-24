import { readFile } from "node:fs/promises";
import { z } from "zod";

const count = z.int().nonnegative();
const diagnostic = z.object({ format: z.literal(1), completed: z.boolean(), stopReason: z.string().min(1),
  configurationFingerprint: z.string().min(1), routingIdentity: z.string().min(1), capturedAt: z.iso.datetime(),
  snapshotAgeMs: count, routingPairs: count, evaluatedRoutes: count, reusedRoutes: count, prunedArrangements: count,
  optionalRefinementMillis: count, distinctRegularWindows: count, confirmedRegularMinutes: count, regularCapacityMinutes: count,
  overtimeAuthorized: z.boolean(), limits: z.object({ routes: count, depth: count, beam: count, arrangementsPerWindow: count }),
  coverage: z.array(z.object({ date: z.iso.date(), windowStart: z.iso.datetime(), windowEnd: z.iso.datetime(), routes: count,
    arrangements: count, moves: count, candidateEvaluations: count, completed: z.boolean(), stopReason: z.string().min(1) })),
  offerSources: z.record(z.string(), z.enum(["INSERTION", "REARRANGEMENT"])),
});

/** Optional dedicated server log, read after the measured case. Never read inside search timing. */
export async function benchmarkDiagnostics(path: string | undefined, jobIds: string[]) {
  if (path == null) return null;
  const requested = new Set(jobIds);
  const attempts: Array<{ jobId: string; diagnostic: z.infer<typeof diagnostic> }> = [];
  for (const line of (await readFile(path, "utf8")).split(/\r?\n/)) {
    const match = /Booking search diagnostics for job ([^\s:]+): (.*)$/.exec(line);
    if (match?.[1] == null || match[2] == null || !requested.has(match[1])) continue;
    attempts.push({ jobId: match[1], diagnostic: diagnostic.parse(JSON.parse(match[2])) });
  }
  const observed = new Set(attempts.map(item => item.jobId));
  return { attempts, unavailableJobIds: jobIds.filter(id => !observed.has(id)),
    limitation: "Preparation diagnostics can precede a commit conflict. Multiple snapshot retries remain separate; early queue/routing/snapshot failures may have no prepared diagnostic." };
}
