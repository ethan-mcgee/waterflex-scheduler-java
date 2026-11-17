import type { ServiceColor } from "@/lib/serviceColors";

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
  technicianId: string;
  technicianName: string;
  date: string;
  customerName: string;
  serviceName: string;
  /** The color the visit is drawn in, from its service. */
  serviceColor: ServiceColor;
  addressLine: string;
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
  serviceName: string;
  serviceColor: ServiceColor;
  durationMin: number;
  addressLine: string;
  /** False until the address has coordinates; such a job needs its pin confirmed before it can be placed. */
  located: boolean;
  /** The manual follow-up the job is waiting on, or null when there is none. */
  followUp: string | null;
}

/** A service on screen and the color it is drawn in, for the legend. */
export interface ScheduleServiceLegend { name: string; color: ServiceColor }

/** A service the new-job form offers. */
export interface ScheduleService { code: string; name: string }

/** The default day view (technicians as columns on one time axis) or the weekly grid kept for existing links. */
export type ScheduleViewMode = "day" | "week";
