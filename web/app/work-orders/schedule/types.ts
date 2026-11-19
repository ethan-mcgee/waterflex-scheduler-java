import type { ServiceColor } from "@/lib/serviceColors";
import type { WorkOrderFields, WorkOrderStatus } from "@/lib/workOrdersDayCore";

export interface ScheduleTechnician {
  id: string;
  name: string;
  color: string;
  shiftStartMin: number;
  shiftEndMin: number;
  days: Record<string, { member: boolean; available: boolean; shiftStartMin: number | null; shiftEndMin: number | null }>;
}

export interface ScheduleAppointment {
  id: string;
  jobId: string;
  technicianId: string;
  technicianName: string;
  date: string;
  customerName: string;
  serviceCode: string;
  serviceName: string;
  /** The color the visit is drawn in, from its service. */
  serviceColor: ServiceColor;
  /** The work order status, or null for a job state the portal shows no status for. */
  status: WorkOrderStatus | null;
  workOrder: WorkOrderFields;
  addressLine: string;
  /** City, state and ZIP, which the City / ZIP filter searches. */
  place: string;
  windowStart: string;
  windowEnd: string;
  plannedStart: string;
  plannedEnd: string;
}

export interface ScheduleAbsence {
  technicianId: string;
  date: string;
  startMin: number;
  endMin: number;
}

/** A job waiting to be placed on the calendar: PENDING, with no appointment yet. */
export interface ScheduleJob {
  id: string;
  customerName: string;
  serviceCode: string;
  serviceName: string;
  serviceColor: ServiceColor;
  durationMin: number;
  workOrder: WorkOrderFields;
  addressLine: string;
  /** City, state and ZIP, which the City / ZIP filter searches. */
  place: string;
  /** False until the address has coordinates; such a job needs its pin confirmed before it can be placed. */
  located: boolean;
  /** The manual follow-up the job is waiting on, or null when there is none. */
  followUp: string | null;
}

/** A service on screen and the color it is drawn in, for the legend. */
export interface ScheduleServiceLegend { code: string; name: string; color: ServiceColor }

/** A reason code the new-job form and the work order editor offer, with the minutes that suggest a time. */
export interface ScheduleService { code: string; name: string; estDurationMin: number; bufferPct: number }
