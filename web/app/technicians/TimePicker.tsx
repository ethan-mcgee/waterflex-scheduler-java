"use client";

import { Clock } from "lucide-react";
import { useEffect, useId, useRef, useState } from "react";
import { formatTime12, parseTimeText } from "./format";
import styles from "./technicians.module.css";

const SLOT_MINUTES = 15;

function slotsFor(value: number, allowEndOfDay: boolean): number[] {
  const slots = Array.from({ length: (24 * 60) / SLOT_MINUTES }, (_, index) => index * SLOT_MINUTES);
  if (allowEndOfDay || value === 1440) slots.push(1440);
  if (!slots.includes(value)) { slots.push(value); slots.sort((a, b) => a - b); }
  return slots;
}

export default function TimePicker({ value, onChange, label, allowEndOfDay = false }: {
  value: number;
  onChange: (minutes: number) => void;
  label: string;
  allowEndOfDay?: boolean;
}) {
  const [open, setOpen] = useState(false);
  const [text, setText] = useState(formatTime12(value));
  const focused = useRef(false);
  const wrap = useRef<HTMLDivElement>(null);
  const selected = useRef<HTMLButtonElement>(null);
  const listId = useId();

  useEffect(() => { if (!focused.current) setText(formatTime12(value)); }, [value]);
  useEffect(() => {
    if (!open) return;
    selected.current?.scrollIntoView({ block: "center" });
    const close = (event: PointerEvent) => { if (!(event.target instanceof Node) || !wrap.current?.contains(event.target)) setOpen(false); };
    document.addEventListener("pointerdown", close);
    return () => document.removeEventListener("pointerdown", close);
  }, [open]);

  function choose(minutes: number) { onChange(minutes); setText(formatTime12(minutes)); setOpen(false); }

  return (
    <div ref={wrap} className={styles.pickerWrap} style={{ width: 118 }}>
      <div className={styles.pickerInput}>
        <input
          aria-label={label}
          role="combobox"
          aria-autocomplete="none"
          aria-controls={listId}
          aria-expanded={open}
          value={text}
          onFocus={() => { focused.current = true; }}
          onBlur={() => { focused.current = false; setText(formatTime12(value)); }}
          onClick={() => setOpen(true)}
          onKeyDown={(event) => { if (event.key === "Escape") setOpen(false); else if (event.key === "ArrowDown") setOpen(true); }}
          onChange={(event) => {
            setText(event.target.value);
            const parsed = parseTimeText(event.target.value);
            if (parsed != null) onChange(parsed);
          }}
        />
        <button type="button" className={styles.pickerIcon} aria-hidden="true" tabIndex={-1} onClick={() => setOpen(current => !current)}>
          <Clock size={14} aria-hidden="true" />
        </button>
      </div>
      {open && (
        <div id={listId} className={styles.timePopover} role="listbox" aria-label={label}>
          {slotsFor(value, allowEndOfDay).map(minutes => (
            <button
              key={minutes}
              ref={minutes === value ? selected : undefined}
              type="button"
              role="option"
              aria-selected={minutes === value}
              className={`${styles.timeOption} ${minutes === value ? styles.timeOptionSelected : ""}`}
              onClick={() => choose(minutes)}
            >
              {formatTime12(minutes)}{minutes === 1440 ? " (end of day)" : ""}
            </button>
          ))}
        </div>
      )}
    </div>
  );
}
