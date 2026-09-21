import { NextRequest, NextResponse } from "next/server";
import { EngineError, optimizationHistory, optimizationRun } from "@/lib/engineClient";

export async function GET(req: NextRequest) {
  const metroId = req.nextUrl.searchParams.get("metroId");
  const date = req.nextUrl.searchParams.get("date");
  if (!metroId || !date) {
    return NextResponse.json({ error: "Missing metroId or date" }, { status: 400 });
  }
  try {
    const history = await optimizationHistory(metroId, date);
    const runId = req.nextUrl.searchParams.get("runId");
    // Deep links must also work after the saved run leaves the latest-20 history.
    if (runId && !history.runs.some(run => run.run_id === runId)) {
      const selected = await optimizationRun(runId);
      if (selected.metro_id !== metroId || selected.service_date !== date) {
        return NextResponse.json({ error: "Preview does not belong to this dispatch day." }, { status: 404 });
      }
      history.runs.push(selected);
    }
    return NextResponse.json(history);
  } catch (error) {
    if (error instanceof EngineError) {
      return NextResponse.json({ error: error.message }, { status: error.status });
    }
    throw error;
  }
}
