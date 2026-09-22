"use client";

import { readResponse, success, errorMessage } from "@/lib/contracts";
import { addCalendarDays, mondayOfWeek, weekDates } from "@/lib/date";
import { useRouter } from "next/navigation";
import { useMemo, useState } from "react";
import Avatar from "../../components/Avatar";
import styles from "./availability.module.css";
import ui from "../../components/ui.module.css";

interface Override {
  date: string;
  available: boolean;
  shiftStartMin: number | null;
  shiftEndMin: number | null;
}

interface Tech {
  id: string;
  name: string;
  shiftStartMin: number;
  shiftEndMin: number;
  qualifications: string[];
  overrides: Override[];
}

const DAY_NAMES = ["Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"];

function formatMinute(value: number): string {
  const hour = Math.floor(value / 60);
  const minute = value % 60;
  const period = hour >= 12 ? "p" : "a";
  const displayHour = hour % 12 || 12;
  return minute === 0 ? `${displayHour}${period}` : `${displayHour}:${String(minute).padStart(2, "0")}${period}`;
}

function toMinutes(value: string): number {
  const [hours = 0, minutes = 0] = value.split(":").map(Number);
  return hours * 60 + minutes;
}

function fromMinutes(value: number): string {
  return `${String(Math.floor(value / 60)).padStart(2, "0")}:${String(value % 60).padStart(2, "0")}`;
}

function todayLocal(): string {
  return new Date().toISOString().slice(0, 10);
}

export default function AvailabilityEditor({ technicians, services }: { technicians: Tech[]; services: Array<{ id: string; name: string }> }) {
  const router = useRouter();
  const [techId, setTechId] = useState(technicians[0]?.id ?? "");
  const [search, setSearch] = useState("");
  const [showAddOverride, setShowAddOverride] = useState(false);
  const [date, setDate] = useState("");
  const [available, setAvailable] = useState(true);
  const [start, setStart] = useState("08:00");
  const [end, setEnd] = useState("17:00");
  const [message, setMessage] = useState("");
  const [busy, setBusy] = useState(false);

  const tech = technicians.find((item) => item.id === techId);
  const today = todayLocal();
  const filtered = useMemo(
    () => technicians.filter((item) => item.name.toLowerCase().includes(search.toLowerCase())),
    [technicians, search]
  );

  const week = useMemo(() => weekDates(mondayOfWeek(today)), [today]);

  async function saveShift(event: React.FormEvent) {
    event.preventDefault();
    if (!tech) return;
    setBusy(true);
    try {
      const response = await fetch("/api/dispatch/availability", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          technicianId: tech.id, date, available,
          shiftStartMin: available ? toMinutes(start) : null, shiftEndMin: available ? toMinutes(end) : null,
        }),
      });
      await readResponse(response, success);
      setMessage("Override saved.");
      setShowAddOverride(false);
      setDate("");
      router.refresh();
    } catch (error) { setMessage(errorMessage(error)); }
    finally { setBusy(false); }
  }

  async function deleteOverride(overrideDate: string) {
    if (!tech) return;
    setBusy(true);
    try {
      const response = await fetch("/api/dispatch/availability", {
        method: "DELETE",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ technicianId: tech.id, date: overrideDate }),
      });
      await readResponse(response, success);
      setMessage("Override removed.");
      router.refresh();
    } catch (error) { setMessage(errorMessage(error)); }
    finally { setBusy(false); }
  }

  async function toggleQualification(serviceId: string, qualified: boolean) {
    if (!tech) return;
    setBusy(true);
    try {
      const response = await fetch("/api/dispatch/qualification", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ technicianId: tech.id, serviceId, qualified }),
      });
      await readResponse(response, success);
      setMessage("Qualification updated.");
      router.refresh();
    } catch (error) { setMessage(errorMessage(error)); }
    finally { setBusy(false); }
  }

  function overrideFor(dateKey: string): Override | undefined {
    return tech?.overrides.find((item) => item.date === dateKey);
  }

  return (
    <div className={styles.main}>
      <div className={styles.top}>
        <p className={styles.eyebrow}>Dispatch</p>
        <h1 className={styles.title}>Shifts &amp; Qualifications</h1>
        <p className={styles.subtitle}>Roster availability and service qualifications, at a glance.</p>
      </div>

      <div className={styles.layout}>
        <div className={`${ui.card} ${styles.roster}`}>
          <div className={styles.search}>
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
              <circle cx="11" cy="11" r="7" />
              <path d="m21 21-4.3-4.3" />
            </svg>
            <input placeholder="Search technicians" value={search} onChange={(event) => setSearch(event.target.value)} />
          </div>
          <p className={ui.sectionLabel}>{filtered.length} technician{filtered.length === 1 ? "" : "s"}</p>
          <div className={styles.rosterList}>
            {filtered.map((item) => {
              const override = item.overrides.find((entry) => entry.date === today);
              const onToday = override ? override.available : true;
              return (
                <button
                  key={item.id}
                  type="button"
                  className={`${styles.rosterItem} ${item.id === techId ? styles.rosterItemSelected : ""}`}
                  onClick={() => { setTechId(item.id); setShowAddOverride(false); setMessage(""); }}
                >
                  <Avatar name={item.name} />
                  <span className={styles.rosterName}>{item.name}</span>
                  <span
                    className={`${styles.availDot} ${onToday ? styles.availOn : styles.availOff}`}
                    title={onToday ? "On shift today" : "Off today"}
                  />
                </button>
              );
            })}
          </div>
        </div>

        {tech ? (
          <div className={`${ui.card} ${styles.detail}`}>
            <div className={styles.detailHead}>
              <div className={styles.detailId}>
                <Avatar name={tech.name} size={52} />
                <div>
                  <h2>{tech.name}</h2>
                </div>
              </div>
              <div style={{ display: "flex", alignItems: "center", gap: 10 }}>
                <span className={`${ui.pill} ${ui.pillBrand}`}>
                  {tech.qualifications.length} of {services.length} services qualified
                </span>
                <button className={`${ui.button} ${ui.buttonBrand}`} type="button" onClick={() => setShowAddOverride((v) => !v)}>
                  + Add override
                </button>
              </div>
            </div>

            <p className={ui.sectionLabel}>Service qualifications</p>
            <div className={styles.chipRow} style={{ marginBottom: 24 }}>
              {services.map((service) => {
                const on = tech.qualifications.includes(service.id);
                return (
                  <button
                    key={service.id}
                    type="button"
                    disabled={busy}
                    className={`${ui.chip} ${on ? ui.chipOn : ""}`}
                    onClick={() => toggleQualification(service.id, !on)}
                  >
                    {on && (
                      <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="3">
                        <path d="M20 6 9 17l-5-5" />
                      </svg>
                    )}
                    {service.name}
                  </button>
                );
              })}
            </div>

            <p className={ui.sectionLabel}>This week</p>
            <div className={styles.weekStrip}>
              {week.map((dateKey) => {
                const override = overrideFor(dateKey);
                const isAvailable = override ? override.available : true;
                const startMin = override?.available ? override.shiftStartMin ?? tech.shiftStartMin : tech.shiftStartMin;
                const endMin = override?.available ? override.shiftEndMin ?? tech.shiftEndMin : tech.shiftEndMin;
                const dayName = DAY_NAMES[new Date(`${dateKey}T00:00:00Z`).getUTCDay()];
                return (
                  <div key={dateKey} className={`${styles.day} ${isAvailable ? styles.dayOn : styles.dayOff}`}>
                    <div className={styles.dname}>{dayName}</div>
                    <div className={styles.ddate}>{Number(dateKey.slice(-2))}</div>
                    <div className={styles.shiftBlock}>
                      {isAvailable ? `${formatMinute(startMin)}–${formatMinute(endMin)}` : "Off"}
                    </div>
                  </div>
                );
              })}
            </div>

            {showAddOverride && (
              <form onSubmit={saveShift} className={styles.overrideForm} style={{ marginTop: 22 }}>
                <div className={styles.field}>
                  <label>Date</label>
                  <input type="date" required value={date} onChange={(event) => setDate(event.target.value)} />
                </div>
                <div className={styles.field}>
                  <label>Available</label>
                  <input type="checkbox" checked={available} onChange={(event) => setAvailable(event.target.checked)} />
                </div>
                {available && (
                  <>
                    <div className={styles.field}>
                      <label>Start</label>
                      <input type="time" value={start} onChange={(event) => setStart(event.target.value)} />
                    </div>
                    <div className={styles.field}>
                      <label>End</label>
                      <input type="time" value={end} onChange={(event) => setEnd(event.target.value)} />
                    </div>
                  </>
                )}
                <button className={`${ui.button} ${ui.buttonBrand}`} type="submit" disabled={busy}>
                  Save day
                </button>
              </form>
            )}

            {message && <p role="status" className={styles.message}>{message}</p>}

            <p className={ui.sectionLabel} style={{ marginTop: 22 }}>Upcoming overrides</p>
            <div className={styles.overrideList}>
              {tech.overrides.length === 0 && <p className={styles.message}>No overrides on file.</p>}
              {tech.overrides.map((item) => (
                <div key={item.date} className={styles.overrideRow}>
                  <span className={styles.overrideDate}>{item.date}</span>
                  <span className={`${ui.pill} ${item.available ? ui.pillSuccess : ui.pillDanger}`}>
                    {item.available ? "Available" : "Off"}
                  </span>
                  <span className={styles.overrideTime}>
                    {item.available && item.shiftStartMin != null && item.shiftEndMin != null
                      ? `${formatMinute(item.shiftStartMin)} – ${formatMinute(item.shiftEndMin)}`
                      : "Full day"}
                  </span>
                  <button
                    type="button"
                    className={ui.iconButton}
                    title="Edit"
                    onClick={() => {
                      setDate(item.date);
                      setAvailable(item.available);
                      if (item.shiftStartMin != null) setStart(fromMinutes(item.shiftStartMin));
                      if (item.shiftEndMin != null) setEnd(fromMinutes(item.shiftEndMin));
                      setShowAddOverride(true);
                    }}
                  >
                    &#9998;
                  </button>
                  <button type="button" className={ui.iconButton} title="Delete" disabled={busy} onClick={() => deleteOverride(item.date)}>
                    &#10005;
                  </button>
                </div>
              ))}
            </div>
          </div>
        ) : (
          <div className={`${ui.card} ${styles.detail} ${styles.empty}`}>No technicians found.</div>
        )}
      </div>
    </div>
  );
}
