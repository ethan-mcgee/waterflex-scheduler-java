import type { z } from "zod";
import type { workOrderUpdate } from "./contracts";
import { prisma } from "./prisma";
import { ownsJob } from "./clientScope";
import { activeReasonCodes } from "./reasonCodes";
import { allotTime, suggestedMinutes } from "./reasonCodesCore";

export type WorkOrderUpdate = z.infer<typeof workOrderUpdate>;

/**
 * Saves a work order's details, and its reason codes and time allotted while it waits for the calendar. A booked visit
 * keeps its codes and time: its day was planned with them, and changing them would need the day planned again.
 */
export async function updateWorkOrder(clientId: string, jobId: string, input: WorkOrderUpdate): Promise<{ saved: true } | { status: number; error: string }> {
  if (!(await ownsJob(clientId, jobId))) return { status: 404, error: "Work order not found" };
  const job = await prisma.job.findUnique({ where: { id: jobId }, select: { status: true, appointment: { select: { id: true } } } });
  if (job === null) return { status: 404, error: "Work order not found" };
  const details = { workOrderType: input.workOrderType, purchaseOrder: input.purchaseOrder, technicianMessage: input.technicianMessage,
    customerRequest: input.customerRequest, callFirst: input.callFirst };
  if (input.timing === undefined) {
    await prisma.job.update({ where: { id: jobId }, data: details });
    return { saved: true };
  }
  if (job.status !== "PENDING" || job.appointment !== null)
    return { status: 409, error: "A booked visit keeps its reason codes and time allotted. Change the details only, or cancel and book it again." };
  const found = await activeReasonCodes(input.timing.reasonCodes);
  if ("error" in found) return { status: 400, error: found.error };
  const [first, ...extra] = found.codes;
  if (first === undefined) return { status: 400, error: "A work order needs a reason code." };
  const allotted = allotTime(suggestedMinutes(found.codes), input.timing.durationMin ?? undefined);
  if ("refusal" in allotted) return { status: allotted.refusal.status, error: allotted.refusal.error };
  const saved = await prisma.$transaction(async tx => {
    // Only a job still waiting is changed: one placed meanwhile keeps the codes and time its day was planned with.
    const updated = await tx.job.updateMany({ where: { id: jobId, status: "PENDING", appointment: null }, data: { ...details, serviceId: first.id,
      durationMin: allotted.durationMin, suggestedDurationMin: allotted.suggestedMin, durationOverridden: allotted.overridden } });
    if (updated.count !== 1) return false;
    await tx.jobReasonCode.deleteMany({ where: { jobId } });
    if (extra.length > 0) await tx.jobReasonCode.createMany({ data: extra.map((code, index) => ({ jobId, position: index + 2, reasonCodeId: code.id })) });
    return true;
  });
  if (!saved) return { status: 409, error: "This work order was placed on the calendar while you edited it. Nothing was saved; edit its details again." };
  return { saved: true };
}
