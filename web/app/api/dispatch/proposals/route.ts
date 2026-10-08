import { NextRequest, NextResponse } from "next/server";
import { date as dateContract, previewRequest, readBody, text } from "@/lib/contracts";
import { activeClient } from "@/lib/activeClient";
import { notFound, servesMetro } from "@/lib/clientScope";
import { apiProposalHistory, proposeApiDay } from "@/lib/apiDispatch";
import { apiDispatchStep, onlyWithApi } from "@/lib/apiDispatchRoute";
import { publicApiEnabled } from "@/lib/schedulerApi";

/** The client's daily proposals for a metro day, through the public scheduling API. */
export async function GET(req: NextRequest) {
  if (!publicApiEnabled()) return onlyWithApi();
  const metroId = text.safeParse(req.nextUrl.searchParams.get("metroId"));
  const date = dateContract.safeParse(req.nextUrl.searchParams.get("date"));
  if (!metroId.success || !date.success) return NextResponse.json({ error: "Missing metroId or date" }, { status: 400 });
  const clientId = (await activeClient(req)).id;
  if (!(await servesMetro(clientId, metroId.data))) return notFound("Metro");
  return apiDispatchStep(async () => ({ proposals: await apiProposalHistory(clientId, metroId.data, date.data) }));
}

/** Asks the scheduler to optimize the client's routes for a metro day. */
export async function POST(req: NextRequest) {
  if (!publicApiEnabled()) return onlyWithApi();
  const parsed = await readBody(req, previewRequest);
  if (!parsed.success) return NextResponse.json({ error: "Invalid request body" }, { status: 400 });
  const clientId = (await activeClient(req)).id;
  if (!(await servesMetro(clientId, parsed.data.metroId))) return notFound("Metro");
  return apiDispatchStep(() => proposeApiDay(clientId, parsed.data.metroId, parsed.data.date));
}
