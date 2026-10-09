import { date as dateContract, text } from "@/lib/contracts";
import { NextRequest, NextResponse } from "next/server";
import { activeClient } from "@/lib/activeClient";
import { notFound, servesMetro, servesMetroAlone } from "@/lib/clientScope";
import { dispatchGeometry, EngineError } from "@/lib/engineClient";
import { apiDispatchGeometry } from "@/lib/apiGeometry";
import { DispatchRefused, refusedFor } from "@/lib/apiDispatch";
import { publicApiEnabled } from "@/lib/schedulerApi";

export async function GET(req: NextRequest) {
  const metroId = req.nextUrl.searchParams.get("metroId");
  const date = req.nextUrl.searchParams.get("date");
  const runId = req.nextUrl.searchParams.get("runId") ?? undefined;
  const phase = req.nextUrl.searchParams.get("phase") ?? "current";
  if (!metroId || !date || !text.safeParse(metroId).success || !dateContract.safeParse(date).success) return NextResponse.json({ error: "Missing metroId or date" }, { status: 400 });
  if (!["current", "before", "after"].includes(phase) || (phase === "after" && !runId)) return NextResponse.json({ error: "Invalid route phase or preview" }, { status: 400 });
  const clientId = (await activeClient(req)).id;
  if (publicApiEnabled()) {
    // Through the public API the board shows the current routes only; proposals are reviewed as timed routes, not engine runs.
    if (phase !== "current" || !(await servesMetro(clientId, metroId))) return notFound(phase === "current" ? "Metro" : "Route preview");
    try {
      return NextResponse.json(await apiDispatchGeometry(clientId, metroId, date));
    } catch (error) {
      try { refusedFor(error); }
      catch (refused) { if (refused instanceof DispatchRefused) return NextResponse.json({ error: refused.message }, { status: refused.status }); throw refused; }
    }
  }
  // An engine run's routes cover the whole metro, so they are shown only in a metro of the client's own.
  if (!(await (phase === "current" ? servesMetro : servesMetroAlone)(clientId, metroId))) return notFound("Metro");
  try {
    return NextResponse.json(await dispatchGeometry(metroId, date, runId, phase, clientId));
  } catch (error) {
    if (error instanceof EngineError) return NextResponse.json({ error: error.message }, { status: error.status });
    throw error;
  }
}
