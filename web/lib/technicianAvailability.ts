import { addCalendarDays, todayInTz } from "./date";
import { standardWeek } from "./contracts";
import type { z } from "zod";

export type AvailabilityDay = z.infer<typeof standardWeek>[number];
export type AvailabilityVersion = { effectiveDate: Date; days: AvailabilityDay[] };

export function initialAvailability(shiftStartMin: number, shiftEndMin: number) {
  if (!Number.isInteger(shiftStartMin) || !Number.isInteger(shiftEndMin) || shiftStartMin < 0 || shiftEndMin > 1440 || shiftStartMin >= shiftEndMin)
    throw new Error("Invalid initial technician shift hours");
  return { create: { effectiveDate: new Date("1900-01-01T00:00:00Z"), days: { create: Array.from({ length: 7 }, (_, dayOfWeek) => ({
    dayOfWeek, available: dayOfWeek >= 1 && dayOfWeek <= 5,
    shiftStartMin: dayOfWeek >= 1 && dayOfWeek <= 5 ? shiftStartMin : null,
    shiftEndMin: dayOfWeek >= 1 && dayOfWeek <= 5 ? shiftEndMin : null,
  })) } } };
}

export function dayOfWeek(date: string): number { return new Date(`${date}T00:00:00Z`).getUTCDay(); }

export function nextTemplateEffectiveDate(now = new Date()): string {
  let date = addCalendarDays(todayInTz("America/Chicago", now), 1);
  let weekdays = 0;
  while (weekdays < 10) {
    const weekday = dayOfWeek(date);
    if (weekday >= 1 && weekday <= 5) weekdays++;
    if (weekdays < 10) date = addCalendarDays(date, 1);
  }
  return addCalendarDays(date, 1);
}

export function validateVersions(versions: AvailabilityVersion[]): AvailabilityVersion[] {
  if (versions.length === 0) throw new Error("Technician weekly availability is missing");
  for (const version of versions) standardWeek.parse(version.days.map(day => ({
    dayOfWeek: day.dayOfWeek, available: day.available,
    shiftStartMin: day.shiftStartMin, shiftEndMin: day.shiftEndMin,
  })));
  return [...versions].sort((a, b) => a.effectiveDate.getTime() - b.effectiveDate.getTime());
}

export function resolveWeeklyDay(versions: AvailabilityVersion[], date: string): AvailabilityDay {
  const effective = validateVersions(versions).filter(v => v.effectiveDate.toISOString().slice(0, 10) <= date).at(-1);
  if (!effective) throw new Error(`Technician weekly availability is missing for ${date}`);
  const day = effective.days.find(v => v.dayOfWeek === dayOfWeek(date));
  if (!day) throw new Error(`Technician weekly availability is incomplete for ${date}`);
  return day;
}
