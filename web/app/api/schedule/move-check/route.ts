import { NextRequest, NextResponse } from "next/server";
import { activeClient } from "@/lib/activeClient";
import { servesMetro } from "@/lib/clientScope";
import { moveCheckRequest, readBody } from "@/lib/contracts";
import { DispatchRefused, refusedFor } from "@/lib/apiDispatch";
import { MoveRefused } from "@/lib/apiMoveCheckCore";
import { checkMove, serverTiming, timed, type Phases } from "@/lib/apiMoveCheck";

/**
 * Whether the active client's day would still work with an appointment on another technician. Nothing is saved.
 * Every answer carries a Server-Timing header with the portal's own steps, so a browser can see where the time went.
 */
export async function POST(req: NextRequest) {
  const started = performance.now();
  const phases: Phases = [];
  const answer = (body: unknown, status: number) => {
    phases.push({ name: "total", ms: performance.now() - started });
    return NextResponse.json(body, { status, headers: { "Server-Timing": serverTiming(phases), "Cache-Control": "no-store" } });
  };
  const parsed = await readBody(req, moveCheckRequest);
  if (!parsed.success) return answer({ error: "Send metroId, serviceDate, appointmentId and targetTechnicianId." }, 400);
  const { metroId, ...move } = parsed.data;
  const clientId = await timed(phases, "scope", async () => {
    const id = (await activeClient(req)).id;
    return (await servesMetro(id, metroId)) ? id : null;
  });
  if (clientId === null) return answer({ error: "Metro not found" }, 404);
  try {
    return answer(await checkMove(clientId, metroId, move, phases), 200);
  } catch (error) {
    if (error instanceof MoveRefused) return answer({ error: error.message }, error.status);
    try { refusedFor(error); }
    catch (refused) {
      if (refused instanceof DispatchRefused) return answer({ error: refused.message }, refused.status);
      throw refused;
    }
  }
}
