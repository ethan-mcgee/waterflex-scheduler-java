import { date as dateContract, text } from "@/lib/contracts";
import { NextRequest, NextResponse } from "next/server";
import { activeClient } from "@/lib/activeClient";
import { notFound, servesMetro } from "@/lib/clientScope";
import { mondayOfWeek } from "@/lib/date";
import { scheduleVersion } from "@/lib/scheduleVersion";

/**
 * The version of the active client's schedule for a week, so the schedule page can tell cheaply that another
 * dispatcher, a booking or the overnight run changed it, and reload.
 */
export async function GET(req: NextRequest) {
  const metroId = req.nextUrl.searchParams.get("metroId");
  const monday = req.nextUrl.searchParams.get("monday");
  if (!metroId || !monday || !text.safeParse(metroId).success || !dateContract.safeParse(monday).success)
    return NextResponse.json({ error: "Missing metroId or monday" }, { status: 400 });
  const clientId = (await activeClient(req)).id;
  if (!(await servesMetro(clientId, metroId))) return notFound("Metro");
  return NextResponse.json({ version: await scheduleVersion(clientId, mondayOfWeek(monday)) }, { headers: { "Cache-Control": "no-store" } });
}
