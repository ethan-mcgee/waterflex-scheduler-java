"use client";

import { useState } from "react";

interface Tech {
  id: string; name: string; shiftStartMin: number; shiftEndMin: number;
  qualifications: string[];
  overrides: Array<{ date: string; available: boolean; shiftStartMin: number | null; shiftEndMin: number | null }>;
}

export default function AvailabilityEditor({ technicians, services }: { technicians: Tech[]; services: Array<{ id: string; name: string }> }) {
  const [techId, setTechId] = useState(technicians[0]?.id ?? "");
  const [date, setDate] = useState("");
  const [available, setAvailable] = useState(true);
  const [start, setStart] = useState("08:00");
  const [end, setEnd] = useState("17:00");
  const [message, setMessage] = useState("");
  const tech = technicians.find((item) => item.id === techId);
  async function saveShift(event: React.FormEvent) {
    event.preventDefault();
    const response = await fetch("/api/dispatch/availability", {
      method: "POST", headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ technicianId: techId, date, available,
        shiftStartMin: available ? toMinutes(start) : null, shiftEndMin: available ? toMinutes(end) : null }),
    });
    setMessage(response.ok ? "Saved. Reload to see the new override." : "Could not save shift.");
  }
  async function toggleQualification(serviceId: string, qualified: boolean) {
    const response = await fetch("/api/dispatch/qualification", {
      method: "POST", headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ technicianId: techId, serviceId, qualified }),
    });
    setMessage(response.ok ? "Saved. Reload to see the change." : "Could not save qualification.");
  }
  return <div>
    <label>Technician <select value={techId} onChange={(event) => setTechId(event.target.value)}>
      {technicians.map((item) => <option key={item.id} value={item.id}>{item.name}</option>)}
    </select></label>
    <h2>Services</h2>
    {services.map((service) => <label key={`${techId}-${service.id}`} style={{ display: "block", marginBottom: 8 }}>
      <input type="checkbox" defaultChecked={tech?.qualifications.includes(service.id)}
        onChange={(event) => toggleQualification(service.id, event.target.checked)} /> {service.name}
    </label>)}
    <h2>Day override</h2>
    <form onSubmit={saveShift} style={{ display: "grid", gap: 10, maxWidth: 350 }}>
      <label>Date <input type="date" required value={date} onChange={(event) => setDate(event.target.value)} /></label>
      <label><input type="checkbox" checked={available} onChange={(event) => setAvailable(event.target.checked)} /> Available</label>
      {available && <><label>Start <input type="time" value={start} onChange={(event) => setStart(event.target.value)} /></label>
        <label>End <input type="time" value={end} onChange={(event) => setEnd(event.target.value)} /></label></>}
      <button type="submit">Save day</button>
    </form>
    {message && <p role="status">{message}</p>}
    <h2>Upcoming overrides</h2>
    <ul>{tech?.overrides.map((item) => <li key={item.date}>{item.date}: {item.available ? "available" : "off"}</li>)}</ul>
  </div>;
}

function toMinutes(value: string) { const [hours = 0, minutes = 0] = value.split(":").map(Number); return hours * 60 + minutes; }
