import { readFile } from "node:fs/promises";
import path from "node:path";
import { NextResponse } from "next/server";

export const dynamic = "force-dynamic";

function enabled() {
  const configured = process.env.OPTIMIZATION_TEST_ENABLED;
  return configured === "true" || (configured === undefined && process.env.NODE_ENV !== "production");
}

export async function GET() {
  if (!enabled()) return NextResponse.json({ error: "Not found." }, { status: 404 });
  try {
    const report = await readFile(path.join(process.cwd(), ".data", "california-replay.json"), "utf8");
    return new NextResponse(report, {
      headers: { "Content-Type": "application/json; charset=utf-8", "Cache-Control": "no-store" },
    });
  } catch (error) {
    if (error && typeof error === "object" && "code" in error && error.code === "ENOENT") {
      return NextResponse.json({ error: "No local California replay report has been generated." }, { status: 404 });
    }
    return NextResponse.json({ error: "Unable to read California replay report." }, { status: 500 });
  }
}
