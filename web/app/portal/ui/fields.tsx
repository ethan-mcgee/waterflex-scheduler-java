"use client";

import { useId } from "react";
import styles from "./ui.module.css";

export interface FieldOption { value: string; label: string }

/** An outlined select whose label sits inside while empty and floats onto the border once a value is chosen. */
export function SelectField({ label, value, options, onChange, disabled = false, hint, emptyLabel = "All", name }: {
  label: string; value: string; options: readonly FieldOption[]; onChange?: (value: string) => void; disabled?: boolean; hint?: string;
  emptyLabel?: string; name?: string;
}) {
  const id = useId();
  const filled = value !== "";
  return (
    <div className={`${styles.field} ${filled ? styles.filled : ""} ${disabled ? styles.disabled : ""}`} title={hint}>
      <label htmlFor={id}>{label}</label>
      <select id={id} name={name} value={value} disabled={disabled} onChange={event => onChange?.(event.target.value)} title={hint}>
        <option value="">{filled ? emptyLabel : ""}</option>
        {options.map(option => <option key={option.value} value={option.value}>{option.label}</option>)}
      </select>
    </div>
  );
}

/** An outlined text input with the same floating label. */
export function TextField({ label, value, onChange, disabled = false, placeholder, name }: {
  label: string; value: string; onChange?: (value: string) => void; disabled?: boolean; placeholder?: string; name?: string;
}) {
  const id = useId();
  return (
    <div className={`${styles.field} ${value !== "" ? styles.filled : ""} ${disabled ? styles.disabled : ""}`}>
      <label htmlFor={id}>{label}</label>
      <input id={id} name={name} value={value} disabled={disabled} placeholder={placeholder} onChange={event => onChange?.(event.target.value)} />
    </div>
  );
}
