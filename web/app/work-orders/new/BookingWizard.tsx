"use client";

import { offersResponse, bookingLocationResponse, bookingResponse, bookingFailure, selection as selectionSchema, success, readResponse, errorMessage, required, date as dateContract } from "@/lib/contracts";
import { appointmentSearchMessage, bookingApiStart, recordBookingApiDuration } from "@/lib/appointmentSearch";
import { MAX_DURATION_MIN, MIN_DURATION_MIN, reasonCodesLabel, suggestedMinutes } from "@/lib/reasonCodesCore";
import { useCallback, useEffect, useId, useMemo, useRef, useState } from "react";
import { useAbortOnUnmount } from "@/app/portal/useAbortOnUnmount";
import { PageHeader } from "@/app/portal/ui";
import styles from "./booking.module.css";
import AddressPinMap, { type PinCandidate, type ServiceArea } from "./AddressPinMap";
import AccountPicker, { accountAddress, type AccountView } from "./AccountPicker";
import ReasonCodePicker, { type ReasonCodeOption } from "./ReasonCodePicker";

interface SlotOffer {
  offerId: string;
  date: string;
  windowStart: string;
  windowEnd: string;
  expiresAt: string;
}

type Step = "form" | "pin" | "slots" | "confirmed" | "pending";
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

function newRequestId(): string {
  return globalThis.crypto?.randomUUID?.() ?? `${Date.now()}-${Math.random()}`;
}

const EMPTY_FORM: FormState = {
  firstName: "", lastName: "", email: "", phone: "", line1: "", line2: "", city: "", state: "NE", postalCode: "",
  reasonCodes: [""], allotted: null, workOrderType: "", purchaseOrder: "", technicianMessage: "", customerRequest: "", callFirst: false,
};

function formatDay(dateOnly: string): string {
  // `dateOnly` is a bare "YYYY-MM-DD" (the engine's `day` field, not a
  // timestamp). `new Date("YYYY-MM-DD")` parses that as UTC midnight,
  // which toLocaleDateString then renders in the browser's local
  // timezone - rolling back to the *previous* calendar day for anyone
  // west of UTC. Parse the components directly as a local date instead.
  const parts = dateContract.parse(dateOnly).split("-").map(Number);
  const [year, month, day] = [required(parts[0]), required(parts[1]), required(parts[2])];
  return new Date(year, month - 1, day).toLocaleDateString(undefined, {
    weekday: "long",
    month: "long",
    day: "numeric",
  });
}

function formatWindow(startIso: string, endIso: string): string {
  const opts: Intl.DateTimeFormatOptions = { hour: "numeric", minute: "2-digit" };
  return `${new Date(startIso).toLocaleTimeString(undefined, opts)} – ${new Date(
    endIso
  ).toLocaleTimeString(undefined, opts)}`;
}

function secondsRemaining(expiresAtIso: string, nowMs: number): number {
  return Math.max(0, Math.round((new Date(expiresAtIso).getTime() - nowMs) / 1000));
}

function formatCountdown(seconds: number): string {
  const m = Math.floor(seconds / 60);
  const s = seconds % 60;
  return `${m}:${s.toString().padStart(2, "0")}`;
}

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

export default function BookingWizard({ services, accountsAvailable }: { services: ReasonCodeOption[]; accountsAvailable: boolean }) {
  const activeRequest = useRef<AbortController | null>(null);
  useAbortOnUnmount(useCallback(() => activeRequest.current?.abort(), []));
  function requestSignal() {
    activeRequest.current?.abort();
    activeRequest.current = new AbortController();
    return activeRequest.current.signal;
  }
  const fieldId = useId();
  const [step, setStep] = useState<Step>("form");
  const [mode, setMode] = useState<Mode>(accountsAvailable ? "account" : "address");
  const [account, setAccount] = useState<AccountView | null>(null);
  const [form, setForm] = useState<FormState>(EMPTY_FORM);
  const [requestId, setRequestId] = useState(() => newRequestId());
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const [invalidOffers, setInvalidOffers] = useState(false);
  const [offers, setOffers] = useState<SlotOffer[]>([]);
  const [confirmingHoldId, setConfirmingHoldId] = useState<string | null>(null);
  const [releasing, setReleasing] = useState(false);
  const [confirmedOffer, setConfirmedOffer] = useState<SlotOffer | null>(null);
  const [appointmentId, setAppointmentId] = useState<string | null>(null);
  const [jobId, setJobId] = useState<string | null>(null);
  const [pinCandidates, setPinCandidates] = useState<PinCandidate[]>([]);
  const [pin, setPin] = useState<{ lat: number; lng: number } | null>(null);
  const [serviceArea, setServiceArea] = useState<ServiceArea | null>(null);
  const [mapAvailable, setMapAvailable] = useState(false);
  const [mapVersion, setMapVersion] = useState(0);
  const revision = useRef(0);
  const submittedMode = useRef<"pin" | "followUp" | null>(null);
  function clearLocation() {
    revision.current++; submittedMode.current = null;
    activeRequest.current?.abort();
    setPinCandidates([]); setPin(null); setServiceArea(null); setMapAvailable(false); setSubmitting(false);
    setRequestId(newRequestId()); setJobId(null); setError(null);
  }

  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    if (step !== "slots") return;
    const interval = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(interval);
  }, [step]);

  const chosen = useMemo(() => form.reasonCodes.filter(code => code !== "").map(code => services.find(item => item.code === code))
    .filter(item => item !== undefined), [services, form.reasonCodes]);
  const suggested = chosen.length > 0 ? suggestedMinutes(chosen) : null;
  const allotted = allottedTime(form, suggested);
  const ready = allotted !== null && "minutes" in allotted ? allotted : null;
  const codesLabel = reasonCodesLabel(chosen.map(item => item.name));

  function updateField<K extends keyof FormState>(key: K, value: FormState[K]) {
    clearLocation();
    setForm((f) => ({ ...f, [key]: value }));
  }

  function chooseAccount(next: AccountView | null) {
    clearLocation();
    setAccount(next);
    // The account's own phone and email fill empty fields; its name stays a reference, since it is not split into parts.
    if (next !== null) setForm(f => ({ ...f, phone: f.phone || (next.phone ?? ""), email: f.email || (next.email ?? "") }));
  }

  function switchMode(next: Mode) {
    if (next === mode) return;
    clearLocation(); setAccount(null); setMode(next);
  }

  async function lookupLocation() {
    const current = ++revision.current;
    setError(null); setSubmitting(true); setPin(null); setMapAvailable(false);
    try {
      const res = await fetch("/api/book/location", {
        signal: requestSignal(), method: "POST", headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ line1: form.line1, city: form.city, state: form.state, postalCode: form.postalCode }),
      });
      const data = await readResponse(res, bookingLocationResponse);
      if (current !== revision.current) return;
      setPinCandidates(data.candidates); setServiceArea(data.serviceArea);
      setPin(data.candidates[0] ?? null); setRequestId(newRequestId()); setMapVersion(v => v + 1); setStep("pin");
    } catch (error) { if (current === revision.current) setError(errorMessage(error)); }
    finally { if (current === revision.current) setSubmitting(false); }
  }

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    if (mode === "account") await submitWorkOrder(false);
    else await lookupLocation();
  }

  async function handleSelectSlot(offerId: string) {
    const started = bookingApiStart();
    setError(null);
    setConfirmingHoldId(offerId);
    try {
      const selection = await fetch("/api/book/select", {
        signal: requestSignal(),
        method: "POST", headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ jobId, offerId }),
      });
      const raw: unknown = await selection.json();
      if (!selection.ok) {
        const selected = bookingFailure.parse(raw);
        if (selected.pendingReference) { setJobId(selected.pendingReference); setStep("pending"); return; }
        setError(selected.error ?? "That window is no longer available. Please refresh your options.");
        if (selection.status === 409 && selected.offers) setOffers(selected.offers);
        setInvalidOffers(selected.search?.outcome !== "AVAILABLE");
        return;
      }
      const selected = selectionSchema.parse(raw);
      setConfirmedOffer(offers.find((o) => o.offerId === offerId) ?? null);
      setAppointmentId(selected.appointmentId);
      setStep("confirmed");
    } catch (error) {
      setError(errorMessage(error));
      setInvalidOffers(true);
    } finally {
      recordBookingApiDuration(started, "selection");
      setConfirmingHoldId(null);
    }
  }

  async function handleBackToForm() {
    if (submitting || confirmingHoldId !== null || releasing) return;
    if (!jobId || offers.length === 0) { clearLocation(); setStep("form"); return; }
    setReleasing(true);
    setError(null);
    try {
      const response = await fetch("/api/book/release", {
        method: "POST", headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ jobId, offerId: required(offers[0]).offerId }),
      });
      const result = await readResponse(response, success);
      if (!result.success) throw new Error("Could not release times. Please try Start over again.");
      clearLocation();
      setStep("form");
      setOffers([]);
      setRequestId(newRequestId());
      setJobId(null);
    } catch (error) {
      setError(errorMessage(error, "Could not release times. Please try Start over again."));
    } finally {
      setReleasing(false);
    }
  }

  async function refreshOffers() {
    if (!jobId) return;
    setSubmitting(true); setError(null);
    const started = bookingApiStart();
    try {
      const refreshed = await readResponse(await fetch("/api/book/refresh", { method: "POST", headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ jobId }) }), offersResponse);
      setOffers(refreshed.offers); setInvalidOffers(refreshed.offers.length === 0); setNow(Date.now());
      setError(appointmentSearchMessage(refreshed.search));
    } catch (error) { setError(errorMessage(error, "Could not refresh times.")); }
    finally { recordBookingApiDuration(started, "refresh"); setSubmitting(false); }
  }

  /** Takes the work order in and asks for its times: at the account's location, at the confirmed pin, or as a follow-up. */
  async function submitWorkOrder(followUp: boolean) {
    if (ready === null) return;
    if (mode === "account" && (account === null || (!followUp && account.location === null))) return;
    if (mode === "address" && !followUp && (!pin || !mapAvailable)) return;
    const intent = followUp ? "followUp" : "pin";
    const submissionId = submittedMode.current !== null && submittedMode.current !== intent ? newRequestId() : requestId;
    submittedMode.current = intent; setRequestId(submissionId);
    const started = bookingApiStart();
    const current = revision.current;
    const contact = { requestId: submissionId, firstName: form.firstName, lastName: form.lastName, email: form.email, phone: form.phone };
    const place = mode === "account" && account !== null ? { accountNumber: account.accountNumber, ...(followUp ? { followUp: true } : {}) }
      : { line1: form.line1, ...(form.line2.trim() === "" ? {} : { line2: form.line2 }), city: form.city, state: form.state, postalCode: form.postalCode,
        ...(followUp ? { followUp: true } : { confirmedPin: { ...pin, manuallyConfirmed: true } }) };
    setSubmitting(true);
    setError(null);
    try {
      const response = await fetch("/api/book", {
        signal: requestSignal(),
        method: "POST", headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ ...contact, ...place, ...workOrderDetails(form, ready) }),
      });
      const data = await readResponse(response, bookingResponse);
      if (current !== revision.current) return;
      setJobId(data.jobId);
      if (data.pendingReference) { setStep("pending"); return; }
      const problem = appointmentSearchMessage(required(data.search, "Appointment search status"));
      if (problem) { setError(problem); return; }
      setOffers(required(data.offers, "Appointment offers"));
      setInvalidOffers(false);
      setStep("slots");
    } catch (error) { if (current === revision.current) setError(errorMessage(error)); }
    finally { recordBookingApiDuration(started, mode === "account" ? "account" : "pin"); if (current === revision.current) setSubmitting(false); }
  }

  const searchProgress = submitting ? <div role="status">
    <label htmlFor="appointment-search-progress">Finding available appointments</label>
    <progress id="appointment-search-progress" aria-label="Finding available appointments" />
  </div> : null;

  if (step === "pending") {
    return <div className={styles.wrap}><div className={styles.card}>
      <h1 className={styles.title}>Sent to follow-up</h1>
      <p>No time could be offered for this work order. It waits on the Follow-up page under reference {jobId}.</p>
    </div></div>;
  }

  if (step === "pin") {
    return <div className={styles.wrap}>
      <PageHeader crumbs={["Work orders", "New work order"]} title="Confirm the service location"
        description="Check the service location. Drag the pin or click the map to place it at the driveway entrance." />
      <p>{form.line1}, {form.city}, {form.state} {form.postalCode}</p>
      {error && <div role="alert" className={styles.error}>{error}</div>}
      {searchProgress}
      {serviceArea && <AddressPinMap key={mapVersion} candidates={pinCandidates} area={serviceArea} pin={pin}
        onPlace={position => { setPin(position); setRequestId(newRequestId()); }} onAvailableChange={setMapAvailable} />}
      <div className={styles.card}>
        {!pin && <p>No matching address was found. Click the map to place the pin, or request follow-up.</p>}
        {!mapAvailable && <p role="status">Map unavailable or loading. Pin confirmation is blocked. Retry the map when tiles are available.</p>}
        {pinCandidates.map((candidate, index) => <button key={`${candidate.lat}-${candidate.lng}`} className={styles.linkButton}
          disabled={submitting} onClick={() => { setPin(candidate); setRequestId(newRequestId()); }}>Use location {index + 1}</button>)}
        <button className={styles.button} onClick={() => submitWorkOrder(false)} disabled={submitting || !pin || !mapAvailable}>
          {submitting ? "Checking availability..." : "Confirm pin and see times"}
        </button>
        <button className={styles.linkButton} disabled={submitting} onClick={() => { clearLocation(); setStep("form"); }}>Edit address</button>
        <button className={styles.linkButton} disabled={submitting} onClick={lookupLocation}>Retry address lookup</button>
        <button className={styles.linkButton} disabled={submitting} onClick={() => { setMapAvailable(false); setMapVersion(v => v + 1); }}>Retry map</button>
        <button className={styles.linkButton} disabled={submitting} onClick={() => submitWorkOrder(true)}>Request follow-up</button>
      </div>
    </div>;
  }

  if (step === "confirmed" && confirmedOffer) {
    return (
      <div className={styles.wrap}>
        <div className={styles.card}>
          <div className={styles.confirmBox}>
            <div className={styles.checkmark}>&#9989;</div>
            <h1 className={styles.title}>You&apos;re booked!</h1>
            <p>
              {formatDay(confirmedOffer.date)}, {formatWindow(confirmedOffer.windowStart, confirmedOffer.windowEnd)}
            </p>
            <p className={styles.fieldHint}>Confirmation #{appointmentId}</p>
          </div>
        </div>
      </div>
    );
  }

  if (step === "slots") {
    return (
      <div className={styles.wrap}>
        <PageHeader crumbs={["Work orders", "New work order"]} title="Choose a time"
          description={<>{codesLabel}{ready !== null && <>, {ready.minutes} min</>}. Pick the time the customer wants.</>} />
        {error && <div role="alert" className={styles.error}>{error}</div>}
      {searchProgress}
        <div className={styles.slotGrid}>
          {offers.map((offer) => {
            const remaining = secondsRemaining(offer.expiresAt, now);
            const expired = remaining <= 0;
            return (
              <div
                key={offer.offerId}
                className={`${styles.slotCard} ${expired ? styles.slotCardExpired : ""}`}
              >
                <div>
                  <div className={styles.slotDay}>{formatDay(offer.date)}</div>
                  <div className={styles.slotWindow}>
                    {formatWindow(offer.windowStart, offer.windowEnd)}
                  </div>
                  {!expired && (
                    <div className={styles.slotCountdown}>
                      Offer available for {formatCountdown(remaining)}
                    </div>
                  )}
                </div>
                <button
                  className={styles.selectButton}
                  disabled={invalidOffers || expired || confirmingHoldId !== null || submitting || releasing}
                  onClick={() => handleSelectSlot(offer.offerId)}
                >
                  {confirmingHoldId === offer.offerId ? "Booking..." : expired ? "Expired" : "Select"}
                </button>
              </div>
            );
          })}
        </div>
        <button className={styles.linkButton} onClick={refreshOffers} disabled={submitting || confirmingHoldId !== null || releasing}>Refresh times</button>
        <button className={styles.linkButton} onClick={handleBackToForm} disabled={submitting || confirmingHoldId !== null || releasing}>
          {releasing ? "Releasing times..." : "← Start over"}
        </button>
      </div>
    );
  }

  const textField = (key: "firstName" | "lastName" | "email" | "phone" | "city" | "postalCode", label: string, type = "text") =>
    <div className={styles.field}>
      <label className={styles.label}>{label}</label>
      <input className={styles.input} type={type} required value={form[key]} onChange={(e) => updateField(key, e.target.value)} />
    </div>;
  const accountBlocked = mode === "account" && account === null;
  const noCodes = services.length === 0 || form.reasonCodes[0] === "";

  return (
    <div className={styles.wrap}>
      <PageHeader crumbs={["Work orders", "New work order"]} title="New work order"
        description="Find the customer's WaterFlex account, choose the reason codes and the time to allot, then pick a time." />
      {error && <div role="alert" className={styles.error}>{error}</div>}
      {searchProgress}
      <form className={styles.card} onSubmit={handleSubmit}>
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
              <label className={styles.label}>Address</label>
              <input className={styles.input} required placeholder="Street address" value={form.line1}
                onChange={(e) => updateField("line1", e.target.value)} style={{ marginBottom: "0.5rem" }} />
              <input className={styles.input} placeholder="Apt, suite, etc. (optional)" value={form.line2}
                onChange={(e) => updateField("line2", e.target.value)} />
              <div className={styles.fieldHint}>We serve the Omaha metro area and nearby communities within about 65 miles.</div>
            </div>
            <div className={styles.row3}>
              {textField("city", "City")}
              <div className={styles.field}>
                <label className={styles.label}>State</label>
                <input className={styles.input} required maxLength={2} value={form.state} onChange={(e) => updateField("state", e.target.value.toUpperCase())} />
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
                onChange={(e) => updateField("allotted", e.target.value)} aria-describedby={`${fieldId}-allotted-hint`} />
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
                onChange={(e) => updateField("workOrderType", e.target.value)} />
            </div>
          </div>
          <div className={styles.row}>
            <div className={styles.field}>
              <label className={styles.label} htmlFor={`${fieldId}-po`}>Purchase order</label>
              <input id={`${fieldId}-po`} className={styles.input} maxLength={80} value={form.purchaseOrder}
                onChange={(e) => updateField("purchaseOrder", e.target.value)} />
            </div>
            <label className={styles.checkField}>
              <input type="checkbox" checked={form.callFirst} onChange={(e) => updateField("callFirst", e.target.checked)} />
              Call the customer before arriving
            </label>
          </div>
          <div className={styles.field}>
            <label className={styles.label} htmlFor={`${fieldId}-request`}>Customer request</label>
            <textarea id={`${fieldId}-request`} className={styles.input} rows={2} maxLength={1000} value={form.customerRequest}
              onChange={(e) => updateField("customerRequest", e.target.value)} />
          </div>
          <div className={styles.field}>
            <label className={styles.label} htmlFor={`${fieldId}-message`}>Message to the technician</label>
            <textarea id={`${fieldId}-message`} className={styles.input} rows={2} maxLength={1000} value={form.technicianMessage}
              onChange={(e) => updateField("technicianMessage", e.target.value)} />
          </div>

          {mode === "account"
            ? <button className={styles.button} type="submit" disabled={submitting || accountBlocked || noCodes || ready === null || account?.location === null}>
              {submitting ? "Finding times..." : "See times"}
            </button>
            : <button className={styles.button} type="submit" disabled={submitting || noCodes || ready === null}>
              {submitting ? "Looking up address..." : "Review service location"}
            </button>}
          <button type="button" className={styles.linkButton} disabled={submitting || accountBlocked || noCodes || ready === null} onClick={e => {
            if (e.currentTarget.form?.reportValidity()) void submitWorkOrder(true);
          }}>Request follow-up</button>
          {mode === "account" && account !== null && <p className={styles.fieldHint}>Service address: {accountAddress(account)}</p>}
        </fieldset>
      </form>
    </div>
  );
}
