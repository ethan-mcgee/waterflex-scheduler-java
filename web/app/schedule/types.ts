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
