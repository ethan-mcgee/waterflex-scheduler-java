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
