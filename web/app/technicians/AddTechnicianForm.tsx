"use client";

import { useState } from "react";
import ui from "../components/ui.module.css";
import ColorPicker from "./ColorPicker";
import StandardAvailabilityGrid, { defaultStandardWeek, type StandardDay } from "./StandardAvailabilityGrid";
import { TECHNICIAN_COLOR_PALETTE } from "@/lib/technicianColor";
import styles from "./technicians.module.css";
import { errorMessage, readResponse } from "@/lib/contracts";
import { z } from "zod";
import HomeAddressFields from "./HomeAddressFields";
import { inputStyle } from "./inputStyle";
import { useHomeAddressLookup } from "./useHomeAddressLookup";

export default function AddTechnicianForm({ services, metros, dealerships, depots, onClose, onCreated }: {
  services: Array<{ id: string; name: string }>;
  metros: Array<{ id: string; name: string }>;
  dealerships: Array<{ id: string; name: string }>;
  depots: Array<{ id: string; name: string; dealershipId: string; metroId: string }>;
  onClose: () => void;
  onCreated: () => void;
}) {
  const [name, setName] = useState("");
  const [email, setEmail] = useState("");
  const [phone, setPhone] = useState("");
  const [bio, setBio] = useState("");
  const [color, setColor] = useState(TECHNICIAN_COLOR_PALETTE[0] ?? "#2563eb");
  const [days, setDays] = useState<StandardDay[]>(() => defaultStandardWeek(8 * 60, 17 * 60));
  const [quals, setQuals] = useState<string[]>([]);
  const [metroId, setMetroId] = useState(metros[0]?.id ?? "");
  const [dealershipId, setDealershipId] = useState(depots.find(item => item.metroId === metros[0]?.id)?.dealershipId ?? "");
  const [depotId, setDepotId] = useState(depots.find(item => item.metroId === metros[0]?.id)?.id ?? "");
  const home = useHomeAddressLookup();
  const [busy, setBusy] = useState(false), [message, setMessage] = useState("");

  const invalid = !name.trim() || !email.trim() || !phone.trim() || !metroId || !dealershipId || !depotId || !home.pinReady || !quals.length ||
    !days.some(day => day.available) || days.some(day => day.available && day.startMin >= day.endMin);

  function toggleDay(dayOfWeek: number) {
    setDays((current) => current.map((day) => (day.dayOfWeek === dayOfWeek ? { ...day, available: !day.available } : day)));
  }
  function toggleQual(id: string) {
    setQuals((current) => (current.includes(id) ? current.filter((item) => item !== id) : [...current, id]));
  }
  async function createTechnician() {
    if (invalid || !home.payload) return;
    setBusy(true); setMessage("");
    try {
      const response = await fetch("/api/technicians", { method: "POST", headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ name: name.trim(), email: email.trim(), phone: phone.trim(), bio: bio.trim() || null,
          color, depotId, ...home.payload,
          days: days.map(day => ({ dayOfWeek: day.dayOfWeek, available: day.available,
            shiftStartMin: day.available ? day.startMin : null, shiftEndMin: day.available ? day.endMin : null })), qualifications: quals }) });
      await readResponse(response, z.object({ success: z.literal(true), id: z.string() }));
      onCreated();
    } catch (error) { setMessage(errorMessage(error)); } finally { setBusy(false); }
  }

  return (
    <div className={styles.modalBackdrop} role="presentation" onMouseDown={onClose}>
      <div className={styles.modal} role="dialog" aria-modal="true" aria-labelledby="add-technician-title" onMouseDown={(event) => event.stopPropagation()}>
        <h2 id="add-technician-title" className={styles.modalTitle}>Add technician</h2>
        <p className={styles.modalSubtitle}>Set up their profile and standard weekly schedule once. Exceptions can be layered on for specific dates later.</p>
          <>
            <div className={styles.fieldGrid}><div><label className={ui.sectionLabel} htmlFor="new-metro">Metro</label>
              <select id="new-metro" value={metroId} onChange={event => { const next = event.target.value; const first = depots.find(item => item.metroId === next); setMetroId(next); setDealershipId(first?.dealershipId ?? ""); setDepotId(first?.id ?? ""); }} style={inputStyle}>{metros.map(metro => <option key={metro.id} value={metro.id}>{metro.name}</option>)}</select></div>
              <div><label className={ui.sectionLabel} htmlFor="new-dealership">Dealership</label><select id="new-dealership" value={dealershipId} onChange={event => { const next = event.target.value; setDealershipId(next); setDepotId(depots.find(item => item.metroId === metroId && item.dealershipId === next)?.id ?? ""); }} style={inputStyle}>
                <option value="">Choose a dealership</option>{dealerships.filter(item => depots.some(depot => depot.dealershipId === item.id && depot.metroId === metroId)).map(item => <option key={item.id} value={item.id}>{item.name}</option>)}
              </select></div>
              <div><label className={ui.sectionLabel} htmlFor="new-depot">Depot</label><select id="new-depot" value={depotId} onChange={event => setDepotId(event.target.value)} style={inputStyle}>
                <option value="">Choose a depot</option>{depots.filter(item => item.metroId === metroId && item.dealershipId === dealershipId).map(item => <option key={item.id} value={item.id}>{item.name}</option>)}
              </select></div></div>
            <HomeAddressFields state={home} idPrefix="new" />
            <div className={styles.fieldGrid}>
              <div>
                <label className={ui.sectionLabel} htmlFor="new-name">Name <span className={styles.required}>*</span></label>
                <input id="new-name" value={name} placeholder="Full name" onChange={(event) => setName(event.target.value)} style={inputStyle} />
              </div>
              <div>
                <label className={ui.sectionLabel} htmlFor="new-email">Email <span className={styles.required}>*</span></label>
                <input id="new-email" value={email} placeholder="Required" onChange={(event) => setEmail(event.target.value)} style={inputStyle} />
              </div>
            </div>
            <div style={{ marginBottom: 14 }}>
              <label className={ui.sectionLabel} htmlFor="new-phone">Phone <span className={styles.required}>*</span></label>
              <input id="new-phone" value={phone} placeholder="Required" onChange={(event) => setPhone(event.target.value)} style={inputStyle} />
            </div>
            <div style={{ marginBottom: 18 }}>
              <label className={ui.sectionLabel} htmlFor="new-bio">Short bio <span className={styles.optional}>(optional)</span></label>
              <textarea id="new-bio" value={bio} rows={5} placeholder="Optional" onChange={(event) => setBio(event.target.value)} style={{ ...inputStyle, width: "100%", resize: "vertical" }} />
            </div>

            <label className={ui.sectionLabel}>Color</label>
            <ColorPicker value={color} onChange={setColor} />

            <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between", marginBottom: 8 }}>
              <label className={ui.sectionLabel} style={{ margin: 0 }}>Standard availability</label>
              <span style={{ fontSize: 11, color: "var(--quiet)" }}>Defaults to Mon-Fri, 8a-5p. Click to toggle.</span>
            </div>
            <StandardAvailabilityGrid days={days} onToggle={toggleDay} onHours={(dayOfWeek, field, value) => setDays(current => current.map(day => day.dayOfWeek === dayOfWeek ? { ...day, [field]: value } : day))} />

            <label className={ui.sectionLabel}>Qualifications</label>
            <div className={styles.chipRow} style={{ marginBottom: 22 }}>
              {services.map((service) => {
                const on = quals.includes(service.id);
                return (
                  <button key={service.id} type="button" aria-pressed={on} className={`${ui.chip} ${on ? ui.chipOn : ""}`} onClick={() => toggleQual(service.id)}>
                    {on && <span aria-hidden="true">&#10003;</span>}{service.name}
                  </button>
                );
              })}
            </div>

            {message && <p role="alert" className={styles.errorText}>{message}</p>}
            {invalid && <p className={styles.errorText}>Complete the profile, confirm a pin, choose a qualification, and set valid hours for at least one day.</p>}
            <div className={styles.modalActions}>
              <button type="button" className={ui.button} onClick={onClose}>Cancel</button>
              <button type="button" className={`${ui.button} ${ui.buttonBrand}`} disabled={invalid || busy} onClick={createTechnician}>
                Create technician
              </button>
            </div>
          </>
      </div>
    </div>
  );
}
