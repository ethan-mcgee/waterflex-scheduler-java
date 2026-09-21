import { readBody, previewRequest } from "@/lib/contracts";
import { NextRequest, NextResponse } from "next/server";
import { previewOptimization, EngineError } from "@/lib/engineClient";

export async function POST(req: NextRequest) {
  const parsed = await readBody(req, previewRequest);
  if (!parsed.success) return NextResponse.json({ error: "Invalid request body" }, { status: 400 });
  const body = parsed.data;
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
