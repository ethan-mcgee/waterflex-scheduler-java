export const DAY_NAMES = ["Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"];

export function formatMinute(value: number): string {
  if (value === 1440) return "12a";
  const hour = Math.floor(value / 60), minute = value % 60, period = hour >= 12 ? "p" : "a";
  return minute === 0 ? `${hour % 12 || 12}${period}` : `${hour % 12 || 12}:${String(minute).padStart(2, "0")}${period}`;
}

export function toMinutes(value: string): number | null {
  if (!/^\d{2}:\d{2}$/.test(value)) return null;
  const [hours, minutes] = value.split(":").map(Number);
  if (hours == null || minutes == null || hours > 23 || minutes > 59) return null;
  return hours * 60 + minutes;
}

export function fromMinutes(value: number | null): string {
  return value == null ? "" : `${String(Math.floor(value / 60)).padStart(2, "0")}:${String(value % 60).padStart(2, "0")}`;
}

export function formatInterval(start: number | null, end: number | null): string | null {
  return start != null && end != null && start >= 0 && end <= 1440 && start < end ? `${formatMinute(start)} to ${formatMinute(end)}` : null;
}

export const FULL_DAY_NAMES = ["Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday"];

export function formatTime12(value: number): string {
  if (value === 1440) return "12:00 AM";
  const hour = Math.floor(value / 60), minute = value % 60;
  return `${hour % 12 || 12}:${String(minute).padStart(2, "0")} ${hour >= 12 ? "PM" : "AM"}`;
}

// Accepts "8:30 AM", "8am", "17:00" and "24:00". Anything else is not a time yet.
export function parseTimeText(text: string): number | null {
  const value = text.trim();
  const twelve = /^(\d{1,2})(?::(\d{2}))?\s*([ap])\.?m?\.?$/i.exec(value);
  if (twelve) {
    const hour = Number(twelve[1]), minute = Number(twelve[2] ?? "0");
    if (hour < 1 || hour > 12 || minute > 59) return null;
    return ((hour % 12) + (twelve[3]?.toLowerCase() === "p" ? 12 : 0)) * 60 + minute;
  }
  const twentyFour = /^(\d{1,2}):(\d{2})$/.exec(value);
  if (!twentyFour) return null;
  const hour = Number(twentyFour[1]), minute = Number(twentyFour[2]);
  if (minute > 59) return null;
  if (hour === 24 && minute === 0) return 1440;
  return hour <= 23 ? hour * 60 + minute : null;
}
