import type { Prisma } from "@prisma/client";
import type { WorkOrderFields } from "./workOrdersDayCore";

/** What to load with a job to describe its work order: its first code (the service) and its second and third. */
export const WORK_ORDER_INCLUDE = {
  service: { select: { code: true, name: true } },
  reasonCodes: { orderBy: { position: "asc" }, include: { reasonCode: { select: { code: true, name: true } } } },
} satisfies Prisma.JobInclude;

type JobWithWorkOrder = Prisma.JobGetPayload<{ include: typeof WORK_ORDER_INCLUDE }>;

/** A job's work order fields, its reason codes in order. */
export function workOrderFields(job: JobWithWorkOrder): WorkOrderFields {
  return {
    reasonCodes: [{ code: job.service.code, name: job.service.name }, ...job.reasonCodes.map(item => ({ code: item.reasonCode.code, name: item.reasonCode.name }))],
    workOrderType: job.workOrderType, purchaseOrder: job.purchaseOrder, technicianMessage: job.technicianMessage,
    customerRequest: job.customerRequest, callFirst: job.callFirst, durationMin: job.durationMin,
    suggestedDurationMin: job.suggestedDurationMin, durationOverridden: job.durationOverridden,
  };
}
