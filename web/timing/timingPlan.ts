// The record IDs the move check timing study seeded (timing/seedMoveTiming.ts writes them to <run dir>/plan.json) and
// the timing spec drags. No secrets.
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { z } from "zod";
import { date as dateContract } from "../lib/contracts";

export const TIMING_CLIENT_ID = "move-timing";
export const TIMING_METRO_ID = "metro-omaha";
export const TIMING_ZONE = "America/Chicago";

const day = z.object({ date: dateContract, kind: z.enum(["warm", "cold-feasible", "cold-not-feasible"]), moving: z.string().min(1) }).strict();
export const timingPlan = z.object({
  runId: z.string().min(1), clientId: z.literal(TIMING_CLIENT_ID), metroId: z.literal(TIMING_METRO_ID), timeZone: z.literal(TIMING_ZONE),
  technicians: z.object({ source: z.string().min(1), roomy: z.string().min(1), full: z.string().min(1) }).strict(),
  days: z.array(day).min(1),
  created: z.object({ client: z.boolean(), metro: z.boolean(), settings: z.boolean(), dealership: z.string().min(1), depot: z.string().min(1),
    service: z.string().min(1), customers: z.array(z.string().min(1)) }).strict(),
}).strict();
export type TimingPlan = z.infer<typeof timingPlan>;

export function readPlan(runDir: string): TimingPlan {
  return timingPlan.parse(JSON.parse(readFileSync(join(runDir, "plan.json"), "utf8")));
}
