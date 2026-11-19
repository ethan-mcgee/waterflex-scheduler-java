"use client";

import Link from "next/link";
import { useState, type FormEvent } from "react";
import { errorMessage, readResponse, reservationBooked, reservationReleased } from "@/lib/contracts";
import { clockLabel } from "@/lib/workOrdersDayCore";
import type { DemandSeries } from "@/lib/recurringDemand";
import { Button } from "@/app/portal/ui";
import { dayLabel } from "./labels";
import styles from "./recurring.module.css";

const placeHref = (metroId: string, date: string, jobId: string) =>
  `/work-orders/schedule?${new URLSearchParams({ metroId, date, placeJob: jobId }).toString()}`;

async function post(url: string, body: unknown) {
  return fetch(url, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body) });
}

/**
 * A due customer's reservation: none yet, reserved (book it or give it back), or booked (its work order, to place on
 * the calendar). Booking asks for the contact the dispatcher confirmed, since WaterFlex's export has one name field.
 */
export default function ReservationActions({ series, metroId, onDone }: { series: DemandSeries; metroId: string; onDone: () => void }) {
  const [booking, setBooking] = useState(false);
  const [contact, setContact] = useState({ firstName: "", lastName: "", email: series.email ?? "", phone: series.phone ?? "" });
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const reservation = series.reservation;
  if (reservation === null) return <p className={styles.reservationLine}>Not reserved</p>;
  const when = `${dayLabel(reservation.date)}, ${clockLabel(reservation.startMin)} - ${clockLabel(reservation.endMin)} with ${reservation.technicianName}`;

  if (reservation.status === "BOOKED") {
    return <p className={styles.reservationLine}>
      Booked for {when}. {reservation.jobId === null ? null : reservation.placed ? "On the calendar."
        : <Link href={placeHref(metroId, reservation.date, reservation.jobId)}>Place on calendar</Link>}
    </p>;
  }

  async function run(action: () => Promise<void>) {
    setBusy(true); setError(null);
    try { await action(); onDone(); }
    catch (failure) { setError(errorMessage(failure, "The reservation could not be changed. Try again.")); }
    finally { setBusy(false); }
  }

  async function book(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (reservation === null) return;
    await run(async () => {
      await readResponse(await post(`/api/recurring/reservations/${encodeURIComponent(reservation.id)}/book`, {
        firstName: contact.firstName.trim(), lastName: contact.lastName.trim(), email: contact.email.trim(), phone: contact.phone.trim() }), reservationBooked);
      setBooking(false);
    });
  }

  const field = (name: keyof typeof contact, label: string) => <label>{label}
    <input className={styles.input} required value={contact[name]} onChange={event => setContact({ ...contact, [name]: event.target.value })} />
  </label>;

  return <div className={styles.reservationLine}>
    <p>Reserved {when}.</p>
    {booking ? <form className={styles.bookForm} onSubmit={book} aria-label={`Book ${series.accountNumber}`}>
      {series.customerName !== null && <p className={styles.muted}>Account name: {series.customerName}</p>}
      {field("firstName", "First name")}
      {field("lastName", "Last name")}
      {field("email", "Email")}
      {field("phone", "Phone")}
      <div className={styles.inlineActions}>
        <Button type="submit" disabled={busy}>Create work order</Button>
        <Button variant="text" onClick={() => setBooking(false)} disabled={busy}>Cancel</Button>
      </div>
    </form> : <div className={styles.inlineActions}>
      <Button variant="outlined" onClick={() => setBooking(true)} disabled={busy}>Book</Button>
      <Button variant="text" disabled={busy} onClick={() => void run(async () => {
        await readResponse(await post(`/api/recurring/reservations/${encodeURIComponent(reservation.id)}/release`, {}), reservationReleased);
      })}>Release</Button>
    </div>}
    {error !== null && <p className={styles.error} role="alert">{error}</p>}
  </div>;
}
