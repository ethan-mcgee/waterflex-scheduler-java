import { errorMessage, moveCheckResult, type MoveCheckRequest, type MoveCheckResult } from "./contracts";

/** The browser's timing of one move check: from the drop to the answer painted on screen. No customer data. */
export const MOVE_CHECK_MEASURE = "waterflex.move-check";

/** The start of a move check's timing, taken in the drop handler; pass it to recordMoveCheck once the answer shows. */
export function moveCheckStart(): number {
  return performance.now();
}

export function recordMoveCheck(start: number, detail: { outcome: MoveCheckResult["outcome"] | "ERROR"; appointmentId: string; targetTechnicianId: string }) {
  if (performance.getEntriesByName(MOVE_CHECK_MEASURE, "measure").length >= 512) performance.clearMeasures(MOVE_CHECK_MEASURE);
  performance.measure(MOVE_CHECK_MEASURE, { start, end: performance.now(), detail });
}

/** A check the portal refused or could not answer, with the message to show. */
export class MoveCheckFailed extends Error {
  constructor(readonly status: number, message: string) { super(message); this.name = "MoveCheckFailed"; }
}

/** Asks the portal whether the day still works with the move. A newer drop aborts an older check through `signal`. */
export async function requestMoveCheck(request: MoveCheckRequest, signal: AbortSignal): Promise<MoveCheckResult> {
  const response = await fetch("/api/schedule/move-check", {
    method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(request), signal, cache: "no-store",
  });
  const body: unknown = await response.json().catch(() => undefined);
  if (!response.ok) throw new MoveCheckFailed(response.status, errorMessage(body, "The move could not be checked. Try again."));
  const parsed = moveCheckResult.safeParse(body);
  if (!parsed.success) throw new MoveCheckFailed(502, "The move check answered in an unexpected form. Try again.");
  return parsed.data;
}
