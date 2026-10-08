import { NextRequest, NextResponse } from "next/server";
import { readBody } from "@/lib/contracts";
import { activeClient } from "@/lib/activeClient";
import { onlyWithApi } from "@/lib/apiDispatchRoute";
import { loadRunMinutes, recentRuns, saveRunMinutes } from "@/lib/overnight";
import { minuteOf, runTimes, timeOf } from "@/lib/overnightCore";
import { publicApiEnabled } from "@/lib/schedulerApi";

// Without the public API the GET reads no request data, which would let the build cache its answer.
export const dynamic = "force-dynamic";

/** The active client's overnight run times and latest runs. */
export async function GET(request: NextRequest) {
  if (!publicApiEnabled()) return onlyWithApi();
  const clientId = (await activeClient(request)).id;
  const [minutes, runs] = await Promise.all([loadRunMinutes(clientId), recentRuns(clientId)]);
  return NextResponse.json({ times: minutes.map(timeOf), runs });
}

/** Replaces the active client's run times; an empty list leaves runs to "Run now". */
export async function PUT(request: NextRequest) {
  if (!publicApiEnabled()) return onlyWithApi();
  const parsed = await readBody(request, runTimes);
  if (!parsed.success) return NextResponse.json({ error: "Run times must be distinct 24-hour times such as 02:00, at most eight" }, { status: 400 });
  const clientId = (await activeClient(request)).id;
  const saved = await saveRunMinutes(clientId, parsed.data.times.map(minuteOf));
  return NextResponse.json({ times: saved.map(timeOf) });
}
