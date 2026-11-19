import { Prisma } from "@prisma/client";
import { NextRequest, NextResponse } from "next/server";
import { prisma } from "@/lib/prisma";
import { readBody, reasonCodeCreate, reasonCodeSaved } from "@/lib/contracts";

/** Adds a reason code to the catalog, which every client of this portal shares. */
export async function POST(req: NextRequest) {
  const parsed = await readBody(req, reasonCodeCreate);
  if (!parsed.success) return NextResponse.json({ error: "Give the code, its label, its minutes (1 to 720) and its buffer." }, { status: 400 });
  try {
    const created = await prisma.serviceCatalog.create({ data: parsed.data });
    return NextResponse.json(reasonCodeSaved.parse({ code: created.code }), { status: 201 });
  } catch (error) {
    if (error instanceof Prisma.PrismaClientKnownRequestError && error.code === "P2002")
      return NextResponse.json({ error: `Reason code ${parsed.data.code} already exists.` }, { status: 409 });
    throw error;
  }
}
