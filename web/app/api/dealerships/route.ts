import { NextRequest, NextResponse } from "next/server";
import { dealershipSetup, readBody } from "@/lib/contracts";
import { prisma } from "@/lib/prisma";

export async function POST(request: NextRequest) {
  const parsed = await readBody(request, dealershipSetup);
  if (!parsed.success) return NextResponse.json({ error: "Invalid dealership setup" }, { status: 400 });
  const input = parsed.data;
  const dealership = await prisma.dealership.create({ data: input, select: { id: true } });
  return NextResponse.json({ success: true, id: dealership.id }, { status: 201 });
}
