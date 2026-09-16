"use client";

import { useEffect, useMemo, useState } from "react";
import styles from "@/app/book/booking.module.css";
import AddressPinMap, { type PinCandidate } from "@/app/book/AddressPinMap";

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
  // timezone — rolling back to the *previous* calendar day for anyone
  // west of UTC. Parse the components directly as a local date instead.
  const parts = dateOnly.split("-").map(Number);
  const [year, month, day] = [parts[0] ?? 1970, parts[1] ?? 1, parts[2] ?? 1];
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
  const [step, setStep] = useState<Step>("form");
  const [form, setForm] = useState<FormState>(EMPTY_FORM);
  const [requestId, setRequestId] = useState(() => newRequestId());
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const [offers, setOffers] = useState<SlotOffer[]>([]);
  const [confirmingHoldId, setConfirmingHoldId] = useState<string | null>(null);
  const [confirmedOffer, setConfirmedOffer] = useState<SlotOffer | null>(null);
  const [appointmentId, setAppointmentId] = useState<string | null>(null);
  const [jobId, setJobId] = useState<string | null>(null);
  const [pinCandidates, setPinCandidates] = useState<PinCandidate[]>([]);
  const [selectedPinIndex, setSelectedPinIndex] = useState(0);

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
    setForm((f) => ({ ...f, [key]: value }));
  }

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    setError(null);
    setSubmitting(true);
    try {
      const res = await fetch("/api/book", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ ...form, requestId }),
      });
      const data = await res.json();
      if (!res.ok) {
        setError(data.error ?? "Something went wrong. Please try again.");
        return;
      }
      setJobId(data.jobId);
      if (data.pinRequired) {
        setPinCandidates(data.candidates);
        setSelectedPinIndex(0);
        setStep("pin");
        return;
      }
      if (data.pendingReference) {
        setStep("pending");
        return;
      }
      if (!data.offers || data.offers.length === 0) {
        setError(
          "We don't have any availability in the next couple of weeks. Please call us to schedule."
        );
        return;
      }
      setOffers(data.offers);
      setNow(Date.now());
      setStep("slots");
    } catch {
      setError("Network error. Please check your connection and try again.");
    } finally {
      setSubmitting(false);
    }
  }

  async function handleSelectSlot(offerId: string) {
    setError(null);
    setConfirmingHoldId(offerId);
    try {
      const selection = await fetch("/api/book/select", {
        method: "POST", headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ jobId, offerId }),
      });
      const selected = await selection.json();
      if (!selection.ok) {
        if (selected.pendingReference) { setJobId(selected.pendingReference); setStep("pending"); return; }
        setError(selected.error ?? "That window is no longer available. Please refresh your options.");
        if (selection.status === 409) setOffers(selected.offers ?? []);
        return;
      }
      const res = await fetch("/api/book/confirm", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ holdId: selected.holdId }),
      });
      const data = await res.json();
      if (!res.ok) {
        if (data.pendingReference) { setJobId(data.pendingReference); setStep("pending"); return; }
        setError(
          data.error === "hold expired" || res.status === 409
            ? "That time is no longer available. Please choose another, or go back to see fresh options."
            : data.error ?? "Something went wrong confirming that slot."
        );
        // Drop the slot that failed so the customer doesn't retry a dead option.
        setOffers((prev) => prev.filter((o) => o.offerId !== offerId));
        return;
      }
      setConfirmedOffer(offers.find((o) => o.offerId === offerId) ?? null);
      setAppointmentId(data.appointmentId);
      setStep("confirmed");
    } catch {
      setError("Network error. Please try again.");
    } finally {
      setConfirmingHoldId(null);
    }
  }

  function handleBackToForm() {
    setStep("form");
    setOffers([]);
    setError(null);
    setRequestId(newRequestId());
    setJobId(null);
  }

  async function handlePinConfirmation() {
    const pin = pinCandidates[selectedPinIndex];
    if (!pin) return;
    setSubmitting(true);
    setError(null);
    try {
      const response = await fetch("/api/book", {
        method: "POST", headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ ...form, requestId, confirmedPin: { lat: pin.lat, lng: pin.lng } }),
      });
      const data = await response.json();
      if (!response.ok) { setError(data.error ?? "Could not confirm that location."); return; }
      if (data.pendingReference) { setStep("pending"); return; }
      if (!data.offers?.length) { setError("No available time was found. Please call us to schedule."); return; }
      setOffers(data.offers);
      setStep("slots");
    } catch { setError("Network error. Please try again."); }
    finally { setSubmitting(false); }
  }

  if (step === "pending") {
    return <main className={styles.wrap}><div className={styles.card}>
      <h1 className={styles.title}>We will follow up</h1>
      <p>We could not safely offer a time online. Please keep this reference: {jobId}</p>
    </div></main>;
  }

  if (step === "pin") {
    return <main className={styles.wrap}>
      <h1 className={styles.title}>Confirm your service location</h1>
      <p className={styles.subtitle}>We found more than one possible match. Select the pin that marks your home.</p>
      {error && <div className={styles.error}>{error}</div>}
      <AddressPinMap candidates={pinCandidates} selected={selectedPinIndex} onSelect={setSelectedPinIndex} />
      <div className={styles.card}>
        {pinCandidates.map((candidate, index) => <label key={`${candidate.lat}-${candidate.lng}`} className={styles.serviceOption}>
          <input type="radio" name="pin" checked={selectedPinIndex === index} onChange={() => setSelectedPinIndex(index)} />
          <span>Location {index + 1}: {candidate.lat.toFixed(5)}, {candidate.lng.toFixed(5)}</span>
        </label>)}
        <button className={styles.button} onClick={handlePinConfirmation} disabled={submitting}>
          {submitting ? "Checking availability..." : "Confirm pin and see times"}
        </button>
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
          {selectedService?.name} &mdash; pick whichever works best for you.
        </p>
        {error && <div className={styles.error}>{error}</div>}
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
                  disabled={expired || confirmingHoldId !== null}
                  onClick={() => handleSelectSlot(offer.offerId)}
                >
                  {confirmingHoldId === offer.offerId ? "Booking..." : expired ? "Expired" : "Select"}
                </button>
              </div>
            );
          })}
        </div>
        <button className={styles.linkButton} onClick={handleBackToForm}>
          &larr; Start over
        </button>
      </main>
    );
  }

  return (
    <main className={styles.wrap}>
      <h1 className={styles.title}>Book a service visit</h1>
      <p className={styles.subtitle}>Tell us what you need and we&apos;ll find the soonest good time.</p>
      {error && <div className={styles.error}>{error}</div>}
      <form className={styles.card} onSubmit={handleSubmit}>
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
          {submitting ? "Finding available times..." : "See available times"}
        </button>
      </form>
    </main>
  );
}
