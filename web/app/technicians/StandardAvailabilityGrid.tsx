"use client";

import { DAY_NAMES, formatMinute } from "./format";
import { fromMinutes, toMinutes } from "./format";
import styles from "./technicians.module.css";

export interface StandardDay {
  dayOfWeek: number;
  available: boolean;
  startMin: number;
  endMin: number;
}

export function defaultStandardWeek(shiftStartMin: number, shiftEndMin: number): StandardDay[] {
  return DAY_NAMES.map((_, dayOfWeek) => ({
    dayOfWeek,
    available: dayOfWeek >= 1 && dayOfWeek <= 5,
    startMin: shiftStartMin,
    endMin: shiftEndMin,
  }));
}

export default function StandardAvailabilityGrid({ days, onToggle, onHours }: { days: StandardDay[]; onToggle?: (dayOfWeek: number) => void;
  onHours?: (dayOfWeek: number, field: "startMin" | "endMin", value: number) => void }) {
  return (
    <div className={styles.dayGrid}>
      {days.map((day) => (
        <div
          key={day.dayOfWeek}
          className={`${styles.dayCard} ${day.available ? "" : styles.dayCardOff}`}
        >
          <label><input type="checkbox" checked={day.available} disabled={!onToggle} onChange={() => onToggle?.(day.dayOfWeek)} /> {DAY_NAMES[day.dayOfWeek]}</label>
          <span className={`${styles.dayCardPill} ${day.available ? "" : styles.dayCardPillOff}`}>
            {day.available ? `${formatMinute(day.startMin)}-${formatMinute(day.endMin)}` : "Off"}
          </span>
          {day.available && onHours && <span style={{ display: "flex", flexDirection: "column", gap: 4, marginTop: 6 }}>
            <input aria-label={`${DAY_NAMES[day.dayOfWeek]} start`} type="time" value={fromMinutes(day.startMin)} onChange={event => { const value = toMinutes(event.target.value); if (value != null) onHours(day.dayOfWeek, "startMin", value); }} />
            <input aria-label={`${DAY_NAMES[day.dayOfWeek]} end`} type="time" value={fromMinutes(day.endMin)} onChange={event => { const value = toMinutes(event.target.value); if (value != null) onHours(day.dayOfWeek, "endMin", value); }} />
          </span>}
        </div>
      ))}
    </div>
  );
}
