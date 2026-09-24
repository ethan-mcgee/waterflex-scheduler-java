export interface BoardTechnician {
  id: string;
  name: string;
  color: string;
  homeLat: number;
  homeLng: number;
  shiftStartMin: number;
  shiftEndMin: number;
  routeTiming?: import("@/lib/currentRouteTiming").CurrentRouteTiming;
}

export interface BoardAppointment {
  id: string;
  technicianId: string;
  sequence: number;
  windowStart: string;
  windowEnd: string;
  plannedStart: string;
  plannedEnd: string;
  customerName: string;
  serviceName: string;
  addressLine: string;
  lat: number | null;
  lng: number | null;
}
