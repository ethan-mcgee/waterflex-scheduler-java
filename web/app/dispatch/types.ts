export interface BoardTechnician {
  id: string;
  name: string;
  homeLat: number;
  homeLng: number;
  shiftStartMin: number;
  shiftEndMin: number;
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
  lat: number;
  lng: number;
}
