import { NextRequest, NextResponse } from "next/server";
import type { z } from "zod";
import { activeClient } from "./activeClient";
import { servesMetro } from "./clientScope";
import { readBody } from "./contracts";
import { DispatchRefused, refusedFor } from "./apiDispatch";
import { PlacementRefused } from "./apiPlacementCore";
import { StaleReceipt } from "./apiReceipt";
import { serverTiming, timed, type Phases } from "./apiMoveCheck";

/**
 * The shared shape of the schedule page's placement routes: the body checked against its contract, the metro checked
 * to be the active client's, every refusal answered with its own status, and a Server-Timing header on every answer.
 */
export async function placementRoute<T extends { metroId: string }>(req: NextRequest, contract: z.ZodType<T>, invalid: string,
  run: (clientId: string, body: T, phases: Phases) => Promise<unknown>): Promise<NextResponse> {
  const started = performance.now();
  const phases: Phases = [];
  const answer = (body: unknown, status: number, retryAfterMs: number | null = null) => {
    phases.push({ name: "total", ms: performance.now() - started });
    return NextResponse.json(body, { status, headers: { "Server-Timing": serverTiming(phases), "Cache-Control": "no-store",
      ...(retryAfterMs === null ? {} : { "Retry-After": String(Math.max(1, Math.ceil(retryAfterMs / 1000))) }) } });
  };
  const parsed = await readBody(req, contract);
  if (!parsed.success) return answer({ error: invalid }, 400);
  const clientId = await timed(phases, "scope", async () => {
    const id = (await activeClient(req)).id;
    return (await servesMetro(id, parsed.data.metroId)) ? id : null;
  });
  if (clientId === null) return answer({ error: "Metro not found" }, 404);
  try {
    return answer(await run(clientId, parsed.data, phases), 200);
  } catch (error) {
    if (error instanceof PlacementRefused) return answer({ error: error.message }, error.status);
    if (error instanceof StaleReceipt) return answer({ error: "The schedule changed while this was saved. Preview it again." }, 409);
    try { refusedFor(error); }
    catch (refused) {
      if (refused instanceof DispatchRefused) return answer({ error: refused.message }, refused.status, refused.retryAfterMs);
      throw refused;
    }
  }
}
