import { date as dateContract, required } from "./contracts";

/** The zone's UTC offset at an instant, in milliseconds (local minus UTC). */
export function zoneOffsetMs(instant: number, timeZone: string): number {
  const parts = new Intl.DateTimeFormat("en-US", { timeZone, hourCycle: "h23", year: "numeric", month: "2-digit", day: "2-digit",
    hour: "2-digit", minute: "2-digit", second: "2-digit" }).formatToParts(new Date(instant));
  const get = (type: string) => Number(required(parts.find(part => part.type === type)?.value, `Zoned ${type}`));
  const shown = Date.UTC(get("year"), get("month") - 1, get("day"), get("hour"), get("minute"), get("second"));
  return shown - (instant - (instant % 1000 + 1000) % 1000);
}

/**
 * The instant of `minute` minutes after local midnight on `date` in `timeZone`, exactly as the scheduler's
 * ScheduleCutoff.localMinute resolves it: in a repeated hour a start takes the earlier instant and an end the later
 * one; in a skipped hour the time moves forward by the gap. `minute` may be 1440, the end of the day.
 */
export function localMinute(date: string, minute: number, endBoundary: boolean, timeZone: string): Date {
  dateContract.parse(date);
  if (!Number.isInteger(minute) || minute < 0 || minute > 1440) throw new Error(`Invalid local minute ${minute}`);
  const [year, month, day] = date.split("-").map(Number);
  const wall = Date.UTC(required(year), required(month) - 1, required(day)) + minute * 60_000;
  const before = zoneOffsetMs(wall - 86_400_000, timeZone);
  const after = zoneOffsetMs(wall + 86_400_000, timeZone);
  // Offsets that really produce this wall time, earlier instant (larger offset) first, like ZoneRules.getValidOffsets.
  const valid = [...new Set([before, after])].filter(offset => zoneOffsetMs(wall - offset, timeZone) === offset).sort((a, b) => b - a);
  if (valid.length === 0) return new Date(wall - before);
  return new Date(wall - required(endBoundary ? valid.at(-1) : valid[0]));
}
