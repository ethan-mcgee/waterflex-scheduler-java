"use client";

import { useCallback, useEffect, useId, useMemo, useRef, useState, type FormEvent } from "react";
import { bookingLocationResponse, errorMessage, newWorkOrderResponse, readResponse } from "@/lib/contracts";
import { MAX_DURATION_MIN, MIN_DURATION_MIN, suggestedMinutes } from "@/lib/reasonCodesCore";
import { useAbortOnUnmount } from "@/app/portal/useAbortOnUnmount";
import AccountPicker, { accountAddress, type AccountView } from "./AccountPicker";
import AddressPinMap, { type PinCandidate, type ServiceArea } from "./AddressPinMap";
import ReasonCodePicker, { type ReasonCodeOption } from "./ReasonCodePicker";
import calendar from "./calendar.module.css";
import styles from "./workOrderForm.module.css";

/** A work order for a WaterFlex account, or for an address typed in and located here. */
type Mode = "account" | "address";

interface FormState {
  firstName: string;
  lastName: string;
  email: string;
  phone: string;
  line1: string;
  line2: string;
  city: string;
  state: string;
  postalCode: string;
  /** The reason codes in order; "" is a place not chosen yet. */
  reasonCodes: string[];
  /** The dispatcher's time allotted as typed, or null to follow the codes' suggestion. */
  allotted: string | null;
  workOrderType: string;
  purchaseOrder: string;
  technicianMessage: string;
  customerRequest: string;
  callFirst: boolean;
}

const EMPTY_FORM: FormState = {
  firstName: "", lastName: "", email: "", phone: "", line1: "", line2: "", city: "", state: "NE", postalCode: "",
  reasonCodes: [""], allotted: null, workOrderType: "", purchaseOrder: "", technicianMessage: "", customerRequest: "", callFirst: false,
};

const newRequestId = () => crypto.randomUUID();

/** The time allotted the form would send, or why it cannot be sent. */
type Allotted = { minutes: number; overridden: boolean } | { problem: string } | null;

function allottedTime(form: FormState, suggested: number | null): Allotted {
  if (form.allotted === null) {
    if (suggested === null) return null;
    if (suggested > MAX_DURATION_MIN) return { problem: `These reason codes add up to ${suggested} minutes, more than a day can hold. Enter a shorter time allotted.` };
    return { minutes: suggested, overridden: false };
  }
  const minutes = /^\d+$/.test(form.allotted.trim()) ? Number(form.allotted.trim()) : Number.NaN;
  if (!Number.isSafeInteger(minutes) || minutes < MIN_DURATION_MIN || minutes > MAX_DURATION_MIN)
    return { problem: `Time allotted must be a whole number of minutes from ${MIN_DURATION_MIN} to ${MAX_DURATION_MIN}.` };
  return { minutes, overridden: true };
}

/** What every work order says beyond its customer and place; empty fields are left out rather than sent blank. */
function workOrderDetails(form: FormState, allotted: { minutes: number; overridden: boolean }) {
  const [first, ...extra] = form.reasonCodes.filter(code => code !== "");
  const optional = (key: string, value: string) => value.trim() === "" ? {} : { [key]: value.trim() };
  return { serviceCode: first, ...(extra.length > 0 ? { extraReasonCodes: extra } : {}), ...(allotted.overridden ? { durationMin: allotted.minutes } : {}),
    ...optional("workOrderType", form.workOrderType), ...optional("purchaseOrder", form.purchaseOrder),
    ...optional("technicianMessage", form.technicianMessage), ...optional("customerRequest", form.customerRequest), callFirst: form.callFirst };
}

/**
 * The new work order form, on the schedule: the customer (a WaterFlex account, or a typed address whose pin is
 * confirmed on a map), up to three reason codes, the time allotted and the work order's details. "Create and place"
 * takes the work order in and hands it to the calendar, which asks the booking service for recommended times and shows
 * every place to choose by hand. "Request follow-up" takes it in without a location. One submission makes one job.
 */
export default function NewWorkOrderDrawer({ services, accountsAvailable, onClose, onCreated }: {
  services: ReasonCodeOption[];
  accountsAvailable: boolean;
  onClose: () => void;
  /** The work order was taken in: to be placed now, or, with `followUp`, waiting on a call to locate it. */
  onCreated: (created: { jobId: string; label: string; followUp: boolean }) => void;
}) {
  const activeRequest = useRef<AbortController | null>(null);
  useAbortOnUnmount(useCallback(() => activeRequest.current?.abort(), []));
  const requestSignal = () => {
    activeRequest.current?.abort();
    activeRequest.current = new AbortController();
    return activeRequest.current.signal;
  };
  const fieldId = useId();
  const [step, setStep] = useState<"form" | "pin">("form");
  const [mode, setMode] = useState<Mode>(accountsAvailable ? "account" : "address");
  const [account, setAccount] = useState<AccountView | null>(null);
  const [form, setForm] = useState<FormState>(EMPTY_FORM);
  const [requestId, setRequestId] = useState(newRequestId);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [pinCandidates, setPinCandidates] = useState<PinCandidate[]>([]);
  const [pin, setPin] = useState<{ lat: number; lng: number } | null>(null);
  const [serviceArea, setServiceArea] = useState<ServiceArea | null>(null);
  const [mapAvailable, setMapAvailable] = useState(false);
  const [mapVersion, setMapVersion] = useState(0);
  // An edit makes every answer still on its way stale; a switch between placing and follow-up is another request.
  const revision = useRef(0);
  const submittedMode = useRef<"place" | "followUp" | null>(null);

  // Escape closes the drawer unless a request is on its way.
  useEffect(() => {
    const onKey = (event: KeyboardEvent) => { if (event.key === "Escape" && !submitting) onClose(); };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [onClose, submitting]);

  function clearLocation() {
    revision.current++; submittedMode.current = null;
    activeRequest.current?.abort();
    setPinCandidates([]); setPin(null); setServiceArea(null); setMapAvailable(false); setSubmitting(false);
    setRequestId(newRequestId()); setError(null);
  }

  const chosen = useMemo(() => form.reasonCodes.filter(code => code !== "").map(code => services.find(item => item.code === code))
    .filter(item => item !== undefined), [services, form.reasonCodes]);
  const suggested = chosen.length > 0 ? suggestedMinutes(chosen) : null;
  const allotted = allottedTime(form, suggested);
  const ready = allotted !== null && "minutes" in allotted ? allotted : null;

  function updateField<K extends keyof FormState>(key: K, value: FormState[K]) {
    clearLocation();
    setForm(current => ({ ...current, [key]: value }));
  }

  function chooseAccount(next: AccountView | null) {
    clearLocation();
    setAccount(next);
    // The account's own phone and email fill empty fields; its name stays a reference, since it is not split into parts.
    if (next !== null) setForm(current => ({ ...current, phone: current.phone || (next.phone ?? ""), email: current.email || (next.email ?? "") }));
  }

  function switchMode(next: Mode) {
    if (next === mode) return;
    clearLocation(); setAccount(null); setMode(next);
  }

  async function lookupLocation() {
    const current = ++revision.current;
    setError(null); setSubmitting(true); setPin(null); setMapAvailable(false);
    try {
      const response = await fetch("/api/book/location", {
        signal: requestSignal(), method: "POST", headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ line1: form.line1, city: form.city, state: form.state, postalCode: form.postalCode }),
      });
      const data = await readResponse(response, bookingLocationResponse);
      if (current !== revision.current) return;
      setPinCandidates(data.candidates); setServiceArea(data.serviceArea);
      setPin(data.candidates[0] ?? null); setRequestId(newRequestId()); setMapVersion(version => version + 1); setStep("pin");
    } catch (failure) { if (current === revision.current) setError(errorMessage(failure)); }
    finally { if (current === revision.current) setSubmitting(false); }
  }

  /** Takes the work order in: at the account's location, at the confirmed pin, or as a follow-up. */
  async function create(followUp: boolean) {
    const first = chosen[0];
    if (ready === null || first === undefined) return;
    if (mode === "account" && (account === null || (!followUp && account.location === null))) return;
    if (mode === "address" && !followUp && (pin === null || !mapAvailable)) return;
    const intent = followUp ? "followUp" : "place";
    const submissionId = submittedMode.current !== null && submittedMode.current !== intent ? newRequestId() : requestId;
    submittedMode.current = intent; setRequestId(submissionId);
    const current = revision.current;
    const contact = { requestId: submissionId, firstName: form.firstName, lastName: form.lastName, email: form.email, phone: form.phone };
    const place = mode === "account" && account !== null ? { accountNumber: account.accountNumber, ...(followUp ? { followUp: true } : {}) }
      : { line1: form.line1, ...(form.line2.trim() === "" ? {} : { line2: form.line2 }), city: form.city, state: form.state, postalCode: form.postalCode,
        ...(followUp || pin === null ? { followUp: true } : { confirmedPin: { ...pin, manuallyConfirmed: true } }) };
    setSubmitting(true);
    setError(null);
    try {
      const response = await fetch("/api/schedule/placement/job", {
        signal: requestSignal(), method: "POST", headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ ...contact, ...place, ...workOrderDetails(form, ready) }),
      });
      const created = await readResponse(response, newWorkOrderResponse);
      if (current !== revision.current) return;
      onCreated({ jobId: created.jobId, label: `${form.firstName.trim()} ${form.lastName.trim()}, ${first.name}`, followUp: created.followUp });
    } catch (failure) { if (current === revision.current) setError(errorMessage(failure, "The work order could not be created. Try again.")); }
    finally { if (current === revision.current) setSubmitting(false); }
  }

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (mode === "account") void create(false);
    else void lookupLocation();
  }

  const progress = submitting ? <p role="status" className={styles.fieldHint}>
    {step === "form" && mode === "address" ? "Looking up the address..." : "Creating the work order..."}</p> : null;

  const pinStep = <>
    <p>{form.line1}, {form.city}, {form.state} {form.postalCode}</p>
    <p className={styles.fieldHint}>Check the service location. Drag the pin or click the map to place it at the driveway entrance.</p>
    {serviceArea !== null && <AddressPinMap key={mapVersion} candidates={pinCandidates} area={serviceArea} pin={pin}
      onPlace={position => { setPin(position); setRequestId(newRequestId()); }} onAvailableChange={setMapAvailable} />}
    {pin === null && <p>No matching address was found. Click the map to place the pin, or request follow-up.</p>}
    {!mapAvailable && <p role="status">Map unavailable or loading. Pin confirmation is blocked. Retry the map when tiles are available.</p>}
    {pinCandidates.map((candidate, index) => <button key={`${candidate.lat}-${candidate.lng}`} type="button" className={styles.linkButton}
      disabled={submitting} onClick={() => { setPin(candidate); setRequestId(newRequestId()); }}>Use location {index + 1}</button>)}
    <button className={styles.button} type="button" onClick={() => void create(false)} disabled={submitting || pin === null || !mapAvailable}>
      {submitting ? "Creating..." : "Confirm pin, create and place"}
    </button>
    <button type="button" className={styles.linkButton} disabled={submitting} onClick={() => { clearLocation(); setStep("form"); }}>Edit address</button>
    <button type="button" className={styles.linkButton} disabled={submitting} onClick={() => void lookupLocation()}>Retry address lookup</button>
    <button type="button" className={styles.linkButton} disabled={submitting}
      onClick={() => { setMapAvailable(false); setMapVersion(version => version + 1); }}>Retry map</button>
    <button type="button" className={styles.linkButton} disabled={submitting} onClick={() => void create(true)}>Request follow-up</button>
  </>;

  const textField = (key: "firstName" | "lastName" | "email" | "phone" | "city" | "postalCode", label: string, type = "text") =>
    <div className={styles.field}>
      <label className={styles.label} htmlFor={`${fieldId}-${key}`}>{label}</label>
      <input id={`${fieldId}-${key}`} className={styles.input} type={type} required autoComplete="off" value={form[key]}
        onChange={event => updateField(key, event.target.value)} />
    </div>;
  const accountBlocked = mode === "account" && account === null;
  const noCodes = services.length === 0 || form.reasonCodes[0] === "";

  const formStep = <form onSubmit={submit} aria-label="New work order">
    <fieldset className={styles.plainFieldset}>
      <div className={styles.modeSwitch} role="group" aria-label="Customer">
        <button type="button" aria-pressed={mode === "account"} disabled={submitting} onClick={() => switchMode("account")}>WaterFlex account</button>
        <button type="button" aria-pressed={mode === "address"} disabled={submitting} onClick={() => switchMode("address")}>Typed address</button>
      </div>
      {mode === "account" && !accountsAvailable && <p className={styles.fieldHint}>No WaterFlex accounts are imported for this client yet.</p>}
      {mode === "account" && <AccountPicker account={account} onChoose={chooseAccount} disabled={submitting} />}
      {mode === "account" && account?.name != null && <p className={styles.fieldHint}>Account name: {account.name}</p>}

      <div className={styles.row}>{textField("firstName", "First name")}{textField("lastName", "Last name")}</div>
      <div className={styles.row}>{textField("email", "Email", "email")}{textField("phone", "Phone", "tel")}</div>

      {mode === "address" && <>
        <div className={styles.field}>
          <label className={styles.label} htmlFor={`${fieldId}-line1`}>Address</label>
          <input id={`${fieldId}-line1`} className={styles.input} required placeholder="Street address" autoComplete="off" value={form.line1}
            onChange={event => updateField("line1", event.target.value)} style={{ marginBottom: "0.5rem" }} />
          <input className={styles.input} placeholder="Apt, suite, etc. (optional)" aria-label="Apt, suite, etc." autoComplete="off" value={form.line2}
            onChange={event => updateField("line2", event.target.value)} />
        </div>
        <div className={styles.row3}>
          {textField("city", "City")}
          <div className={styles.field}>
            <label className={styles.label} htmlFor={`${fieldId}-state`}>State</label>
            <input id={`${fieldId}-state`} className={styles.input} required maxLength={2} autoComplete="off" value={form.state}
              onChange={event => updateField("state", event.target.value.toUpperCase())} />
          </div>
          {textField("postalCode", "ZIP")}
        </div>
      </>}

      <ReasonCodePicker options={services} value={form.reasonCodes} disabled={submitting} onChange={codes => updateField("reasonCodes", codes)} />

      <div className={styles.row}>
        <div className={styles.field}>
          <label className={styles.label} htmlFor={`${fieldId}-allotted`}>Time allotted (minutes)</label>
          <input id={`${fieldId}-allotted`} className={styles.input} inputMode="numeric"
            value={form.allotted ?? (suggested === null ? "" : String(suggested))}
            onChange={event => updateField("allotted", event.target.value)} aria-describedby={`${fieldId}-allotted-hint`} />
          <div id={`${fieldId}-allotted-hint`} className={styles.fieldHint}>
            {suggested === null ? "Choose a reason code to see the suggested time." : `Suggested from the reason codes: ${suggested} min.`}
            {form.allotted !== null && suggested !== null && <> <button type="button" className={styles.inlineButton}
              onClick={() => updateField("allotted", null)}>Use the suggested time</button></>}
          </div>
          {allotted !== null && "problem" in allotted && <div role="alert" className={styles.error}>{allotted.problem}</div>}
        </div>
        <div className={styles.field}>
          <label className={styles.label} htmlFor={`${fieldId}-type`}>Work order type</label>
          <input id={`${fieldId}-type`} className={styles.input} maxLength={80} value={form.workOrderType}
            onChange={event => updateField("workOrderType", event.target.value)} />
        </div>
      </div>
      <div className={styles.row}>
        <div className={styles.field}>
          <label className={styles.label} htmlFor={`${fieldId}-po`}>Purchase order</label>
          <input id={`${fieldId}-po`} className={styles.input} maxLength={80} value={form.purchaseOrder}
            onChange={event => updateField("purchaseOrder", event.target.value)} />
        </div>
        <label className={styles.checkField}>
          <input type="checkbox" checked={form.callFirst} onChange={event => updateField("callFirst", event.target.checked)} />
          Call the customer before arriving
        </label>
      </div>
      <div className={styles.field}>
        <label className={styles.label} htmlFor={`${fieldId}-request`}>Customer request</label>
        <textarea id={`${fieldId}-request`} className={styles.input} rows={2} maxLength={1000} value={form.customerRequest}
          onChange={event => updateField("customerRequest", event.target.value)} />
      </div>
      <div className={styles.field}>
        <label className={styles.label} htmlFor={`${fieldId}-message`}>Message to the technician</label>
        <textarea id={`${fieldId}-message`} className={styles.input} rows={2} maxLength={1000} value={form.technicianMessage}
          onChange={event => updateField("technicianMessage", event.target.value)} />
      </div>

      {mode === "account" && account !== null && <p className={styles.fieldHint}>Service address: {accountAddress(account)}</p>}
      {mode === "account"
        ? <button className={styles.button} type="submit" disabled={submitting || accountBlocked || noCodes || ready === null || account?.location === null}>
          {submitting ? "Creating..." : "Create and place"}
        </button>
        : <button className={styles.button} type="submit" disabled={submitting || noCodes || ready === null}>
          {submitting ? "Looking up address..." : "Review service location"}
        </button>}
      <button type="button" className={styles.linkButton} disabled={submitting || accountBlocked || noCodes || ready === null} onClick={event => {
        if (event.currentTarget.form?.reportValidity()) void create(true);
      }}>Request follow-up</button>
    </fieldset>
  </form>;

  return (
    <div className={calendar.panelBackdrop} role="presentation">
      <aside className={`${calendar.panel} ${calendar.panelWide}`} role="dialog" aria-modal="true" aria-labelledby={`${fieldId}-title`}>
        <div className={calendar.panelHeader}>
          <h2 id={`${fieldId}-title`}>{step === "pin" ? "Confirm the service location" : "New work order"}</h2>
          <button className={calendar.close} type="button" aria-label="Close new work order" disabled={submitting} onClick={onClose}>×</button>
        </div>
        {step === "form" && <p className={styles.fieldHint}>
          Find the customer&apos;s WaterFlex account, choose the reason codes and the time to allot, then create it and place it on the calendar.</p>}
        {error !== null && <div role="alert" className={styles.error}>{error}</div>}
        {progress}
        {step === "pin" ? pinStep : formStep}
      </aside>
    </div>
  );
}
