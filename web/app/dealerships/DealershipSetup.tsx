"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import { z } from "zod";
import { errorMessage, readResponse } from "@/lib/contracts";

type Anchor = "HOME" | "DEPOT";
function anchor(value: string): Anchor { return value === "DEPOT" ? "DEPOT" : "HOME"; }
type Depot = { id: string; dealershipId: string; metroId: string; name: string; lat: number; lng: number; departure: Anchor; returnTo: Anchor; policyEffectiveDate: string };
type Dealership = { id: string; name: string; technicianCount: number };
const created = z.object({ success: z.literal(true), id: z.string() });
const saved = z.object({ success: z.literal(true) });
const pins = z.object({ candidates: z.array(z.object({ lat: z.number().finite(), lng: z.number().finite(), precision: z.string() })) });

export default function DealershipSetup({ metros, depots, dealerships }: {
  metros: Array<{ id: string; name: string }>; depots: Depot[]; dealerships: Dealership[];
}) {
  const router = useRouter();
  const [name, setName] = useState("");
  const [dealershipId, setDealershipId] = useState(dealerships[0]?.id ?? "");
  const [metroId, setMetroId] = useState(metros[0]?.id ?? "");
  const [depotName, setDepotName] = useState("");
  const [address, setAddress] = useState({ line1: "", city: "", state: "", postalCode: "" });
  const [candidates, setCandidates] = useState<Array<{ lat: number; lng: number; precision: string }>>([]);
  const [pinIndex, setPinIndex] = useState<number | null>(null);
  const [departure, setDeparture] = useState<Anchor>("HOME");
  const [returnTo, setReturnTo] = useState<Anchor>("HOME");
  const [message, setMessage] = useState("");
  const [busy, setBusy] = useState(false);

  async function run(action: () => Promise<void>) {
    setBusy(true); setMessage("");
    try { await action(); router.refresh(); }
    catch (error) { setMessage(errorMessage(error)); }
    finally { setBusy(false); }
  }
  async function findPins() {
    await run(async () => {
      setCandidates([]); setPinIndex(null);
      const response = await fetch("/api/technicians/geocode", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(address) });
      const result = await readResponse(response, pins);
      setCandidates(result.candidates);
      if (!result.candidates.length) setMessage("No verified pin found for this address.");
    });
  }
  async function createDealership() {
    await run(async () => {
      const response = await fetch("/api/dealerships", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ name }) });
      const result = await readResponse(response, created);
      setDealershipId(result.id); setName(""); setMessage("Dealership created. Add its depots below.");
    });
  }
  async function createDepot() {
    const pin = pinIndex == null ? null : candidates[pinIndex];
    if (!pin) return;
    await run(async () => {
      const response = await fetch("/api/depots", { method: "POST", headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ dealershipId, metroId, name: depotName, address, confirmedPin: { lat: pin.lat, lng: pin.lng }, departure, returnTo }) });
      await readResponse(response, created);
      setDepotName(""); setCandidates([]); setPinIndex(null); setMessage("Depot created.");
    });
  }
  async function savePolicy(item: Depot, nextDeparture: Anchor, nextReturn: Anchor) {
    await run(async () => {
      const response = await fetch(`/api/depots/${item.id}/policy`, { method: "PUT", headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ departure: nextDeparture, returnTo: nextReturn }) });
      await readResponse(response, saved); setMessage(`Saved ${item.name}. Booked routes were checked.`);
    });
  }
  return <main style={{ maxWidth: 900, margin: "40px auto", padding: 24 }}>
    <h1>Dealerships and depots</h1>
    <p>Create a dealership, then add its depots in each metro. Each depot has its own route policy.</p>
    <section><h2>1. Add dealership</h2>
      <label>Name <input value={name} onChange={event => setName(event.target.value)} /></label>
      <button type="button" disabled={busy || !name.trim()} onClick={createDealership}>Create dealership</button>
    </section>
    <section><h2>2. Add depot</h2>
      <label>Dealership <select value={dealershipId} onChange={event => setDealershipId(event.target.value)}><option value="">Choose a dealership</option>
        {dealerships.map(item => <option key={item.id} value={item.id}>{item.name}</option>)}</select></label>
      <label>Metro <select value={metroId} onChange={event => setMetroId(event.target.value)}>
        {metros.map(item => <option key={item.id} value={item.id}>{item.name}</option>)}</select></label>
      <label>Depot name <input value={depotName} onChange={event => setDepotName(event.target.value)} /></label>
      {(["line1", "city", "state", "postalCode"] as const).map(field => <label key={field}>{field} <input value={address[field]}
        onChange={event => { setAddress(current => ({ ...current, [field]: event.target.value })); setCandidates([]); setPinIndex(null); }} /></label>)}
      <button type="button" disabled={busy || Object.values(address).some(value => !value.trim())} onClick={findPins}>Find depot pins</button>
      {candidates.length > 0 && <label>Confirm depot pin <select value={pinIndex ?? ""} onChange={event => setPinIndex(event.target.value === "" ? null : Number(event.target.value))}>
        <option value="">Choose a pin</option>{candidates.map((pin, index) => <option key={index} value={index}>{pin.precision}: {pin.lat.toFixed(5)}, {pin.lng.toFixed(5)}</option>)}
      </select></label>}
      <EndpointControls departure={departure} returnTo={returnTo} onDeparture={setDeparture} onReturn={setReturnTo} />
      <button type="button" disabled={busy || !dealershipId || !metroId || !depotName.trim() || pinIndex == null} onClick={createDepot}>Create depot</button>
    </section>
    <section><h2>Existing dealerships</h2>{dealerships.map(item => <div key={item.id}><h3>{item.name}</h3><p>{item.technicianCount} technicians</p>
      {depots.filter(depot => depot.dealershipId === item.id).map(depot => <PolicyEditor key={`${depot.id}:${depot.departure}:${depot.returnTo}`} item={depot} busy={busy} save={savePolicy} />)}
      {!depots.some(depot => depot.dealershipId === item.id) && <p>No depots configured.</p>}</div>)}</section>
    {message && <p role="status">{message}</p>}
    <p>Next, add technicians with a verified home address, a depot, weekly availability, and qualifications.</p>
  </main>;
}

function EndpointControls({ departure, returnTo, onDeparture, onReturn }: {
  departure: Anchor; returnTo: Anchor; onDeparture: (value: Anchor) => void; onReturn: (value: Anchor) => void;
}) {
  return <><label>Departure <select value={departure} onChange={event => onDeparture(anchor(event.target.value))}>
    <option value="HOME">Home</option><option value="DEPOT">Depot</option></select></label>
    <label>Return <select value={returnTo} onChange={event => onReturn(anchor(event.target.value))}>
      <option value="HOME">Home</option><option value="DEPOT">Depot</option></select></label></>;
}

function PolicyEditor({ item, busy, save }: { item: Depot; busy: boolean; save: (item: Depot, departure: Anchor, returnTo: Anchor) => Promise<void> }) {
  const [departure, setDeparture] = useState(item.departure), [returnTo, setReturnTo] = useState(item.returnTo);
  return <div><h4>{item.name}</h4><p>{item.metroId} ({item.lat.toFixed(5)}, {item.lng.toFixed(5)})</p>
    <p>Route policy effective from {item.policyEffectiveDate}</p>
    <EndpointControls departure={departure} returnTo={returnTo} onDeparture={setDeparture} onReturn={setReturnTo} />
    <button type="button" disabled={busy || departure === item.departure && returnTo === item.returnTo}
      onClick={() => void save(item, departure, returnTo)}>Save route policy</button></div>;
}
