"use client";

import ui from "../components/ui.module.css";
import DepotPinMap from "../dealerships/DepotPinMap";
import { inputStyle } from "./inputStyle";
import styles from "./technicians.module.css";
import type { HomeAddress, HomeAddressState } from "./useHomeAddressLookup";

const FIELDS = ["line1", "city", "state", "postalCode"] as const satisfies ReadonlyArray<keyof HomeAddress>;

export default function HomeAddressFields({ state, idPrefix, hasSavedPin = false }: { state: HomeAddressState; idPrefix: string; hasSavedPin?: boolean }) {
  const { address, updateAddress, lookup, lookupError, selectedCandidate, pin, pinMoved, pinConfirmed, mapAvailable } = state;
  return (
    <>
      <div className={styles.fieldGrid}>{FIELDS.map(field =>
        <div key={field}><label className={ui.sectionLabel} htmlFor={`${idPrefix}-${field}`}>{field === "line1" ? "Street address" : field === "postalCode" ? "Postal code" : field}</label>
          <input id={`${idPrefix}-${field}`} value={address[field]} onChange={event => updateAddress(field, event.target.value)} style={inputStyle} /></div>)}</div>
      <div style={{ margin: "12px 0" }}>
        {hasSavedPin && !state.dirty ? <p>Saved home pin is on file. Change the address to look up a new pin.</p> :
          <p>{lookup === "loading" ? "Locating home address…" : lookup === "error" ? lookupError || "No verified pin found for this address." : selectedCandidate?.precision === "APPROXIMATE" ? `This street was found, but house number ${address.line1.match(/^\s*\d+[A-Za-z]?/)?.[0]?.trim() ?? ""} was not verified. Move the home pin to the correct house and confirm it.` : selectedCandidate ? "Review the located home pin. Drag it to adjust the location." : "Fill in the complete home address to locate it on the map."}</p>}
        {pin && selectedCandidate && <>
          <div style={{ height: 280, border: "1px solid var(--line)", borderRadius: 8, overflow: "hidden" }}>
            <DepotPinMap key={`${selectedCandidate.lat}-${selectedCandidate.lng}`} lat={pin.lat} lng={pin.lng} requireInteractive onAvailableChange={state.setMapAvailable}
              onDrag={state.movePin} />
          </div>
          {!mapAvailable && <p role="status">Map placement is unavailable at this address. A located house pin can still be used; manual confirmation requires the map.</p>}
          {(selectedCandidate.precision === "APPROXIMATE" || pinMoved) && <button type="button" className={ui.button} disabled={!mapAvailable} onClick={state.confirmPin}>Confirm home pin</button>}
          {pinConfirmed && <span role="status"> Home pin confirmed</span>}
        </>}
      </div>
    </>
  );
}
