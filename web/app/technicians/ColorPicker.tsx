"use client";

import { useState } from "react";
import { TECHNICIAN_COLOR_PALETTE, hexToHsl, hslToHex } from "@/lib/technicianColor";
import styles from "./technicians.module.css";

export default function ColorPicker({ value, onChange }: { value: string; onChange: (hex: string) => void }) {
  const [open, setOpen] = useState(false);
  const [hue, setHue] = useState(() => hexToHsl(value).h);
  const [sat, setSat] = useState(() => hexToHsl(value).s);
  const [light, setLight] = useState(() => hexToHsl(value).l);

  function toggleOpen() {
    if (open) { setOpen(false); return; }
    const hsl = hexToHsl(value);
    setHue(hsl.h); setSat(hsl.s); setLight(hsl.l); setOpen(true);
  }

  function pickFromBox(event: React.MouseEvent<HTMLDivElement>) {
    const rect = event.currentTarget.getBoundingClientRect();
    const x = Math.min(Math.max(event.clientX - rect.left, 0), rect.width);
    const y = Math.min(Math.max(event.clientY - rect.top, 0), rect.height);
    const nextSat = Math.round((x / rect.width) * 100);
    const nextLight = Math.round(100 - (y / rect.height) * 100);
    setSat(nextSat); setLight(nextLight);
    onChange(hslToHex(hue, nextSat, nextLight));
  }

  function changeHue(event: React.ChangeEvent<HTMLInputElement>) {
    const nextHue = Number(event.target.value);
    setHue(nextHue);
    onChange(hslToHex(nextHue, sat, light));
  }

  const svBackground = `linear-gradient(to top, #000, rgba(0,0,0,0)), linear-gradient(to right, #fff, ${hslToHex(hue, 100, 50)})`;

  return (
    <div className={styles.colorRow}>
      {TECHNICIAN_COLOR_PALETTE.map((hex) => (
        <button
          key={hex}
          type="button"
          aria-label={`Use color ${hex}`}
          aria-pressed={value === hex}
          className={`${styles.swatch} ${value === hex ? styles.swatchSelected : ""}`}
          style={{ background: hex }}
          onClick={() => { onChange(hex); setOpen(false); }}
        />
      ))}
      <button
        type="button"
        aria-label="Custom color"
        aria-expanded={open}
        className={`${styles.customTrigger} ${open ? styles.customTriggerOpen : ""}`}
        onClick={toggleOpen}
      >
        <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2} strokeLinecap="round" strokeLinejoin="round">
          <path d="m2 22 1-1h3l9-9" />
          <path d="M3 21v-3l9-9" />
          <path d="m15 6 3.4-3.4a2.1 2.1 0 1 1 3 3L18 9l.4.4a2.1 2.1 0 1 1-3 3l-3.8-3.8a2.1 2.1 0 1 1 3-3l.4.4Z" />
        </svg>
      </button>
      {open && (
        <div className={styles.customPopover}>
          <div className={styles.svBox} style={{ background: svBackground }} onClick={pickFromBox}>
            <div className={styles.svIndicator} style={{ left: `${sat}%`, top: `${100 - light}%` }} />
          </div>
          <input type="range" min={0} max={360} value={hue} onChange={changeHue} className={styles.hueSlider} />
          <div className={styles.previewRow}>
            <span className={styles.previewSwatch}><span style={{ background: value }} />{value}</span>
            <button type="button" className={styles.customTrigger} style={{ width: "auto", borderRadius: 6, padding: "5px 10px", fontSize: "11.5px", fontWeight: 650 }} onClick={() => setOpen(false)}>Done</button>
          </div>
        </div>
      )}
    </div>
  );
}
