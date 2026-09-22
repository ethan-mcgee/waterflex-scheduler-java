"use client";

import { errorMessage, readResponse, success } from "@/lib/contracts";
import { mondayOfWeek, weekDates } from "@/lib/date";
import { useRouter } from "next/navigation";
import { useMemo, useState } from "react";
import Avatar from "../../components/Avatar";
import ui from "../../components/ui.module.css";
import styles from "./availability.module.css";

interface Override { date: string; available: boolean; shiftStartMin: number | null; shiftEndMin: number | null }
interface Absence { technicianId: string; date: string; startMin: number | null; endMin: number | null }
interface Tech { id: string; name: string; active: boolean; shiftStartMin: number | null; shiftEndMin: number | null; qualifications: string[]; overrides: Override[]; overridesTruncated: boolean }
const DAY_NAMES = ["Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"];

function formatMinute(value: number): string {
  if (value === 1440) return "12a";
  const hour = Math.floor(value / 60), minute = value % 60, period = hour >= 12 ? "p" : "a";
  return minute === 0 ? `${hour % 12 || 12}${period}` : `${hour % 12 || 12}:${String(minute).padStart(2, "0")}${period}`;
}
function toMinutes(value: string): number | null {
  if (!/^\d{2}:\d{2}$/.test(value)) return null;
  const [hours, minutes] = value.split(":").map(Number);
  if (hours == null || minutes == null || hours > 23 || minutes > 59) return null;
  return hours * 60 + minutes;
}
function fromMinutes(value: number | null): string { return value == null ? "" : `${String(Math.floor(value / 60)).padStart(2, "0")}:${String(value % 60).padStart(2, "0")}`; }
function formatInterval(start: number | null, end: number | null): string | null {
  return start != null && end != null && start >= 0 && end <= 1440 && start < end ? `${formatMinute(start)} to ${formatMinute(end)}` : null;
}

export default function AvailabilityEditor({ technicians, services, today, absences }: {
  technicians: Tech[]; services: Array<{ id: string; name: string }>; today: string; absences: Absence[];
}) {
  const router = useRouter();
  const [techId, setTechId] = useState(technicians[0]?.id ?? ""), [search, setSearch] = useState("");
  const [showForm, setShowForm] = useState(false), [editingDate, setEditingDate] = useState<string | null>(null);
  const [date, setDate] = useState(""), [available, setAvailable] = useState(true);
  const [start, setStart] = useState(technicians[0] ? fromMinutes(technicians[0].shiftStartMin) : "");
  const [end, setEnd] = useState(technicians[0] ? fromMinutes(technicians[0].shiftEndMin) : "");
  const [message, setMessage] = useState(""), [busy, setBusy] = useState(false);
  const tech = technicians.find(item => item.id === techId);
  const activeServiceIds = useMemo(() => new Set(services.map(service => service.id)), [services]);
  const filtered = useMemo(() => technicians.filter(item => item.name.toLowerCase().includes(search.toLowerCase())), [technicians, search]);
  const week = useMemo(() => weekDates(mondayOfWeek(today)), [today]);

  function resetDraft(next: Tech, open = false) {
    setDate(""); setAvailable(true); setStart(fromMinutes(next.shiftStartMin)); setEnd(fromMinutes(next.shiftEndMin));
    setEditingDate(null); setShowForm(open); setMessage("");
  }
  function selectTechnician(next: Tech) { if (!busy) { setTechId(next.id); resetDraft(next); } }
  async function saveShift(event: React.FormEvent) {
    event.preventDefault();
    if (!tech || !date) { setMessage("Choose a date before saving."); return; }
    const startMin = available ? toMinutes(start) : null, endMin = available ? toMinutes(end) : null;
    if (available && (startMin == null || endMin == null || startMin >= endMin)) { setMessage("Enter a start time that is before the end time."); return; }
    const technicianId = tech.id; setBusy(true); setMessage("");
    try {
      const response = await fetch("/api/dispatch/availability", { method: "POST", headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ technicianId, date, available, shiftStartMin: startMin, shiftEndMin: endMin }) });
      await readResponse(response, success); setMessage("Override saved."); setShowForm(false); setEditingDate(null); router.refresh();
    } catch (error) { setMessage(errorMessage(error)); } finally { setBusy(false); }
  }
  async function deleteOverride(item: Override) {
    if (!tech || !window.confirm(`Delete ${tech.name}'s override for ${item.date}? Their default shift hours will be restored.`)) return;
    const technicianId = tech.id; setBusy(true); setMessage("");
    try {
      const response = await fetch("/api/dispatch/availability", { method: "DELETE", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ technicianId, date: item.date }) });
      await readResponse(response, success); setMessage("Override removed. Default shift hours restored."); router.refresh();
    } catch (error) { setMessage(errorMessage(error)); } finally { setBusy(false); }
  }
  async function toggleQualification(serviceId: string, qualified: boolean) {
    if (!tech) return;
    const technicianId = tech.id; setBusy(true); setMessage("");
    try {
      const response = await fetch("/api/dispatch/qualification", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ technicianId, serviceId, qualified }) });
      await readResponse(response, success); setMessage("Qualification updated."); router.refresh();
    } catch (error) { setMessage(errorMessage(error)); } finally { setBusy(false); }
  }

  return <div className={styles.main}>
    <div className={styles.top}><p className={styles.eyebrow}>Dispatch</p><h1 className={styles.title}>Shifts &amp; Qualifications</h1><p className={styles.subtitle}>Roster availability and service qualifications, at a glance.</p></div>
    <div className={styles.layout}><div className={`${ui.card} ${styles.roster}`}>
      <label className={styles.search} htmlFor="technician-search"><span aria-hidden="true">&#128269;</span><input id="technician-search" placeholder="Search technicians" value={search} onChange={event => setSearch(event.target.value)} /></label>
      <p className={ui.sectionLabel}>{filtered.length} technician{filtered.length === 1 ? "" : "s"}</p>
      <div className={styles.rosterList}>{filtered.map(item => {
        const override = item.overrides.find(entry => entry.date === today), onToday = override ? override.available : true;
        return <button key={item.id} type="button" disabled={busy} aria-pressed={item.id === techId} className={`${styles.rosterItem} ${item.id === techId ? styles.rosterItemSelected : ""}`} onClick={() => selectTechnician(item)}>
          <Avatar name={item.name} /><span className={styles.rosterName}>{item.name}{!item.active && <span className={styles.inactive}>Inactive</span>}</span><span className={`${styles.availDot} ${onToday ? styles.availOn : styles.availOff}`} aria-label={onToday ? "On shift today" : "Off today"} />
        </button>;
      })}</div></div>
      {tech ? <div className={`${ui.card} ${styles.detail}`}>
        <div className={styles.detailHead}><div className={styles.detailId}><Avatar name={tech.name} size={52} /><div><h2>{tech.name}</h2>{!tech.active && <span className={styles.inactive}>Inactive technician</span>}</div></div>
          <div className={styles.detailActions}><span className={`${ui.pill} ${ui.pillBrand}`}>{tech.qualifications.filter(id => activeServiceIds.has(id)).length} of {services.length} active services qualified</span><button className={`${ui.button} ${ui.buttonBrand}`} type="button" disabled={busy} onClick={() => resetDraft(tech, true)}>+ Add override</button></div></div>
        <p className={ui.sectionLabel}>Service qualifications</p><div className={styles.chipRow}>{services.map(service => { const on = tech.qualifications.includes(service.id); return <button key={service.id} type="button" disabled={busy} aria-pressed={on} className={`${ui.chip} ${on ? ui.chipOn : ""}`} onClick={() => toggleQualification(service.id, !on)}>{on && <span aria-hidden="true">&#10003;</span>}{service.name}</button>; })}</div>
        <p className={ui.sectionLabel}>This week</p><div className={styles.weekScroller}><div className={styles.weekStrip}>{week.map(dateKey => {
          const override = tech.overrides.find(item => item.date === dateKey), isAvailable = override ? override.available : true;
          const startMin = override ? override.shiftStartMin : tech.shiftStartMin;
          const endMin = override ? override.shiftEndMin : tech.shiftEndMin;
          const dayAbsences = absences.filter(item => item.technicianId === tech.id && item.date === dateKey);
          return <div key={dateKey} className={`${styles.day} ${isAvailable ? styles.dayOn : styles.dayOff}`}><div className={styles.dname}>{DAY_NAMES[new Date(`${dateKey}T00:00:00Z`).getUTCDay()]}</div><div className={styles.ddate}>{Number(dateKey.slice(-2))}</div><div className={styles.shiftBlock}>{!isAvailable ? "Off" : formatInterval(startMin, endMin) ?? "Shift hours unavailable"}</div>{dayAbsences.map((absence, index) => <div className={styles.absence} key={`${dateKey}-${index}`}>{formatInterval(absence.startMin, absence.endMin) == null ? "Invalid approved time-off interval" : `Time off ${formatInterval(absence.startMin, absence.endMin)}`}</div>)}</div>;
        })}</div></div>
        {showForm && <form onSubmit={saveShift} className={styles.overrideForm}><div className={styles.field}><label htmlFor="override-date">Date</label><input id="override-date" type="date" required value={date} min={today} disabled={editingDate != null} onChange={event => setDate(event.target.value)} /></div><div className={styles.checkboxField}><input id="override-available" type="checkbox" checked={available} onChange={event => setAvailable(event.target.checked)} /><label htmlFor="override-available">Available</label></div>{available && <><div className={styles.field}><label htmlFor="override-start">Start</label><input id="override-start" type="time" required value={start} onChange={event => setStart(event.target.value)} /></div><div className={styles.field}><label htmlFor="override-end">End</label><input id="override-end" type="time" required value={end} onChange={event => setEnd(event.target.value)} /></div></>}<button className={`${ui.button} ${ui.buttonBrand}`} type="submit" disabled={busy}>Save day</button><button className={ui.button} type="button" disabled={busy} onClick={() => { setShowForm(false); setEditingDate(null); }}>Cancel</button></form>}
        {message && <p role="status" className={styles.message}>{message}</p>}<p className={ui.sectionLabel}>Upcoming overrides</p>
        <div className={styles.overrideList}>{tech.overrides.length === 0 && <p className={styles.message}>No upcoming overrides.</p>}{tech.overrides.map(item => <div key={item.date} className={styles.overrideRow}><span className={styles.overrideDate}>{item.date}</span><span className={`${ui.pill} ${item.available ? ui.pillSuccess : ui.pillDanger}`}>{item.available ? "Available" : "Off"}</span><span className={styles.overrideTime}>{!item.available ? "Full day" : formatInterval(item.shiftStartMin, item.shiftEndMin) ?? "Invalid shift hours"}</span><button type="button" className={ui.iconButton} aria-label={`Edit ${tech.name}'s override for ${item.date}`} disabled={busy} onClick={() => { setDate(item.date); setAvailable(item.available); setStart(item.shiftStartMin == null ? fromMinutes(tech.shiftStartMin) : fromMinutes(item.shiftStartMin)); setEnd(item.shiftEndMin == null ? fromMinutes(tech.shiftEndMin) : fromMinutes(item.shiftEndMin)); setEditingDate(item.date); setShowForm(true); setMessage(""); }}>&#9998;</button><button type="button" className={ui.iconButton} aria-label={`Delete ${tech.name}'s override for ${item.date}`} disabled={busy} onClick={() => deleteOverride(item)}>&#10005;</button></div>)}{tech.overridesTruncated && <p role="note" className={styles.message}>Showing the first 100 upcoming overrides.</p>}</div>
      </div> : <div className={`${ui.card} ${styles.detail} ${styles.empty}`}>No technicians found</div>}
    </div>
  </div>;
}
