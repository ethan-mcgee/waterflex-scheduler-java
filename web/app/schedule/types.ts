export interface ScheduleTechnician {
  id: string;
  name: string;
  shiftStartMin: number;
  shiftEndMin: number;
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
