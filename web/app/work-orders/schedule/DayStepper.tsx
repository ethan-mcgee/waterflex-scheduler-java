"use client";

import { useEffect, useRef } from "react";
import { useRouter } from "next/navigation";
import { CalendarDays, ChevronLeft, ChevronRight } from "lucide-react";
import { addCalendarDays, todayInTz } from "@/lib/date";
import { scheduleHref, type ScheduleViewName } from "./scheduleHref";
import styles from "./schedule.module.css";

const DATE_KEY = /^\d{4}-\d{2}-\d{2}$/;

/** "Tuesday, 10/15/24", as the design heads the day. */
export function dayHeading(date: string): string {
  const instant = new Date(`${date}T12:00:00.000Z`);
  const weekday = new Intl.DateTimeFormat("en-US", { weekday: "long", timeZone: "UTC" }).format(instant);
  const short = new Intl.DateTimeFormat("en-US", { month: "2-digit", day: "2-digit", year: "2-digit", timeZone: "UTC" }).format(instant);
  return `${weekday}, ${short}`;
}

/**
 * The day on screen and the ways to change it: a day back or forward, a date from the picker, or today. Every view
 * uses it, so the day reads and moves the same way on the calendar, the map and the two side by side.
 */
export default function DayStepper({ metroId, view, date, timezone }: { metroId: string; view: ScheduleViewName; date: string; timezone: string }) {
  const router = useRouter();
  // The day a click asked for. Navigation is asynchronous, so a second click before the page arrives counts from the
  // first click's day rather than from the one still on screen; quick clicks add up instead of repeating.
  const target = useRef(date);
  useEffect(() => { target.current = date; }, [date]);
  const go = (next: string) => { target.current = next; router.push(scheduleHref({ metroId, view, date: next })); };

  return (
    <div className={styles.dayStepper}>
      <button type="button" className={styles.stepButton} aria-label="Previous day" title="Previous day"
        onClick={() => go(addCalendarDays(target.current, -1))}><ChevronLeft size={20} aria-hidden /></button>
      <h2 className={styles.dayTitle} data-schedule-date={date}>{dayHeading(date)}</h2>
      <button type="button" className={styles.stepButton} aria-label="Next day" title="Next day"
        onClick={() => go(addCalendarDays(target.current, 1))}><ChevronRight size={20} aria-hidden /></button>
      {/* The browser's own date picker, opened from the calendar button; the field itself stays out of sight. */}
      <label className={styles.pickDate} title="Go to date">
        <CalendarDays size={18} aria-hidden />
        <span className={styles.srOnly}>Go to date</span>
        <input type="date" value={date}
          onClick={event => { try { event.currentTarget.showPicker(); } catch { /* Older browsers open it themselves. */ } }}
          onChange={event => { if (DATE_KEY.test(event.target.value)) go(event.target.value); }} />
      </label>
      <button type="button" className={styles.todayButton} onClick={() => go(todayInTz(timezone))}>Today</button>
    </div>
  );
}
