import { NextRequest, NextResponse } from "next/server";
import { EngineError, runOptimizationSimulation } from "@/lib/engineClient";

export const dynamic = "force-dynamic";
export const maxDuration = 300;

function enabled() {
  const configured = process.env.OPTIMIZATION_TEST_ENABLED;
  return configured === "true" || (configured === undefined && process.env.NODE_ENV !== "production");
}

export async function POST(request: NextRequest) {
  if (!enabled()) return NextResponse.json({ error: "Not found." }, { status: 404 });
  try {
    const body = (await request.json()) as Record<string, unknown>;
    const report = await runOptimizationSimulation({
      job_count: Number(body.jobCount),
      technician_count: Number(body.technicianCount),
      horizon_days: Number(body.horizonDays),
      seed: Number(body.seed),
    });
    return NextResponse.json(report);
  } catch (error) {
    const status = error instanceof EngineError ? error.status : 400;
    return NextResponse.json(
      { error: error instanceof Error ? error.message : "Simulation failed." },
      { status },
    );
  }
}
