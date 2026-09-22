import { readBody, testOperation } from "@/lib/contracts";
import { NextRequest, NextResponse } from "next/server";
import { localTestRequestAllowed } from "@/lib/bookingTestAccess";
import { advanceTestRun, controlTestRun, createTestRun, listTestRuns, purgeTestRun, readTestRun, TestRunError } from "@/lib/bookingTestRunner";
import { bookingHorizon } from "@/lib/bookingTestCore";

export const dynamic = "force-dynamic";
export const maxDuration = 60;
function failure(error: unknown) {
  return NextResponse.json({ error: error instanceof Error ? error.message : "Test operation failed" }, { status: error instanceof TestRunError ? error.status : 500 });
}
export async function GET(request: NextRequest) {
  if (!localTestRequestAllowed(request)) return new NextResponse(null, { status: 404 });
  try {
    const id = request.nextUrl.searchParams.get("id");
    return NextResponse.json(id ? await readTestRun(id) : { runs: await listTestRuns(), horizon: bookingHorizon() });
  } catch (error) { return failure(error); }
}
export async function POST(request: NextRequest) {
  if (!localTestRequestAllowed(request)) return new NextResponse(null, { status: 404 });
  try {
    const parsed = await readBody(request, testOperation);
    if (!parsed.success) throw new TestRunError("Invalid test operation.", 400);
    const body = parsed.data;
    if (!body || typeof body.id !== "string") throw new TestRunError("Run ID required.", 400);
    if (body.action === "create") return NextResponse.json(await createTestRun(body.id, body.config));
    if ((body.action === "resume" || body.action === "pause" || body.action === "stop")) return NextResponse.json(await controlTestRun(body.id, body.action));
    if (body.action === "purge") return NextResponse.json(await purgeTestRun(body.id));
    if (body.action === "advance" && Number.isInteger(body.revision)) return NextResponse.json(await advanceTestRun(body.id, body.revision));
    throw new TestRunError("Invalid test operation.", 400);
  } catch (error) { return failure(error); }
}
