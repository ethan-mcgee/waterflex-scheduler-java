"use client";

import { useState, type CSSProperties } from "react";
import Avatar from "../components/Avatar";
import ui from "../components/ui.module.css";
import ColorPicker from "./ColorPicker";
import styles from "./technicians.module.css";
import StandardAvailabilityGrid, { type StandardDay } from "./StandardAvailabilityGrid";

export interface ProfileDraft {
  name: string;
  email: string;
  phone: string;
  bio: string;
  color: string;
}

export default function TechnicianProfileModal({ initial, currentDays, pending, onClose, onSave, onSaveAvailability, busy, error }: {
  initial: ProfileDraft;
  currentDays: StandardDay[];
  pending: { effectiveDate: string; days: StandardDay[] } | null;
  onClose: () => void;
  onSave: (draft: ProfileDraft) => void;
  onSaveAvailability: (days: StandardDay[]) => void;
  busy: boolean;
  error: string;
}) {
  const [name, setName] = useState(initial.name);
  const [email, setEmail] = useState(initial.email);
  const [phone, setPhone] = useState(initial.phone);
  const [bio, setBio] = useState(initial.bio);
  const [color, setColor] = useState(initial.color);
  const [days, setDays] = useState<StandardDay[]>(pending?.days ?? currentDays);

  const invalid = !name.trim();
  const invalidDays = !days.some(day => day.available) || days.some(day => day.available && day.startMin >= day.endMin);

  return (
    <div className={styles.modalBackdrop} role="presentation" onMouseDown={onClose}>
      <div className={styles.modal} role="dialog" aria-modal="true" aria-labelledby="profile-modal-title" onMouseDown={(event) => event.stopPropagation()}>
        <h2 id="profile-modal-title" className={styles.modalTitle}>Edit profile</h2>
        <p className={styles.modalSubtitle}>Update contact details and the color shown on the dispatch board and schedule.</p>

        <div className={styles.profileHead}>
          <Avatar name={name || initial.name} color={color} size={48} />
          <div style={{ flex: 1 }}>
            <label className={ui.sectionLabel} htmlFor="profile-name">Name <span className={styles.required}>*</span></label>
            <input id="profile-name" value={name} onChange={(event) => setName(event.target.value)} style={inputStyle} />
          </div>
        </div>

        <div className={styles.fieldGrid}>
          <div>
            <label className={ui.sectionLabel} htmlFor="profile-email">Email</label>
            <input id="profile-email" type="email" value={email} placeholder="Not provided" onChange={(event) => setEmail(event.target.value)} style={inputStyle} />
          </div>
          <div>
            <label className={ui.sectionLabel} htmlFor="profile-phone">Phone</label>
            <input id="profile-phone" value={phone} placeholder="Not provided" onChange={(event) => setPhone(event.target.value)} style={inputStyle} />
          </div>
        </div>

        <label className={ui.sectionLabel} htmlFor="profile-bio">Short bio <span className={styles.optional}>(optional)</span></label>
        <textarea id="profile-bio" value={bio} rows={4} placeholder="Not provided" onChange={(event) => setBio(event.target.value)} style={{ ...inputStyle, width: "100%", resize: "vertical", marginBottom: 18 }} />

        <label className={ui.sectionLabel}>Color</label>
        <ColorPicker value={color} onChange={setColor} />

        <p className={ui.sectionLabel}>Current weekly availability</p>
        <StandardAvailabilityGrid days={currentDays} />
        {pending && <p className={styles.message}>Pending replacement starts {pending.effectiveDate}.</p>}
        <p className={ui.sectionLabel}>Edit weekly availability</p>
        <StandardAvailabilityGrid days={days} onToggle={dayOfWeek => setDays(current => current.map(day => day.dayOfWeek === dayOfWeek ? { ...day, available: !day.available } : day))}
          onHours={(dayOfWeek, field, value) => setDays(current => current.map(day => day.dayOfWeek === dayOfWeek ? { ...day, [field]: value } : day))} />
        <p className={styles.message}>A weekly change starts the calendar day after the current booking horizon ends. Saving again replaces the pending change.</p>
        {error && <p role="alert" className={styles.errorText}>{error}</p>}
        {invalid && <p className={styles.errorText}>Name is required.</p>}
        {invalidDays && <p className={styles.errorText}>Choose at least one day with valid hours.</p>}
        <div className={styles.modalActions}>
          <button type="button" className={ui.button} onClick={onClose}>Cancel</button>
          <button
            type="button"
            className={`${ui.button} ${ui.buttonBrand}`}
            disabled={invalid || busy}
            onClick={() => { if (!invalid) onSave({ name: name.trim(), email: email.trim(), phone: phone.trim(), bio: bio.trim(), color }); }}
          >
            Save changes
          </button>
          <button type="button" className={`${ui.button} ${ui.buttonBrand}`} disabled={invalidDays || busy} onClick={() => onSaveAvailability(days)}>Save weekly availability</button>
        </div>
      </div>
    </div>
  );
}

const inputStyle: CSSProperties = {
  width: "100%",
  border: "1px solid var(--line-strong)",
  borderRadius: 8,
  padding: "8px 10px",
  font: "inherit",
  fontSize: 13,
  background: "var(--surface)",
  color: "var(--ink)",
};
