import { NextRequest, NextResponse } from "next/server";
import { activeClient } from "@/lib/activeClient";
import { readBody, workOrderSaved, workOrderUpdate } from "@/lib/contracts";
import { updateWorkOrder } from "@/lib/workOrderUpdate";

/** Edits a work order's details, and its reason codes and time allotted while it waits for the calendar. */
export async function PATCH(req: NextRequest, { params }: { params: Promise<{ jobId: string }> }) {
  const { jobId } = await params;
  const parsed = await readBody(req, workOrderUpdate);
  if (!parsed.success) return NextResponse.json({ error: "Invalid work order" }, { status: 400 });
  const client = await activeClient(req);
  const result = await updateWorkOrder(client.id, jobId, parsed.data);
  if ("error" in result) return NextResponse.json({ error: result.error }, { status: result.status });
  return NextResponse.json(workOrderSaved.parse(result));
}
