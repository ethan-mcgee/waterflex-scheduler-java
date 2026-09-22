import { NextRequest, NextResponse } from "next/server";
import { z } from "zod";
import { readBody, text } from "@/lib/contracts";
import { searchAddress } from "@/lib/geocode";

const address = z.object({ line1: text, city: text, state: text, postalCode: text }).strict();

export async function POST(request: NextRequest) {
  const parsed = await readBody(request, address);
  if (!parsed.success) return NextResponse.json({ error: "Enter a complete street address" }, { status: 400 });
  return NextResponse.json({ candidates: await searchAddress(parsed.data) });
}
