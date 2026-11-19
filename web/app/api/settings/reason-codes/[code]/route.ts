import { NextRequest, NextResponse } from "next/server";
import { prisma } from "@/lib/prisma";
import { readBody, reasonCodeChange, reasonCodeSaved } from "@/lib/contracts";

/**
 * Changes a reason code's label, minutes and buffer, or retires or restores it. A retired code is no longer offered for
 * new work orders; work orders that have it keep it, and so does the time they were allotted.
 */
export async function PATCH(req: NextRequest, { params }: { params: Promise<{ code: string }> }) {
  const { code } = await params;
  const parsed = await readBody(req, reasonCodeChange);
  if (!parsed.success) return NextResponse.json({ error: "Give the label, the minutes (1 to 720) and the buffer." }, { status: 400 });
  const updated = await prisma.serviceCatalog.updateMany({ where: { code }, data: parsed.data });
  if (updated.count === 0) return NextResponse.json({ error: `There is no reason code ${code}.` }, { status: 404 });
  return NextResponse.json(reasonCodeSaved.parse({ code }));
}
