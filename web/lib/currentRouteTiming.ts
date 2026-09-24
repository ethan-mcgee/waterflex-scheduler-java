import { z } from "zod";
import { instant, text } from "./contracts";

const segment = z.object({ departure: instant, returnedAt: instant, appointmentIds: z.array(text).min(1) });
const saved = z.object({ format: z.literal(1), scheduleVersion: z.int().nonnegative(), routingIdentity: text.nullable(), segments: z.array(segment) });
export type CurrentRouteTiming = { status: "AVAILABLE"; segments: z.infer<typeof segment>[] } | { status: "UNAVAILABLE" | "INVALID" };

export function currentRouteTiming(raw: unknown, version: number | null,
  appointments: Array<{ id: string; plannedStart: Date; plannedEnd: Date }>): CurrentRouteTiming {
  if (raw === null || version === null) return { status: "UNAVAILABLE" };
  if (!Number.isSafeInteger(version) || version < 0) return { status: "INVALID" };
  const parsed = saved.safeParse(raw);
  if (!parsed.success) return { status: "INVALID" };
  const value = parsed.data;
  if (value.scheduleVersion !== version) return { status: "UNAVAILABLE" };
  if (value.segments.length > 0 && value.routingIdentity === null) return { status: "INVALID" };
  const expected = new Map(appointments.map(appointment => [appointment.id, appointment]));
  if (expected.size !== appointments.length || appointments.some(appointment => !Number.isFinite(appointment.plannedStart.getTime()) ||
    !Number.isFinite(appointment.plannedEnd.getTime()) || appointment.plannedStart >= appointment.plannedEnd)) return { status: "INVALID" };
  const seen = new Set<string>();
  let previous = -Infinity;
  for (const item of value.segments) {
    const start = Date.parse(item.departure), end = Date.parse(item.returnedAt);
    if (start >= end || start < previous) return { status: "INVALID" };
    let visitEnd = start;
    for (const id of item.appointmentIds) {
      const appointment = expected.get(id);
      if (!appointment || seen.has(id) || appointment.plannedStart.getTime() < visitEnd || appointment.plannedEnd.getTime() > end)
        return { status: "INVALID" };
      visitEnd = appointment.plannedEnd.getTime(); seen.add(id);
    }
    previous = end;
  }
  return seen.size === expected.size ? { status: "AVAILABLE", segments: value.segments } : { status: "INVALID" };
}
