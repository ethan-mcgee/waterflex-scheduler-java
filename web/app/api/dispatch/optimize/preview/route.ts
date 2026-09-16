import { NextRequest, NextResponse } from "next/server";
import { EngineError, previewOptimization } from "@/lib/engineClient";

export async function POST(req: NextRequest) {
  const body = (await req.json()) as { metroId?: string; date?: string };
  if (!body.metroId || !body.date) {
    return NextResponse.json({ error: "Missing metroId or date" }, { status: 400 });
  }
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
