"use client";

import { errorMessage, readResponse, success } from "@/lib/contracts";
import { useRouter } from "next/navigation";
import { useMemo, useState } from "react";
import Avatar from "../components/Avatar";
import ui from "../components/ui.module.css";
import AddTechnicianForm from "./AddTechnicianForm";
import { formatInterval, fromMinutes, toMinutes } from "./format";
import StandardAvailabilityGrid, { type StandardDay } from "./StandardAvailabilityGrid";
import TechnicianProfileModal, { type ProfileDraft } from "./TechnicianProfileModal";
import styles from "./technicians.module.css";

interface Override { date: string; available: boolean; shiftStartMin: number | null; shiftEndMin: number | null }
interface Tech { id: string; name: string; active: boolean; email: string | null; phone: string | null; bio: string | null; color: string;
  availabilityVersions: Array<{ effectiveDate: string; days: StandardDay[] }>;
  shiftStartMin: number | null; shiftEndMin: number | null; qualifications: string[]; overrides: Override[]; overridesTruncated: boolean }

export default function TechnicianRoster({ technicians, services, metros, today }: {
  technicians: Tech[]; services: Array<{ id: string; name: string }>; metros: Array<{ id: string; name: string }>; today: string;
}) {
  const router = useRouter();
  const [techId, setTechId] = useState(technicians[0]?.id ?? ""), [search, setSearch] = useState("");
  const [showForm, setShowForm] = useState(false), [editingDate, setEditingDate] = useState<string | null>(null);
  const [date, setDate] = useState(""), [available, setAvailable] = useState(true);
  const [start, setStart] = useState(technicians[0] ? fromMinutes(technicians[0].shiftStartMin) : "");
  const [end, setEnd] = useState(technicians[0] ? fromMinutes(technicians[0].shiftEndMin) : "");
  const [message, setMessage] = useState(""), [busy, setBusy] = useState(false);
  const [showProfileModal, setShowProfileModal] = useState(false);
  const [showAddForm, setShowAddForm] = useState(false);
  const tech = technicians.find(item => item.id === techId);
  const activeServiceIds = useMemo(() => new Set(services.map(service => service.id)), [services]);
  const filtered = useMemo(() => technicians.filter(item => item.name.toLowerCase().includes(search.toLowerCase())), [technicians, search]);

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
      await readResponse(response, success); setMessage("Exception saved."); setShowForm(false); setEditingDate(null); router.refresh();
    } catch (error) { setMessage(errorMessage(error)); } finally { setBusy(false); }
  }
  async function deleteOverride(item: Override) {
    if (!tech || !window.confirm(`Delete ${tech.name}'s schedule exception for ${item.date}? Their default shift hours will be restored.`)) return;
    const technicianId = tech.id; setBusy(true); setMessage("");
    try {
      const response = await fetch("/api/dispatch/availability", { method: "DELETE", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ technicianId, date: item.date }) });
      await readResponse(response, success); setMessage("Exception removed. Default shift hours restored."); router.refresh();
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

  function versionFor(item: Tech, dateKey: string) {
    const version = item.availabilityVersions.filter(version => version.effectiveDate <= dateKey).at(-1);
    if (!version) throw new Error(`Technician availability is missing for ${dateKey}`);
    return version;
  }
  function profileFor(item: Tech): ProfileDraft {
    return { name: item.name, email: item.email ?? "", phone: item.phone ?? "", bio: item.bio ?? "", color: item.color };
  }
  async function saveProfile(item: Tech, draft: ProfileDraft) {
    setBusy(true); setMessage("");
    try {
      const response = await fetch(`/api/technicians/${item.id}`, { method: "PATCH", headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ ...draft, email: draft.email || null, phone: draft.phone || null, bio: draft.bio || null }) });
      await readResponse(response, success); setShowProfileModal(false); setMessage("Profile saved."); router.refresh();
    } catch (error) { setMessage(errorMessage(error)); } finally { setBusy(false); }
  }
  async function saveAvailability(item: Tech, days: StandardDay[]) {
    setBusy(true); setMessage("");
    try {
      const response = await fetch(`/api/technicians/${item.id}/standard-availability`, { method: "PUT", headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ days: days.map(day => ({ dayOfWeek: day.dayOfWeek, available: day.available,
          shiftStartMin: day.available ? day.startMin : null, shiftEndMin: day.available ? day.endMin : null })) }) });
      await readResponse(response, success); setShowProfileModal(false); setMessage("Weekly availability scheduled."); router.refresh();
    } catch (error) { setMessage(errorMessage(error)); } finally { setBusy(false); }
  }

  return <div className={styles.main}>
    <div className={styles.top}><p className={styles.eyebrow}>Team</p><h1 className={styles.title}>Technicians</h1><p className={styles.subtitle}>Manage technician profiles, standard availability, and qualifications.</p></div>
    <div className={styles.layout}><div className={`${ui.card} ${styles.roster}`}>
      <label className={styles.search} htmlFor="technician-search"><input id="technician-search" placeholder="Search technicians" value={search} onChange={event => setSearch(event.target.value)} /></label>
      <button type="button" className={`${ui.button} ${ui.buttonBrand}`} style={{ width: "100%", marginBottom: 12 }} onClick={() => setShowAddForm(true)}>+ Add technician</button>
      <p className={ui.sectionLabel}>{filtered.length} technician{filtered.length === 1 ? "" : "s"}</p>
      <div className={styles.rosterList}>{filtered.map(item => {
        const override = item.overrides.find(entry => entry.date === today);
        const todayDay = versionFor(item, today).days.find(value => value.dayOfWeek === new Date(`${today}T00:00:00Z`).getUTCDay());
        if (!todayDay) throw new Error(`Technician availability is incomplete for ${today}`);
        const onToday = override ? override.available : todayDay.available;
        return <button key={item.id} type="button" disabled={busy} aria-pressed={item.id === techId} className={`${styles.rosterItem} ${item.id === techId ? styles.rosterItemSelected : ""}`} onClick={() => selectTechnician(item)}>
          <Avatar name={item.name} color={item.color} /><span className={styles.rosterName}>{item.name}{!item.active && <span className={styles.inactive}>Inactive</span>}</span><span className={`${styles.availDot} ${onToday ? styles.availOn : styles.availOff}`} aria-label={onToday ? "On shift today" : "Off today"} />
        </button>;
      })}</div></div>
      {tech ? <div className={`${ui.card} ${styles.detail}`}>
        <div className={styles.detailHead}><div className={styles.detailId}><Avatar name={tech.name} color={profileFor(tech).color} size={52} /><div><h2>{tech.name}</h2>{!tech.active && <span className={styles.inactive}>Inactive technician</span>}</div></div>
          <div className={styles.detailActions}>
            <span className={`${ui.pill} ${ui.pillBrand}`}>{tech.qualifications.filter(id => activeServiceIds.has(id)).length} of {services.length} active services qualified</span>
            <button className={ui.button} type="button" onClick={() => setShowProfileModal(true)}>Edit profile</button>
            <button className={`${ui.button} ${ui.buttonBrand}`} type="button" disabled={busy} onClick={() => resetDraft(tech, true)}>+ Add exception</button>
          </div></div>

        <p className={ui.sectionLabel}>Profile</p>
        <div style={{ padding: "12px 14px", background: "var(--surface-soft)", border: "1px solid var(--line)", borderRadius: 9, marginBottom: 20 }}>
          <p style={{ margin: "0 0 4px", fontSize: 12.5, color: "var(--ink-soft)" }}>{profileFor(tech).email || "No email on file"} &middot; {profileFor(tech).phone || "No phone on file"}</p>
          <p style={{ margin: 0, fontSize: 13, color: profileFor(tech).bio ? "var(--ink)" : "var(--quiet)", fontStyle: profileFor(tech).bio ? "normal" : "italic" }}>{profileFor(tech).bio || "No bio provided yet."}</p>
        </div>

        <p className={ui.sectionLabel}>Service qualifications</p><div className={`${styles.chipRow} ${styles.profileQualifications}`}>{services.map(service => { const on = tech.qualifications.includes(service.id); return <button key={service.id} type="button" disabled={busy} aria-pressed={on} className={`${ui.chip} ${on ? ui.chipOn : ""}`} onClick={() => toggleQualification(service.id, !on)}>{on && <span aria-hidden="true">&#10003;</span>}{service.name}</button>; })}</div>

        <p className={ui.sectionLabel}>Current weekly availability</p>
        <StandardAvailabilityGrid days={versionFor(tech, today).days} />
        {tech.availabilityVersions.find(version => version.effectiveDate > today) && <p className={styles.message}>A replacement weekly schedule is pending from {tech.availabilityVersions.find(version => version.effectiveDate > today)?.effectiveDate}. Open Edit profile to review it.</p>}

        {showForm && <form onSubmit={saveShift} className={styles.overrideForm}><div className={styles.field}><label htmlFor="override-date">Date</label><input id="override-date" type="date" required value={date} min={today} disabled={editingDate != null} onChange={event => setDate(event.target.value)} /></div><div className={styles.checkboxField}><input id="override-available" type="checkbox" checked={available} onChange={event => setAvailable(event.target.checked)} /><label htmlFor="override-available">Available</label></div>{available && <><div className={styles.field}><label htmlFor="override-start">Start</label><input id="override-start" type="time" required value={start} onChange={event => setStart(event.target.value)} /></div><div className={styles.field}><label htmlFor="override-end">End</label><input id="override-end" type="time" required value={end} onChange={event => setEnd(event.target.value)} /></div></>}<button className={`${ui.button} ${ui.buttonBrand}`} type="submit" disabled={busy}>Save day</button><button className={ui.button} type="button" disabled={busy} onClick={() => { setShowForm(false); setEditingDate(null); }}>Cancel</button></form>}
        {message && <p role="status" className={styles.message}>{message}</p>}<p className={ui.sectionLabel}>Upcoming schedule exceptions</p>
        <div className={styles.overrideList}>{tech.overrides.length === 0 && <p className={styles.message}>No upcoming exceptions.</p>}{tech.overrides.map(item => <div key={item.date} className={styles.overrideRow}><span className={styles.overrideDate}>{item.date}</span><span className={`${ui.pill} ${item.available ? ui.pillSuccess : ui.pillDanger}`}>{item.available ? "Available" : "Off"}</span><span className={styles.overrideTime}>{!item.available ? "Full day" : formatInterval(item.shiftStartMin, item.shiftEndMin) ?? "Invalid shift hours"}</span><button type="button" className={ui.iconButton} aria-label={`Edit ${tech.name}'s exception for ${item.date}`} disabled={busy} onClick={() => { setDate(item.date); setAvailable(item.available); setStart(item.shiftStartMin == null ? fromMinutes(tech.shiftStartMin) : fromMinutes(item.shiftStartMin)); setEnd(item.shiftEndMin == null ? fromMinutes(tech.shiftEndMin) : fromMinutes(item.shiftEndMin)); setEditingDate(item.date); setShowForm(true); setMessage(""); }}>&#9998;</button><button type="button" className={ui.iconButton} aria-label={`Delete ${tech.name}'s exception for ${item.date}`} disabled={busy} onClick={() => deleteOverride(item)}>&#10005;</button></div>)}{tech.overridesTruncated && <p role="note" className={styles.message}>Showing the first 100 upcoming exceptions.</p>}</div>

        {showProfileModal && <TechnicianProfileModal
          initial={profileFor(tech)}
          onClose={() => setShowProfileModal(false)}
          currentDays={versionFor(tech, today).days}
          pending={tech.availabilityVersions.find(version => version.effectiveDate > today) ?? null}
          onSave={(draft) => saveProfile(tech, draft)}
          onSaveAvailability={(days) => saveAvailability(tech, days)}
          busy={busy}
          error={message}
        />}
      </div> : <div className={`${ui.card} ${styles.detail} ${styles.empty}`}>No technicians found</div>}
    </div>
    {showAddForm && <AddTechnicianForm services={services} metros={metros} onClose={() => setShowAddForm(false)} onCreated={() => { setShowAddForm(false); router.refresh(); }} />}
  </div>;
}
