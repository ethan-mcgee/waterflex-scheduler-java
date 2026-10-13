export interface BoardTechnician {
  id: string;
  name: string;
  color: string;
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
  lat: number | null;
  lng: number | null;
  durationMin: number;
  /** How the address was located, or null when it has not been geocoded. */
  geocodePrecision: string | null;
  /** When a person confirmed the map pin, or null when nobody has. */
  pinConfirmedAt: string | null;
  jobStatus: "PENDING" | "SCHEDULED" | "COMPLETED" | "CANCELLED";
}
