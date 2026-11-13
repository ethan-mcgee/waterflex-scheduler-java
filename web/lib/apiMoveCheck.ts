import { assembleSnapshot, loadSnapshotFacts } from "./clientSnapshot";
import type { MoveCheckResult } from "./contracts";
import { moveResult, onlyTechnicians, withMove, type Move } from "./apiMoveCheckCore";
import { evaluateRoutes } from "./schedulerApi";

/** Wall time per step of one check, in milliseconds, in the order the steps ran. */
export type Phases = Array<{ name: string; ms: number }>;

/** Runs one step and records how long it took, whether it returned or threw. */
export async function timed<T>(phases: Phases, name: string, step: () => Promise<T> | T): Promise<T> {
  const started = performance.now();
  try { return await step(); }
  finally { phases.push({ name, ms: performance.now() - started }); }
}

/**
 * The phases as a Server-Timing header, so the browser's Resource Timing entry for the request carries them. Names
 * are fixed tokens and durations are rounded to a tenth of a millisecond.
 */
export function serverTiming(phases: Phases): string {
  return phases.map(phase => `${phase.name};dur=${Math.max(0, phase.ms).toFixed(1)}`).join(", ");
}

/**
 * Whether the client's day would still work with the appointment on another technician. It loads the client's day,
 * applies the move, sends the two affected technicians' days to the scheduler to be timed, and writes nothing.
 */
export async function checkMove(clientId: string, metroId: string, move: Move, phases: Phases): Promise<MoveCheckResult> {
  const facts = await timed(phases, "facts", () => loadSnapshotFacts(clientId, metroId, [move.serviceDate]));
  const moved = withMove(facts, move);
  if ("refusal" in moved) return moved.refusal;
  const snapshot = await timed(phases, "assemble", () =>
    assembleSnapshot(onlyTechnicians(moved.facts, [moved.sourceTechnicianId, moved.targetTechnicianId])));
  const evaluation = await timed(phases, "scheduler", () => evaluateRoutes(clientId, { serviceDate: move.serviceDate, snapshot }));
  return moveResult(evaluation, moved, move.serviceDate);
}
