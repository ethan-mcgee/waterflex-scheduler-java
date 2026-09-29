"use client";

import { bookingLocationResponse, bookingResponse, bookingFailure, selection as selectionSchema, offersResponse, success, readResponse, errorMessage, required, date as dateContract } from "@/lib/contracts";
import { appointmentSearchMessage, recordBookingApiDuration } from "@/lib/appointmentSearch";
import { useEffect, useMemo, useRef, useState } from "react";
import styles from "@/app/book/booking.module.css";
import AddressPinMap, { type PinCandidate, type ServiceArea } from "@/app/book/AddressPinMap";

interface ServiceOption {
  code: string;
  name: string;
  description: string | null;
  estDurationMin: number;
}

interface SlotOffer {
  offerId: string;
  date: string;
  windowStart: string;
  windowEnd: string;
  expiresAt: string;
}

type Step = "form" | "pin" | "slots" | "confirmed" | "pending";

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
  serviceCode: string;
}

function newRequestId(): string {
  return globalThis.crypto?.randomUUID?.() ?? `${Date.now()}-${Math.random()}`;
}

const EMPTY_FORM: FormState = {
  firstName: "",
  lastName: "",
  email: "",
  phone: "",
  line1: "",
  line2: "",
  city: "",
  state: "NE",
  postalCode: "",
  serviceCode: "",
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

export default function BookingWizard({ services }: { services: ServiceOption[] }) {
  const activeRequest = useRef<AbortController | null>(null);
  useEffect(() => () => activeRequest.current?.abort(), []);
  function requestSignal() {
    activeRequest.current?.abort();
    activeRequest.current = new AbortController();
    return activeRequest.current.signal;
  }
  const [step, setStep] = useState<Step>("form");
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

  const selectedService = useMemo(
    () => services.find((s) => s.code === form.serviceCode) ?? null,
    [services, form.serviceCode]
  );

  function updateField<K extends keyof FormState>(key: K, value: FormState[K]) {
    clearLocation();
    setForm((f) => ({ ...f, [key]: value }));
  }

  async function lookupLocation() {
    const current = ++revision.current;
    setError(null); setSubmitting(true); setPin(null); setMapAvailable(false);
    try {
      const res = await fetch("/api/book/location", {
        signal: requestSignal(), method: "POST", headers: { "Content-Type": "application/json" },
        body: JSON.stringify(form),
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
    await lookupLocation();
  }

  async function handleSelectSlot(offerId: string) {
    const started = performance.now();
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
    const started = performance.now();
    setSubmitting(true);
    setError(null);
    try {
      const response = await fetch("/api/book/refresh", { signal: AbortSignal.any([requestSignal(), AbortSignal.timeout(5000)]), method: "POST",
        headers: { "Content-Type": "application/json" }, body: JSON.stringify({ jobId, deadlineEpochMs: Date.now() + 5000 }) });
      const data = await readResponse(response, offersResponse);
      const problem = appointmentSearchMessage(data.search);
      if (problem) { setOffers([]); setInvalidOffers(true); setError(problem); return; }
      setOffers(data.offers);
      setInvalidOffers(false);
      setNow(Date.now());
    } catch (error) {
      setInvalidOffers(true);
      setError(error instanceof DOMException && error.name === "TimeoutError"
        ? "The appointment search did not finish. Please retry to check available times."
        : errorMessage(error, "Could not refresh times."));
    }
    finally { recordBookingApiDuration(started, "refresh"); setSubmitting(false); }
  }

  async function handlePinConfirmation(followUp = false) {
    if (!followUp && (!pin || !mapAvailable)) return;
    const mode = followUp ? "followUp" : "pin";
    const submissionId = submittedMode.current !== null && submittedMode.current !== mode ? newRequestId() : requestId;
    submittedMode.current = mode; setRequestId(submissionId);
    const started = performance.now();
    const current = revision.current;
    setSubmitting(true);
    setError(null);
    try {
      const response = await fetch("/api/book", {
        signal: requestSignal(),
        method: "POST", headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ ...form, requestId: submissionId, ...(followUp ? { followUp: true } : { confirmedPin: { ...pin, manuallyConfirmed: true } }) }),
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
    finally { recordBookingApiDuration(started, "pin"); if (current === revision.current) setSubmitting(false); }
  }

  const searchProgress = submitting ? <div role="status">
    <label htmlFor="appointment-search-progress">Finding available appointments</label>
    <progress id="appointment-search-progress" aria-label="Finding available appointments" />
  </div> : null;

  if (step === "pending") {
    return <main className={styles.wrap}><div className={styles.card}>
      <h1 className={styles.title}>We will follow up</h1>
      <p>We could not safely offer a time online. Please keep this reference: {jobId}</p>
    </div></main>;
  }

  if (step === "pin") {
    return <main className={styles.wrap}>
      <h1 className={styles.title}>Confirm your service location</h1>
      <p className={styles.subtitle}>Check your service location. Drag the pin or click the map to place it at your driveway entrance.</p>
      <p>{form.line1}, {form.city}, {form.state} {form.postalCode}</p>
      {error && <div role="alert" className={styles.error}>{error}</div>}
      {searchProgress}
      {serviceArea && <AddressPinMap key={mapVersion} candidates={pinCandidates} area={serviceArea} pin={pin}
        onPlace={position => { setPin(position); setRequestId(newRequestId()); }} onAvailableChange={setMapAvailable} />}
      <div className={styles.card}>
        {!pin && <p>No matching address was found. Click the map to place your pin, or request follow-up.</p>}
        {!mapAvailable && <p role="status">Map unavailable or loading. Pin confirmation is blocked. Retry the map when tiles are available.</p>}
        {pinCandidates.map((candidate, index) => <button key={`${candidate.lat}-${candidate.lng}`} className={styles.linkButton}
          disabled={submitting} onClick={() => { setPin(candidate); setRequestId(newRequestId()); }}>Use location {index + 1}</button>)}
        <button className={styles.button} onClick={() => handlePinConfirmation()} disabled={submitting || !pin || !mapAvailable}>
          {submitting ? "Checking availability..." : "Confirm pin and see times"}
        </button>
        <button className={styles.linkButton} disabled={submitting} onClick={() => { clearLocation(); setStep("form"); }}>Edit address</button>
        <button className={styles.linkButton} disabled={submitting} onClick={lookupLocation}>Retry address lookup</button>
        <button className={styles.linkButton} disabled={submitting} onClick={() => { setMapAvailable(false); setMapVersion(v => v + 1); }}>Retry map</button>
        <button className={styles.linkButton} disabled={submitting} onClick={() => handlePinConfirmation(true)}>Request follow-up</button>
      </div>
    </main>;
  }

  if (step === "confirmed" && confirmedOffer) {
    return (
      <main className={styles.wrap}>
        <div className={styles.card}>
          <div className={styles.confirmBox}>
            <div className={styles.checkmark}>&#9989;</div>
            <h1 className={styles.title}>You&apos;re booked!</h1>
            <p>
              {formatDay(confirmedOffer.date)}, {formatWindow(confirmedOffer.windowStart, confirmedOffer.windowEnd)}
            </p>
            <p style={{ color: "#666", fontSize: "0.875rem" }}>
              Confirmation #{appointmentId}
            </p>
          </div>
        </div>
      </main>
    );
  }

  if (step === "slots") {
    return (
      <main className={styles.wrap}>
        <h1 className={styles.title}>Choose a time</h1>
        <p className={styles.subtitle}>
          {selectedService?.name} - pick whichever works best for you.
        </p>
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
      </main>
    );
  }

  return (
    <main className={styles.wrap}>
      <h1 className={styles.title}>Book a service visit</h1>
      <p className={styles.subtitle}>Tell us what you need and we&apos;ll find the soonest good time.</p>
      {error && <div role="alert" className={styles.error}>{error}</div>}
      {searchProgress}
      <form className={styles.card} onSubmit={handleSubmit}>
        <fieldset style={{ border: 0, padding: 0, margin: 0, minWidth: 0 }}>
        <fieldset className={styles.serviceFieldset}>
          <legend className={styles.label}>What type of service do you need?</legend>
          <p className={styles.fieldHint}>Select one service before choosing an appointment time.</p>
          {services.length === 0 ? (
            <div className={styles.error}>No services are available for online booking right now.</div>
          ) : (
            services.map((s) => (
              <label
                key={s.code}
                className={`${styles.serviceOption} ${
                  form.serviceCode === s.code ? styles.serviceOptionSelected : ""
                }`}
              >
                <input
                  className={styles.serviceRadio}
                  type="radio"
                  name="serviceCode"
                  value={s.code}
                  checked={form.serviceCode === s.code}
                  onChange={() => updateField("serviceCode", s.code)}
                  required
                />
                <span className={styles.serviceContent}>
                  <span className={styles.serviceName}>{s.name}</span>
                  {s.description && <span className={styles.serviceMeta}>{s.description}</span>}
                  <span className={styles.serviceMeta}>About {s.estDurationMin} minutes</span>
                </span>
              </label>
            ))
          )}
        </fieldset>

        <div className={styles.row}>
          <div className={styles.field}>
            <label className={styles.label}>First name</label>
            <input
              className={styles.input}
              required
              value={form.firstName}
              onChange={(e) => updateField("firstName", e.target.value)}
            />
          </div>
          <div className={styles.field}>
            <label className={styles.label}>Last name</label>
            <input
              className={styles.input}
              required
              value={form.lastName}
              onChange={(e) => updateField("lastName", e.target.value)}
            />
          </div>
        </div>

        <div className={styles.row}>
          <div className={styles.field}>
            <label className={styles.label}>Email</label>
            <input
              className={styles.input}
              type="email"
              required
              value={form.email}
              onChange={(e) => updateField("email", e.target.value)}
            />
          </div>
          <div className={styles.field}>
            <label className={styles.label}>Phone</label>
            <input
              className={styles.input}
              type="tel"
              required
              value={form.phone}
              onChange={(e) => updateField("phone", e.target.value)}
            />
          </div>
        </div>

        <div className={styles.field}>
          <label className={styles.label}>Address</label>
          <input
            className={styles.input}
            required
            placeholder="Street address"
            value={form.line1}
            onChange={(e) => updateField("line1", e.target.value)}
            style={{ marginBottom: "0.5rem" }}
          />
          <input
            className={styles.input}
            placeholder="Apt, suite, etc. (optional)"
            value={form.line2}
            onChange={(e) => updateField("line2", e.target.value)}
          />
          <div className={styles.fieldHint}>
            We serve the Omaha metro area and nearby communities within about 65 miles.
          </div>
        </div>

        <div className={styles.row3}>
          <div className={styles.field}>
            <label className={styles.label}>City</label>
            <input
              className={styles.input}
              required
              value={form.city}
              onChange={(e) => updateField("city", e.target.value)}
            />
          </div>
          <div className={styles.field}>
            <label className={styles.label}>State</label>
            <input
              className={styles.input}
              required
              maxLength={2}
              value={form.state}
              onChange={(e) => updateField("state", e.target.value.toUpperCase())}
            />
          </div>
          <div className={styles.field}>
            <label className={styles.label}>ZIP</label>
            <input
              className={styles.input}
              required
              value={form.postalCode}
              onChange={(e) => updateField("postalCode", e.target.value)}
            />
          </div>
        </div>

        <button
          className={styles.button}
          type="submit"
          disabled={submitting || !form.serviceCode || services.length === 0}
        >
          {submitting ? "Looking up address..." : "Review service location"}
        </button>
        <button type="button" className={styles.linkButton} disabled={submitting || !form.serviceCode} onClick={e => {
          if (e.currentTarget.form?.reportValidity()) void handlePinConfirmation(true);
        }}>Request follow-up</button>
        </fieldset>
      </form>
    </main>
  );
}
