import { NextRequest, NextResponse } from "next/server";
import { previewOptimization, EngineError } from "@/lib/engineClient";

export async function POST(req: NextRequest) {
  const body = (await req.json()) as { metroId?: string; date?: string };
  if (!body.metroId || !body.date) {
    return NextResponse.json({ error: "Missing metroId or date" }, { status: 400 });
  }

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
