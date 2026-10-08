import { readBody, previewRequest } from "@/lib/contracts";
import { NextRequest, NextResponse } from "next/server";
import { activeClient } from "@/lib/activeClient";
import { notFound, servesMetro } from "@/lib/clientScope";
import { previewOptimization, EngineError } from "@/lib/engineClient";

export async function POST(req: NextRequest) {
  const parsed = await readBody(req, previewRequest);
  if (!parsed.success) return NextResponse.json({ error: "Invalid request body" }, { status: 400 });
  const body = parsed.data;
  if (!(await servesMetro((await activeClient(req)).id, body.metroId))) return notFound("Metro");
  try {
    const result = await previewOptimization({ metro_id: body.metroId, date: body.date });
    return NextResponse.json(result);
  } catch (err) {
    if (err instanceof EngineError) {
      return NextResponse.json({ error: err.message }, { status: err.status });
    }
    throw err;
  }
}
