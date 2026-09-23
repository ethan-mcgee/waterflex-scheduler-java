"use client";

import { useEffect, useRef, useState } from "react";
import { useRouter } from "next/navigation";
import { z } from "zod";
import { errorMessage, readResponse } from "@/lib/contracts";
import Avatar from "../components/Avatar";
import ui from "../components/ui.module.css";
import DepotPinMap from "./DepotPinMap";
import styles from "./dealerships.module.css";

type Anchor = "HOME" | "DEPOT";
type Depot = {
  id: string; dealershipId: string; metroId: string; name: string; lat: number; lng: number;
  departure: Anchor; returnTo: Anchor; policyEffectiveDate: string; technicianCount: number;
  upcomingPolicy: { departure: Anchor; returnTo: Anchor; effectiveDate: string } | null;
};
type Dealership = { id: string; name: string; technicianCount: number };
type Address = { line1: string; city: string; state: string; postalCode: string };
type Pin = { lat: number; lng: number };

const created = z.object({ success: z.literal(true), id: z.string() });
const saved = z.object({ success: z.literal(true), effectiveDate: z.iso.date() });
const pinsResponse = z.object({ candidates: z.array(z.object({
  lat: z.number().finite().min(-90).max(90), lng: z.number().finite().min(-180).max(180),
  precision: z.enum(["ROOFTOP", "APPROXIMATE"]),
})) });

const EMPTY_ADDRESS: Address = { line1: "", city: "", state: "", postalCode: "" };
const LOOKUP_DEBOUNCE_MS = 700;

export default function DealershipSetup({ metros, depots, dealerships }: {
  metros: Array<{ id: string; name: string }>; depots: Depot[]; dealerships: Dealership[];
}) {
  const router = useRouter();
  const [name, setName] = useState("");
  const [dealershipId, setDealershipId] = useState(dealerships[0]?.id ?? "");
  const [metroId, setMetroId] = useState(metros[0]?.id ?? "");
  const [depotName, setDepotName] = useState("");
  const [address, setAddress] = useState<Address>(EMPTY_ADDRESS);
  const [pin, setPin] = useState<Pin | null>(null);
  const [lookup, setLookup] = useState<"idle" | "loading" | "done" | "error">("idle");
  const [departure, setDeparture] = useState<Anchor>("HOME");
  const [returnTo, setReturnTo] = useState<Anchor>("HOME");
  const [editingDepotId, setEditingDepotId] = useState<string | null>(null);
  const [search, setSearch] = useState("");
  const [message, setMessage] = useState("");
  const [error, setError] = useState<{ area: "dealership" | "depot" | "policy"; text: string } | null>(null);
  const [busy, setBusy] = useState(false);

  const addressComplete = Object.values(address).every(value => value.trim());
  const depotFieldsReady = Boolean(dealershipId) && Boolean(metroId) && depotName.trim().length > 0 && addressComplete;

  useEffect(() => {
    if (!depotFieldsReady) { setLookup("idle"); setPin(null); return; }
    const controller = new AbortController();
    setPin(null);
    setLookup("idle");
    const timer = setTimeout(() => {
      setLookup("loading");
      fetch("/api/technicians/geocode", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(address), signal: controller.signal })
        .then(response => readResponse(response, pinsResponse))
        .then(result => {
          if (controller.signal.aborted) return;
          const best = result.candidates.find(candidate => candidate.precision === "ROOFTOP") ?? result.candidates[0];
          if (best) { setPin({ lat: best.lat, lng: best.lng }); setLookup("done"); }
          else { setPin(null); setLookup("error"); }
        })
        .catch(() => { if (!controller.signal.aborted) { setPin(null); setLookup("error"); } });
    }, LOOKUP_DEBOUNCE_MS);
    return () => { clearTimeout(timer); controller.abort(); };
  }, [depotFieldsReady, address]);

  function updateAddress(field: keyof Address, value: string) {
    setAddress(current => ({ ...current, [field]: value }));
    setPin(null);
    setLookup("idle");
  }

  async function run(area: "dealership" | "depot" | "policy", action: () => Promise<void>) {
    setBusy(true); setMessage(""); setError(null);
    try { await action(); router.refresh(); }
    catch (failure) { setError({ area, text: errorMessage(failure) }); }
    finally { setBusy(false); }
  }
  async function createDealership() {
    await run("dealership", async () => {
      const response = await fetch("/api/dealerships", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ name }) });
      const result = await readResponse(response, created);
      setDealershipId(result.id); setName(""); setMessage("Dealership created. Add its depots below.");
    });
  }
  async function createDepot() {
    if (!pin) return;
    await run("depot", async () => {
      const response = await fetch("/api/depots", { method: "POST", headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ dealershipId, metroId, name: depotName, address, confirmedPin: pin, departure, returnTo }) });
      await readResponse(response, created);
      setDepotName(""); setAddress(EMPTY_ADDRESS); setPin(null);
      setMessage("Depot created.");
    });
  }
  async function savePolicy(item: Depot, nextDeparture: Anchor, nextReturn: Anchor) {
    await run("policy", async () => {
      const response = await fetch(`/api/depots/${item.id}/policy`, { method: "PUT", headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ departure: nextDeparture, returnTo: nextReturn }) });
      const result = await readResponse(response, saved);
      setEditingDepotId(null);
      setMessage(`Saved ${item.name} policy for ${result.effectiveDate}. Booked routes were checked.`);
    });
  }

  const filteredDealerships = dealerships.filter(item => item.name.toLowerCase().includes(search.toLowerCase()));

  return (
    <div className={styles.main}>
      <div className={styles.top}>
        <p className={styles.eyebrow}>Setup</p>
        <h1 className={styles.title}>Dealerships and depots</h1>
        <p className={styles.subtitle}>Create a dealership, then add one or more depots under it and set each depot&apos;s own departure and return policy.</p>
      </div>

      {/* step 1: add dealership */}
      <div className={`${ui.card} ${styles.card}`}>
        <div className={styles.dealershipRow}>
          <div className={styles.stepHead}>
            <span className={styles.stepBadge}>1</span>
            <h2 className={styles.stepTitle}>Add dealership</h2>
          </div>
          <input
            className={`${styles.textInput} ${styles.nameInput}`}
            placeholder="Foothills Nissan"
            value={name}
            onChange={event => setName(event.target.value)}
          />
          <button type="button" className={`${ui.button} ${ui.buttonBrand}`} disabled={busy || !name.trim()} onClick={createDealership}>
            Create dealership
          </button>
        </div>
        {error?.area === "dealership" && <p role="alert" className={styles.errorMessage}>{error.text}</p>}
      </div>

      {/* step 2: add depot */}
      <div className={`${ui.card} ${styles.card} ${styles.depotCard}`}>
        <div className={styles.depotCardHead}>
          <span className={styles.stepBadge}>2</span>
          <h2 className={styles.stepTitle}>Add depot</h2>
        </div>

        <div className={styles.fieldGrid3}>
          <div className={styles.field}>
            <label htmlFor="depot-dealership">Dealership</label>
            <select id="depot-dealership" className={styles.textInput} value={dealershipId} onChange={event => setDealershipId(event.target.value)}>
              <option value="">Choose a dealership</option>
              {dealerships.map(item => <option key={item.id} value={item.id}>{item.name}</option>)}
            </select>
          </div>
          <div className={styles.field}>
            <label htmlFor="depot-metro">Metro</label>
            <select id="depot-metro" className={styles.textInput} value={metroId} onChange={event => setMetroId(event.target.value)}>
              {metros.map(item => <option key={item.id} value={item.id}>{item.name}</option>)}
            </select>
          </div>
          <div className={styles.field}>
            <label htmlFor="depot-name">Depot name</label>
            <input id="depot-name" className={styles.textInput} value={depotName} onChange={event => setDepotName(event.target.value)} />
          </div>
        </div>

        <div className={styles.fieldGrid4}>
          <div className={styles.field}>
            <label htmlFor="depot-line1">Street address</label>
            <input id="depot-line1" className={styles.textInput} value={address.line1} onChange={event => updateAddress("line1", event.target.value)} />
          </div>
          <div className={styles.field}>
            <label htmlFor="depot-city">City</label>
            <input id="depot-city" className={styles.textInput} value={address.city} onChange={event => updateAddress("city", event.target.value)} />
          </div>
          <div className={styles.field}>
            <label htmlFor="depot-state">State</label>
            <input id="depot-state" className={styles.textInput} value={address.state} onChange={event => updateAddress("state", event.target.value)} />
          </div>
          <div className={styles.field}>
            <label htmlFor="depot-postal">Postal code</label>
            <input id="depot-postal" className={styles.textInput} value={address.postalCode} onChange={event => updateAddress("postalCode", event.target.value)} />
          </div>
        </div>

        <div className={styles.mapSection}>
          <div className={styles.mapSectionHead}>
            <p className={ui.sectionLabel} style={{ margin: 0 }}>Confirm depot pin</p>
            {lookup === "loading" && <span className={styles.mapStatus}>Locating address&hellip;</span>}
            {lookup === "done" && (
              <span className={styles.mapStatus}>
                <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round"><path d="M5 12.5l4.5 4.5L19 7" /></svg>
                Located automatically from the address above
              </span>
            )}
            {lookup === "error" && <span className={`${styles.mapStatus} ${styles.mapStatusError}`}>No verified pin found for this address.</span>}
          </div>
          <div className={styles.mapBoxWrap}>
            {pin ? (
              <>
                <DepotPinMap lat={pin.lat} lng={pin.lng} onDrag={(lat, lng) => setPin({ lat, lng })} />
                <span className={styles.mapHint}>
                  <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round"><path d="M8 9l-4 4 4 4M16 9l4 4-4 4M14 4l-4 16" /></svg>
                  Drag pin to adjust
                </span>
              </>
            ) : (
              <div className={styles.mapPlaceholder}>
                {depotFieldsReady ? (lookup === "error" ? "Check the address and try again." : "Locating this address on the map…") : "Fill in the dealership, metro, depot name, and address to locate the pin."}
              </div>
            )}
          </div>
        </div>

        <div className={styles.policyRow}>
          <div className={styles.anchorGroup}>
            <AnchorToggle label="Departure" value={departure} onChange={setDeparture} />
            <AnchorToggle label="Return" value={returnTo} onChange={setReturnTo} />
          </div>
          <button type="button" className={`${ui.button} ${ui.buttonBrand}`} disabled={busy || !pin} onClick={createDepot}>
            Create depot
          </button>
        </div>
        {error?.area === "depot" && <p role="alert" className={styles.errorMessage}>{error.text}</p>}
        <p className={styles.nextSteps}>Next onboarding steps: add each technician with a verified home address, assign them to a depot, and choose qualifications on the Technicians page.</p>
      </div>

      {/* existing dealerships & depots */}
      <div className={`${ui.card} ${styles.listCard}`}>
        <div className={styles.listHead}>
          <h2 className={styles.listTitle}>Existing dealerships &amp; depots</h2>
          <label className={styles.search}>
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="#8c9892" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><circle cx="10.5" cy="10.5" r="6" /><path d="M20 20l-4.8-4.8" /></svg>
            <input placeholder="Search dealerships" value={search} onChange={event => setSearch(event.target.value)} />
          </label>
        </div>

        <div className={styles.columnHeader}>
          <span className={`${styles.colLabel} ${styles.colName}`}>Dealership / depot</span>
          <span className={`${styles.colLabel} ${styles.colMetro}`}>Metro</span>
          <span className={`${styles.colLabel} ${styles.colTech}`}>Technicians</span>
          <span className={`${styles.colLabel} ${styles.colDeparture}`}>Today departure</span>
          <span className={`${styles.colLabel} ${styles.colReturn}`}>Today return</span>
          <span className={styles.colActions} />
        </div>

        {filteredDealerships.length === 0 && <p className={styles.empty}>No dealerships found.</p>}
        {filteredDealerships.map(item => (
          <div key={item.id}>
            <div className={styles.dealershipGroup}>
              <Avatar name={item.name} size={30} />
              <span className={styles.dealershipGroupName}>{item.name}</span>
              <span className={styles.depotDetail}>{item.technicianCount} active technicians today</span>
            </div>
            {depots.filter(depot => depot.dealershipId === item.id).map(depot => (
              <DepotRow
                key={depot.id}
                depot={depot}
                metroName={metros.find(metro => metro.id === depot.metroId)?.name ?? depot.metroId}
                editing={editingDepotId === depot.id}
                busy={busy}
                error={error?.area === "policy" && editingDepotId === depot.id ? error.text : null}
                onToggleEdit={() => { setError(null); setEditingDepotId(current => (current === depot.id ? null : depot.id)); }}
                onSave={savePolicy}
              />
            ))}
            {!depots.some(depot => depot.dealershipId === item.id) && <p className={styles.empty}>No depots configured.</p>}
          </div>
        ))}
      </div>

      {message && <p role="status" className={styles.savedMessage}>{message}</p>}
    </div>
  );
}

function AnchorToggle({ label, value, onChange }: { label: string; value: Anchor; onChange: (value: Anchor) => void }) {
  return (
    <div>
      <p className={styles.toggleLabel}>{label}</p>
      <div className={styles.toggleGroup} role="group" aria-label={label}>
        <button type="button" aria-pressed={value === "HOME"} className={`${styles.toggleBtn} ${value === "HOME" ? styles.toggleBtnOn : ""}`} onClick={() => onChange("HOME")}>Home</button>
        <button type="button" aria-pressed={value === "DEPOT"} className={`${styles.toggleBtn} ${value === "DEPOT" ? styles.toggleBtnOn : ""}`} onClick={() => onChange("DEPOT")}>Depot</button>
      </div>
    </div>
  );
}

function DepotRow({ depot, metroName, editing, busy, error, onToggleEdit, onSave }: {
  depot: Depot; metroName: string; editing: boolean; busy: boolean; error: string | null;
  onToggleEdit: () => void; onSave: (depot: Depot, departure: Anchor, returnTo: Anchor) => Promise<void>;
}) {
  const [departure, setDeparture] = useState<Anchor>(depot.upcomingPolicy?.departure ?? depot.departure);
  const [returnTo, setReturnTo] = useState<Anchor>(depot.upcomingPolicy?.returnTo ?? depot.returnTo);

  useEffect(() => { setDeparture(depot.upcomingPolicy?.departure ?? depot.departure); setReturnTo(depot.upcomingPolicy?.returnTo ?? depot.returnTo); }, [depot.departure, depot.returnTo, depot.upcomingPolicy]);

  return (
    <div className={editing ? styles.depotRowExpanded : styles.depotRow}>
      <div style={{ display: "flex", alignItems: "center" }}>
        <span className={`${styles.depotName} ${styles.colName}`}>{depot.name}</span>
        <span className={`${styles.depotDetail} ${styles.colMetro}`}>{metroName}</span>
        <span className={`${styles.depotDetail} ${styles.colTech}`}>{depot.technicianCount} active</span>
        <span className={styles.colDeparture}><AnchorPill value={depot.departure} /></span>
        <span className={styles.colReturn}><AnchorPill value={depot.returnTo} /></span>
        <span className={styles.colActions}>
          <button type="button" className={`${styles.editButton} ${editing ? styles.editButtonOn : ""}`} onClick={onToggleEdit}>
            {editing ? "Editing policy" : "Edit policy"}
          </button>
        </span>
      </div>
      {depot.upcomingPolicy && <p className={styles.upcomingNote}>Scheduled {depot.upcomingPolicy.effectiveDate}: {depot.upcomingPolicy.departure} departure, {depot.upcomingPolicy.returnTo} return</p>}
      {editing && (
        <div className={styles.policyPanel}>
          <p className={ui.sectionLabel} style={{ marginBottom: 12 }}>Current policy from {depot.policyEffectiveDate}: {depot.departure} departure, {depot.returnTo} return</p>
          {depot.upcomingPolicy && <p className={ui.sectionLabel}>Scheduled for {depot.upcomingPolicy.effectiveDate}: {depot.upcomingPolicy.departure} departure, {depot.upcomingPolicy.returnTo} return. Saving replaces this scheduled policy.</p>}
          <div className={styles.policyRow}>
            <div className={styles.anchorGroup}>
              <AnchorToggle label="Departure" value={departure} onChange={setDeparture} />
              <AnchorToggle label="Return" value={returnTo} onChange={setReturnTo} />
            </div>
            <div style={{ display: "flex", gap: 10 }}>
              <button type="button" className={ui.button} onClick={onToggleEdit}>Cancel</button>
              <button type="button" className={`${ui.button} ${ui.buttonBrand}`}
                disabled={busy || (departure === (depot.upcomingPolicy?.departure ?? depot.departure) && returnTo === (depot.upcomingPolicy?.returnTo ?? depot.returnTo))}
                onClick={() => void onSave(depot, departure, returnTo)}>
                Save policy change
              </button>
            </div>
          </div>
          <div className={styles.cutoffNote}>
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round"><path d="M12 9v4M12 16.5h.01" /><path d="M10.3 4.5L2.9 18a2 2 0 0 0 1.7 3h14.8a2 2 0 0 0 1.7-3L13.7 4.5a2 2 0 0 0-3.4 0z" /></svg>
            <span>{depot.upcomingPolicy ? `This edit replaces the policy on ${depot.upcomingPolicy.effectiveDate}.` : "New policy takes effect today before 6:00 AM CT, or tomorrow after the cutoff."} Booked days on or after that date are replanned.</span>
          </div>
          {error && <p role="alert" className={styles.errorMessage}>{error}</p>}
        </div>
      )}
    </div>
  );
}

function AnchorPill({ value }: { value: Anchor }) {
  return <span className={`${ui.pill} ${value === "DEPOT" ? ui.pillBrand : ui.pillNeutral}`}>{value === "DEPOT" ? "Depot" : "Home"}</span>;
}
