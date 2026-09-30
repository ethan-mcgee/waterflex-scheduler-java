"use client";

import { CalendarDays } from "lucide-react";
import { useEffect, useRef, useState } from "react";
import { maskUsDate, parseUsDate } from "@/lib/date";
import styles from "./technicians.module.css";

const MONTHS = ["January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December"];
const WEEKDAYS = ["Su", "Mo", "Tu", "We", "Th", "Fr", "Sa"];

export function dateFieldError(text: string, min: string): string {
  if (!text) return "";
  const iso = parseUsDate(text);
  if (!iso) return "Enter a real date as MM/DD/YYYY.";
  return iso < min ? "Date must be today or later." : "";
}

function isoFor(year: number, month: number, day: number): string {
  return `${year}-${String(month + 1).padStart(2, "0")}-${String(day).padStart(2, "0")}`;
}

export default function DateField({ id, value, onChange, min, today }: {
  id: string;
  value: string;
  onChange: (text: string) => void;
  min: string;
  today: string;
}) {
  const selected = parseUsDate(value);
  const anchor = selected ?? min;
  const [open, setOpen] = useState(false);
  const [month, setMonth] = useState({ year: Number(anchor.slice(0, 4)), month: Number(anchor.slice(5, 7)) - 1 });
  const wrap = useRef<HTMLDivElement>(null);
  const error = dateFieldError(value, min);

  useEffect(() => {
    if (!open) return;
    const close = (event: PointerEvent) => { if (!(event.target instanceof Node) || !wrap.current?.contains(event.target)) setOpen(false); };
    document.addEventListener("pointerdown", close);
    return () => document.removeEventListener("pointerdown", close);
  }, [open]);

  function toggle() {
    if (!open) setMonth({ year: Number(anchor.slice(0, 4)), month: Number(anchor.slice(5, 7)) - 1 });
    setOpen(current => !current);
  }
  function shiftMonth(delta: number) {
    setMonth(current => { const next = new Date(Date.UTC(current.year, current.month + delta, 1)); return { year: next.getUTCFullYear(), month: next.getUTCMonth() }; });
  }
  function pick(iso: string) { onChange(`${iso.slice(5, 7)}/${iso.slice(8, 10)}/${iso.slice(0, 4)}`); setOpen(false); }

  const leading = new Date(Date.UTC(month.year, month.month, 1)).getUTCDay();
  const length = new Date(Date.UTC(month.year, month.month + 1, 0)).getUTCDate();

  return (
    <div ref={wrap} className={styles.pickerWrap} style={{ display: "block" }}>
      <div className={`${styles.pickerInput} ${error ? styles.pickerInputInvalid : ""}`}>
        <input id={id} inputMode="numeric" placeholder="MM/DD/YYYY" autoComplete="off" value={value}
          aria-invalid={error ? true : undefined} aria-describedby={error ? `${id}-error` : undefined}
          onChange={(event) => onChange(maskUsDate(event.target.value))} />
        <button type="button" className={styles.pickerIcon} aria-label="Open calendar" aria-expanded={open} onClick={toggle}>
          <CalendarDays size={16} aria-hidden="true" />
        </button>
      </div>
      {error && <p id={`${id}-error`} role="alert" style={{ margin: "6px 0 0", fontSize: 11.5, color: "var(--danger)" }}>{error}</p>}
      {open && (
        <div className={styles.calendarPopover} role="dialog" aria-label="Choose a date">
          <div className={styles.calendarHead}>
            <button type="button" className={styles.calendarNav} aria-label="Previous month" onClick={() => shiftMonth(-1)}>&lsaquo;</button>
            <span>{MONTHS[month.month]} {month.year}</span>
            <button type="button" className={styles.calendarNav} aria-label="Next month" onClick={() => shiftMonth(1)}>&rsaquo;</button>
          </div>
          <div className={styles.calendarGrid}>
            {WEEKDAYS.map(day => <span key={day} className={styles.calendarWeekday}>{day}</span>)}
            {Array.from({ length: leading }, (_, index) => <span key={`blank-${index}`} />)}
            {Array.from({ length }, (_, index) => {
              const iso = isoFor(month.year, month.month, index + 1);
              return (
                <button key={iso} type="button" disabled={iso < min} aria-label={iso}
                  className={`${styles.calendarDay} ${iso === selected ? styles.calendarDaySelected : ""}`} onClick={() => pick(iso)}>
                  {index + 1}
                </button>
              );
            })}
          </div>
          <div className={styles.calendarFoot}>
            <button type="button" className={styles.linkButton} disabled={today < min} onClick={() => pick(today)}>Today</button>
            <button type="button" className={styles.linkButton} style={{ color: "var(--muted)" }} onClick={() => { onChange(""); setOpen(false); }}>Clear</button>
          </div>
        </div>
      )}
    </div>
  );
}
