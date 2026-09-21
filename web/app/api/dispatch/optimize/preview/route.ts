import { readBody, previewRequest } from "@/lib/contracts";
import { NextRequest, NextResponse } from "next/server";
import { EngineError, previewOptimization } from "@/lib/engineClient";

export async function POST(req: NextRequest) {
  const parsed = await readBody(req, previewRequest);
  if (!parsed.success) return NextResponse.json({ error: "Invalid request body" }, { status: 400 });
  const body = parsed.data;
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
