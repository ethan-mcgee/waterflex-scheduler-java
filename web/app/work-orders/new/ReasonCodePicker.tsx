"use client";

import { useId } from "react";
import { MAX_REASON_CODES } from "@/lib/reasonCodesCore";
import styles from "./booking.module.css";

export interface ReasonCodeOption {
  code: string;
  name: string;
  estDurationMin: number;
  bufferPct: number;
}

const LABELS = ["Reason code", "Second reason code", "Third reason code"] as const;

/**
 * One to three reason codes, the first required. A code chosen in one place is not offered in another, and a second
 * or third code can be removed again.
 */
export default function ReasonCodePicker({ options, value, onChange, disabled }: {
  options: readonly ReasonCodeOption[];
  /** The codes in order; "" is a place not chosen yet. */
  value: readonly string[];
  onChange: (codes: string[]) => void;
  disabled: boolean;
}) {
  const id = useId();
  const set = (index: number, code: string) => onChange(value.map((item, at) => at === index ? code : item));
  const canAdd = value.length < MAX_REASON_CODES && value.every(code => code !== "");
  return <fieldset className={styles.serviceFieldset}>
    <legend className={styles.label}>Reason codes</legend>
    <p className={styles.fieldHint}>The codes suggest the time allotted. The first one decides which technicians are qualified.</p>
    {options.length === 0 && <div className={styles.error}>No reason codes are active. Add them under Settings, Reason codes.</div>}
    {value.map((code, index) => <div key={index} className={styles.reasonRow}>
      <label className={styles.label} htmlFor={`${id}-${index}`}>{LABELS[index] ?? `Reason code ${index + 1}`}</label>
      <select id={`${id}-${index}`} className={styles.select} value={code} disabled={disabled} required={index === 0}
        onChange={event => set(index, event.target.value)}>
        <option value="">Choose a reason code</option>
        {options.filter(option => option.code === code || !value.includes(option.code)).map(option =>
          <option key={option.code} value={option.code}>{option.name} ({option.code}), {option.estDurationMin} min</option>)}
      </select>
      {index > 0 && <button type="button" className={styles.linkButton} disabled={disabled}
        onClick={() => onChange(value.filter((_, at) => at !== index))}>Remove {LABELS[index]?.toLowerCase() ?? "code"}</button>}
    </div>)}
    {canAdd && <button type="button" className={styles.linkButton} disabled={disabled} onClick={() => onChange([...value, ""])}>
      Add another reason code
    </button>}
  </fieldset>;
}
