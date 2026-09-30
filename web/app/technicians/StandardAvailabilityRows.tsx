"use client";

import ui from "../components/ui.module.css";
import { DAY_NAMES, FULL_DAY_NAMES } from "./format";
import type { StandardDay } from "./StandardAvailabilityGrid";
import TimePicker from "./TimePicker";
import styles from "./technicians.module.css";

export function weeklyHours(days: StandardDay[]): number {
  return days.filter(day => day.available && day.startMin < day.endMin).reduce((sum, day) => sum + (day.endMin - day.startMin) / 60, 0);
}

function formatHours(hours: number): string {
  return `${Number.isInteger(hours) ? hours : hours.toFixed(1)} hrs / week`;
}

export default function StandardAvailabilityRows({ days, onToggle, onHours, onApplyMonday }: {
  days: StandardDay[];
  onToggle: (dayOfWeek: number) => void;
  onHours: (dayOfWeek: number, field: "startMin" | "endMin", value: number) => void;
  onApplyMonday: () => void;
}) {
  const ordered = [...days].sort((a, b) => a.dayOfWeek - b.dayOfWeek);
  const monday = ordered.find(day => day.dayOfWeek === 1);
  return (
    <div className={styles.availPanel}>
      <div className={styles.availHead}>
        <p className={ui.sectionLabel} style={{ margin: 0 }}>Edit weekly availability</p>
        <span className={styles.availTotal}>{formatHours(weeklyHours(days))}</span>
      </div>
      <div className={styles.availRows}>
        {ordered.map(day => {
          const short = DAY_NAMES[day.dayOfWeek], full = FULL_DAY_NAMES[day.dayOfWeek];
          return (
            <div key={day.dayOfWeek} className={styles.availRow}>
              <label className={styles.switch}>
                <input type="checkbox" role="switch" aria-label={full} checked={day.available} onChange={() => onToggle(day.dayOfWeek)} />
                <span className={styles.switchTrack} aria-hidden="true" />
              </label>
              <span className={`${styles.availDay} ${day.available ? "" : styles.availDayOff}`}>{full}</span>
              {day.available ? (
                <>
                  <TimePicker label={`${short} start`} value={day.startMin} onChange={value => onHours(day.dayOfWeek, "startMin", value)} />
                  <span className={styles.availTo}>to</span>
                  <TimePicker label={`${short} end`} value={day.endMin} allowEndOfDay onChange={value => onHours(day.dayOfWeek, "endMin", value)} />
                </>
              ) : <span className={styles.availOffText}>Unavailable</span>}
            </div>
          );
        })}
      </div>
      {monday?.available && <button type="button" className={styles.linkButton} onClick={onApplyMonday}>Apply Monday&apos;s hours to all working days</button>}
    </div>
  );
}
