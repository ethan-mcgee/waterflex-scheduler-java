import { NextRequest, NextResponse } from "next/server";
import { clearFakeData, generateFakeData } from "@/lib/fakeData";
import { validateFakeDataInput } from "@/lib/fakeDataCore";

export const dynamic = "force-dynamic";

function messageFrom(error: unknown): string {
  return error instanceof Error ? error.message : "Fake data operation failed.";
}

export async function POST(request: NextRequest) {
  let body: unknown;
  try {
    body = await request.json();
  } catch {
    return NextResponse.json({ error: "Request body must be valid JSON." }, { status: 400 });
  }
  if (!body || typeof body !== "object") {
    return NextResponse.json({ error: "Request body must be an object." }, { status: 400 });
  }
  const value = body as Record<string, unknown>;
  const input = {
    startDate: value.startDate,
    endDate: value.endDate,
    totalCalls: value.totalCalls,
    seed: value.seed,
  };
  try {
    validateFakeDataInput(input as Parameters<typeof validateFakeDataInput>[0]);
  } catch (error) {
    return NextResponse.json({ error: messageFrom(error) }, { status: 400 });
  }
  try {
    return NextResponse.json(
      await generateFakeData(input as Parameters<typeof generateFakeData>[0])
    );
  } catch (error) {
    console.error("Fake data generation failed", error);
    return NextResponse.json({ error: messageFrom(error) }, { status: 500 });
  }
}

export async function DELETE(request: NextRequest) {
  const startDate = request.nextUrl.searchParams.get("startDate") ?? "";
  const endDate = request.nextUrl.searchParams.get("endDate") ?? "";
  try {
    validateFakeDataInput({ startDate, endDate, totalCalls: 1 });
  } catch (error) {
    return NextResponse.json({ error: messageFrom(error) }, { status: 400 });
  }
  try {
    return NextResponse.json(await clearFakeData(startDate, endDate));
  } catch (error) {
    console.error("Fake data cleanup failed", error);
    return NextResponse.json({ error: messageFrom(error) }, { status: 500 });
  }
}
