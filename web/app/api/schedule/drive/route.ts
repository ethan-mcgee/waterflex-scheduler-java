import { date as dateContract, text } from "@/lib/contracts";
import { NextRequest, NextResponse } from "next/server";
import { activeClient } from "@/lib/activeClient";
import { notFound, servesMetro } from "@/lib/clientScope";
import { apiScheduleDrive } from "@/lib/apiScheduleDrive";
import { DispatchRefused, refusedFor } from "@/lib/apiDispatch";

/** The drive before and after every visit of the active client's metro day, for the schedule's day view. */
export async function GET(req: NextRequest) {
  const metroId = req.nextUrl.searchParams.get("metroId");
  const date = req.nextUrl.searchParams.get("date");
  if (!metroId || !date || !text.safeParse(metroId).success || !dateContract.safeParse(date).success)
    return NextResponse.json({ error: "Missing metroId or date" }, { status: 400 });
  const clientId = (await activeClient(req)).id;
  if (!(await servesMetro(clientId, metroId))) return notFound("Metro");
  try {
    return NextResponse.json(await apiScheduleDrive(clientId, metroId, date));
  } catch (error) {
    try { refusedFor(error); }
    catch (refused) {
      if (refused instanceof DispatchRefused) return NextResponse.json({ error: refused.message }, { status: refused.status });
      throw refused;
    }
  }
}
