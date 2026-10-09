import { z } from "zod";
import { NextRequest, NextResponse } from "next/server";
import { readBody, text } from "@/lib/contracts";
import { activeClient } from "@/lib/activeClient";
import { analyzeApiTimeOff } from "@/lib/apiTimeOff";
import { apiTimeOffStep } from "@/lib/apiTimeOffRoute";

/** Analyzes the next day of a pending time-off request through the scheduling API; the page calls it until done. */
export async function POST(request: NextRequest) {
  const parsed = await readBody(request, z.object({ id: text }).strict());
  if (!parsed.success) return NextResponse.json({ error: "Invalid request body" }, { status: 400 });
  const clientId = (await activeClient(request)).id;
  return apiTimeOffStep(() => analyzeApiTimeOff(clientId, parsed.data.id));
}
