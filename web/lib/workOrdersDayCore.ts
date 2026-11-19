/**
 * The work orders section's view of one metro day: plain data and the pure rules that shape it. The database read is
 * in workOrdersDay.ts; everything here runs without one, so it is unit tested.
 */

import { reasonCodesLabel } from "./reasonCodesCore";

export type WorkOrderStatus = "SCHEDULED" | "COMPLETED";

/** The portal's job statuses (JobStatus in the Prisma schema). */
export type JobStatusName = "PENDING" | "SCHEDULED" | "COMPLETED" | "CANCELLED";

export interface WorkOrderTechnician {
  id: string; name: string; color: string; initials: string;
  homeLat: number; homeLng: number;
  /** The day's shift in local minutes after midnight, or null when the technician is off that day. */
  shift: { startMin: number; endMin: number } | null;
  /** The technician's usual shift from their profile, whatever the day. */
  usualShift: { startMin: number; endMin: number };
}

/** A reason code as the portal names it. */
export interface ReasonCodeRef { code: string; name: string }

/** What a work order says beyond its customer, place and time. Missing values stay null; nothing is filled in. */
export interface WorkOrderFields {
  /** One to three codes in order; the first is the job's service, which the scheduler matches qualifications on. */
  reasonCodes: ReasonCodeRef[];
  workOrderType: string | null;
  purchaseOrder: string | null;
  technicianMessage: string | null;
  customerRequest: string | null;
  /** Null when the work order did not say (jobs taken in before the field existed). */
  callFirst: boolean | null;
  /** The time allotted, which the scheduler plans with. */
  durationMin: number;
  /** The codes' suggestion when the job was taken in; null for jobs taken in before reason codes. */
  suggestedDurationMin: number | null;
  durationOverridden: boolean;
}

/** How a card names its work order: the first reason code, and how many more it has ("Salt bridge +1 more"). */
export function workOrderLabel(fields: Pick<WorkOrderFields, "reasonCodes">): string {
  const label = reasonCodesLabel(fields.reasonCodes.map(code => code.name));
  if (label === null) throw new Error("A work order has no reason code");
  return label;
}

export interface WorkOrderCard {
  appointmentId: string; jobId: string; technicianId: string;
  /** Null when the job's status has no work order status yet (see workOrderStatus). */
  status: WorkOrderStatus | null;
  jobStatus: JobStatusName;
  /** The first reason code, which colors the card and the map pin. */
  typeCode: string; typeName: string;
  workOrder: WorkOrderFields;
  customerName: string; street: string; place: string;
  startMin: number; endMin: number;
  windowStart: string; windowEnd: string; plannedStart: string; plannedEnd: string;
  sequence: number; durationMin: number;
  lat: number | null; lng: number | null; geocodePrecision: string | null; pinConfirmedAt: string | null;
}

export interface WaitingWorkOrder {
  jobId: string; typeCode: string; typeName: string; workOrder: WorkOrderFields; customerName: string; place: string; durationMin: number; located: boolean;
}

export interface WorkOrderType { code: string; name: string; background: string }

export interface WorkOrdersDay {
  metro: { id: string; name: string; timezone: string };
  date: string;
  technicians: WorkOrderTechnician[];
  cards: WorkOrderCard[];
  waiting: WaitingWorkOrder[];
  /** The types on screen, by name, for the legend and the type filter. */
  types: WorkOrderType[];
}

/** The design's card pastels, given to work order types in code order so a type keeps its color on every day. */
export const TYPE_PASTELS: readonly string[] = [
  "#ffccbc", "#ffebb3", "#d1c3e8", "#c8e6c8", "#b3e4fb", "#eff4c3", "#f8bbd0", "#b2dfdb", "#cfd8dc", "#c5cae9",
];

export function typePastels(codes: readonly string[]): Map<string, string> {
  const sorted = [...codes].sort();
  if (new Set(sorted).size !== sorted.length) throw new Error("Work order type codes must be unique to be given colors");
  return new Map(sorted.map((code, index) => {
    const pastel = TYPE_PASTELS[index % TYPE_PASTELS.length];
    if (pastel === undefined) throw new Error("The work order type palette is empty");
    return [code, pastel];
  }));
}

/** A type's pastel, which must be known: a missing type is a loading mistake, never a default color. */
export function pastelOf(pastels: ReadonlyMap<string, string>, code: string): string {
  const pastel = pastels.get(code);
  if (pastel === undefined) throw new Error(`Work order type ${code} has no color`);
  return pastel;
}

/**
 * The work order status shown for a booked job. Only the statuses the portal keeps are shown: a job still pending or
 * cancelled has none, rather than a guessed one.
 */
export function workOrderStatus(jobStatus: JobStatusName): WorkOrderStatus | null {
  if (jobStatus === "SCHEDULED") return "SCHEDULED";
  if (jobStatus === "COMPLETED") return "COMPLETED";
  return null;
}

/** "Omaha, NE 68102", leaving out the parts an address lacks. */
export function placeLine(city: string, state: string, postalCode: string | null): string {
  return [city, [state, postalCode].filter(Boolean).join(" ")].filter(part => part !== "").join(", ");
}

export function initials(name: string): string {
  const letters = name.trim().split(/\s+/).filter(Boolean).map(word => word[0]?.toUpperCase() ?? "");
  const first = letters[0] ?? "";
  return letters.length > 1 ? `${first}${letters.at(-1) ?? ""}` : first;
}

/** Minutes after local midnight of an instant, in the metro's time zone. */
export function minuteOfDay(instant: string, timeZone: string): number {
  const parts = new Intl.DateTimeFormat("en-US", { timeZone, hourCycle: "h23", hour: "2-digit", minute: "2-digit" }).formatToParts(new Date(instant));
  const hour = Number(parts.find(part => part.type === "hour")?.value);
  const minute = Number(parts.find(part => part.type === "minute")?.value);
  if (!Number.isInteger(hour) || !Number.isInteger(minute)) throw new Error(`Cannot read the local time of ${instant}`);
  return hour * 60 + minute;
}

/** "9:00", "12:30", as the design writes times on cards. */
export function clockLabel(minute: number): string {
  const hour = Math.floor(minute / 60) % 24;
  const shown = hour % 12 === 0 ? 12 : hour % 12;
  return `${shown}:${String(minute % 60).padStart(2, "0")}`;
}

/**
 * The hour gutter's label, as the design writes it: the period on the first hour and at noon ("8 AM", "12 PM"),
 * small "00" minutes on the others ("9 00", "1 00").
 */
export function hourLabel(hour: number, first: boolean): { hour: string; suffix: string } {
  const shown = hour % 12 === 0 ? 12 : hour % 12;
  const period = hour < 12 ? "AM" : "PM";
  return { hour: String(shown), suffix: first || hour === 12 ? period : "00" };
}

/** The schedule's filters: a work order type, a reason code, a status and a city or ZIP; "" leaves one out. */
export interface CardFilters { type: string; reason: string; status: string; place: string }

export const NO_FILTERS: CardFilters = { type: "", reason: "", status: "", place: "" };

export function hasFilters(filters: CardFilters): boolean {
  return filters.type !== "" || filters.reason !== "" || filters.status !== "" || filters.place.trim() !== "";
}

/**
 * Whether a work order passes the filters: its type exactly, any of its reason codes, its status exactly, and its place
 * as typed text. One with no type never matches a type filter, and one with no status (waiting for the calendar, or in
 * a state the portal shows no status for) never matches a status filter.
 */
export function itemMatches(item: { workOrder: Pick<WorkOrderFields, "workOrderType" | "reasonCodes">; status: WorkOrderStatus | null; place: string },
  filters: CardFilters): boolean {
  if (filters.type !== "" && item.workOrder.workOrderType !== filters.type) return false;
  if (filters.reason !== "" && !item.workOrder.reasonCodes.some(code => code.code === filters.reason)) return false;
  if (filters.status !== "" && item.status !== filters.status) return false;
  return placeMatches(item.place, filters.place);
}

export function cardMatches(card: WorkOrderCard, filters: CardFilters): boolean {
  return itemMatches(card, filters);
}

/** Waiting work orders have no status, so a status filter hides them all. */
export function waitingMatches(order: WaitingWorkOrder, filters: CardFilters): boolean {
  return itemMatches({ ...order, status: null }, filters);
}

function placeMatches(place: string, query: string): boolean {
  const wanted = query.trim().toLowerCase();
  return wanted === "" || place.toLowerCase().includes(wanted);
}

/** One technician's day for the routes list: stops in order, and the span from the first start to the last end. */
export function routeOf(technicianId: string, cards: readonly WorkOrderCard[]): { stops: WorkOrderCard[]; startMin: number | null; endMin: number | null } {
  const stops = cards.filter(card => card.technicianId === technicianId)
    .sort((a, b) => a.sequence - b.sequence || a.startMin - b.startMin);
  const first = stops[0], last = stops.at(-1);
  return { stops, startMin: first?.startMin ?? null, endMin: last?.endMin ?? null };
}

/** "6h 15m", "45m", "2h". */
export function durationLabel(minutes: number): string {
  const hours = Math.floor(minutes / 60), rest = minutes % 60;
  if (hours === 0) return `${rest}m`;
  return rest === 0 ? `${hours}h` : `${hours}h ${rest}m`;
}
