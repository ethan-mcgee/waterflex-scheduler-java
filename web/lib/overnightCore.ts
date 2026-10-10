import { z } from "zod";
import { frozen } from "./apiTimeOffCore";
import { addCalendarDays, todayInTz } from "./date";
import { localMinute } from "./zonedTime";

/**
 * The pure rules of the portal's overnight optimization, safe for the browser: a client's run times, when a scheduled
 * run is due, and which days a run proposes. Run times are wall-clock minutes in the same zone as shift minutes.
 */

export const OVERNIGHT_TIME_ZONE = "America/Chicago";
export const MAX_RUN_TIMES = 8;
/** A scheduled run still starts this long after its time, for a worker that was briefly down; later it is skipped. */
export const SCHEDULE_GRACE_MS = 60 * 60_000;
/** Weekdays a run covers, as the scheduler's own overnight batch does. */
export const OVERNIGHT_WEEKDAYS = 10;

const clock = /^([01][0-9]|2[0-3]):([0-5][0-9])$/;

/** "HH:MM" as minutes after local midnight. */
export function minuteOf(time: string): number {
  const match = clock.exec(time);
  if (match === null) throw new Error(`Invalid run time ${time}`);
  return Number(match[1]) * 60 + Number(match[2]);
}

export function timeOf(minute: number): string {
  if (!Number.isInteger(minute) || minute < 0 || minute > 1439) throw new Error(`Invalid run minute ${minute}`);
  return `${String(Math.floor(minute / 60)).padStart(2, "0")}:${String(minute % 60).padStart(2, "0")}`;
}

/** The run times a client saves: none means manual runs only. */
export const runTimes = z.object({
  times: z.array(z.string().regex(clock, "Use a 24-hour time such as 02:00")).max(MAX_RUN_TIMES)
    .refine(times => new Set(times).size === times.length, "Each run time can be listed once"),
}).strict();

/**
 * The latest scheduled run time at or before `now` that is no more than the grace period old, or null. Times are
 * local; in a repeated hour a time runs once, at its first occurrence, and in a skipped hour it moves forward.
 */
export function dueSlot(minutes: readonly number[], now: Date, graceMs = SCHEDULE_GRACE_MS): Date | null {
  const today = todayInTz(OVERNIGHT_TIME_ZONE, now);
  let due: Date | null = null;
  for (const date of [addCalendarDays(today, -1), today])
    for (const minute of minutes) {
      const slot = localMinute(date, minute, false, OVERNIGHT_TIME_ZONE);
      const age = now.getTime() - slot.getTime();
      if (age >= 0 && age <= graceMs && (due === null || slot > due)) due = slot;
    }
  return due;
}

function weekday(date: string): boolean {
  const day = new Date(`${date}T00:00:00Z`).getUTCDay();
  return day >= 1 && day <= 5;
}

/**
 * The days an overnight run proposes: from today, every day not yet frozen by the 6 a.m. cutoff, until ten weekdays
 * are covered. Weekend days in between are included.
 */
export function overnightDates(now: Date): string[] {
  const dates: string[] = [];
  let date = todayInTz(OVERNIGHT_TIME_ZONE, now);
  let weekdays = 0;
  while (weekdays < OVERNIGHT_WEEKDAYS) {
    if (!frozen(date, now, OVERNIGHT_TIME_ZONE)) {
      dates.push(date);
      if (weekday(date)) weekdays++;
    }
    date = addCalendarDays(date, 1);
  }
  return dates;
}
