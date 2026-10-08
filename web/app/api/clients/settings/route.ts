import { NextRequest, NextResponse } from "next/server";
import { readBody } from "@/lib/contracts";
import { activeClient } from "@/lib/activeClient";
import { saveSolverSettings, toView } from "@/lib/clientSettings";
import { storeSolverSettings } from "@/lib/clientSettingsStore";

/** Saves the active client's booking and routing settings. */
export async function PUT(request: NextRequest) {
  const parsed = await readBody(request, saveSolverSettings);
  if (!parsed.success) return NextResponse.json({ error: "Invalid settings" }, { status: 400 });
  const client = await activeClient(request);
  const outcome = await storeSolverSettings(client.id, parsed.data.expectedVersion, parsed.data.settings);
  if ("stale" in outcome) return NextResponse.json({ error: "These settings changed since you opened them. Reload to see the current values." }, { status: 409 });
  return NextResponse.json(toView(outcome.saved));
}
