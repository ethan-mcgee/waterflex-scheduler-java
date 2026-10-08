import { readBody, previewRequest } from "@/lib/contracts";
import { NextRequest, NextResponse } from "next/server";
import { activeClient } from "@/lib/activeClient";
import { notFound, servesMetro } from "@/lib/clientScope";
import { EngineError, previewOptimization } from "@/lib/engineClient";

export async function POST(req: NextRequest) {
  const parsed = await readBody(req, previewRequest);
  if (!parsed.success) return NextResponse.json({ error: "Invalid request body" }, { status: 400 });
  const body = parsed.data;
  if (!(await servesMetro((await activeClient(req)).id, body.metroId))) return notFound("Metro");
  try {
    return NextResponse.json(
      await previewOptimization({ metro_id: body.metroId, date: body.date })
    );
  } catch (error) {
    if (error instanceof EngineError) {
      return NextResponse.json({ error: error.message }, { status: error.status });
    }
    throw error;
  }
}
