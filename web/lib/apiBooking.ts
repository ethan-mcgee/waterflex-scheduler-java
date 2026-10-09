import { randomUUID } from "node:crypto";
import { prisma } from "./prisma";
import { todayInTz } from "./date";
import { loadSolverSettings } from "./clientSettingsStore";
import { buildClientSnapshot, SnapshotError } from "./clientSnapshot";
import { resolveMetroForLocation } from "./serviceArea";
import { bookingHorizon, checkReceipt, failedSearch, offersView } from "./apiBookingCore";
import { writeReceipt } from "./apiReceipt";
import { confirmBookingHold, createBookingOffers, releaseBookingOffer, SchedulerApiError, selectBookingOffer, type CommitReceipt } from "./schedulerApi";

/**
 * Booking through the public scheduling API, with the portal acting as WaterFlex Software for one client: search with
 * a snapshot of the client's own technicians and appointments, select and confirm a hold, and write the receipt into
 * the portal's tables with a compare-and-set on every listed technician-day's lastModified.
 */

/** A booking step the customer can see: an HTTP status and a message, never an internal detail. */
export class BookingRefused extends Error {
  constructor(readonly status: number, message: string) { super(message); this.name = "BookingRefused"; }
}

const day = (value: string) => new Date(`${value}T00:00:00Z`);

function refusedFor(error: unknown): never {
  if (error instanceof SnapshotError) {
    if (error.code === "NOT_CONFIGURED") throw new BookingRefused(503, "Online booking is not set up for this client yet.");
    console.error("Booking snapshot refused", error.code, error.message);
    throw new BookingRefused(409, "The schedule needs attention before online booking can continue.");
  }
  if (error instanceof SchedulerApiError) {
    if (error.code === "NOT_CONNECTED") { console.error("Scheduling API not connected", error.message); throw new BookingRefused(503, "Online booking is not connected for this client."); }
    if (error.code === "HOLD_UNAVAILABLE" || error.code === "NOT_FOUND") throw new BookingRefused(409, "That window is no longer available. Please refresh your options.");
    if (error.code === "INCOMPLETE_FACTS") throw new BookingRefused(422, "We cannot schedule that location online. Please request follow-up.");
    console.error("Scheduling API call failed", error.status, error.code, error.message);
    throw new BookingRefused(error.status === 429 ? 429 : 503, "Scheduling service unavailable");
  }
  throw error;
}

/**
 * Searches offers for a pending job of the client, ending the job's earlier offers. The horizon is the client's booking
 * horizon from today, or `onlyDate` alone when a generator books a chosen day.
 */
export async function searchApiOffers(clientId: string, jobId: string, onlyDate?: string) {
  const started = performance.now();
  const elapsed = () => Math.ceil(performance.now() - started);
  const job = await prisma.job.findFirst({ where: { id: jobId, customer: { clientId } },
    select: { id: true, status: true, serviceId: true, durationMin: true, address: { select: { lat: true, lng: true } } } });
  if (job === null) throw new BookingRefused(404, "Booking not found");
  if (job.status !== "PENDING") throw new BookingRefused(409, "This booking request has already been completed.");
  const { lat, lng } = job.address;
  if (lat == null || lng == null) throw new BookingRefused(422, "Confirm the service location before searching for times.");
  const [settings, metroId] = await Promise.all([loadSolverSettings(clientId), resolveMetroForLocation(lat, lng, clientId)]);
  if (settings === null) throw new BookingRefused(503, "Online booking is not set up for this client yet.");
  if (metroId === null) throw new BookingRefused(422, "That pin is outside our service area.");
  const metro = await prisma.metro.findUniqueOrThrow({ where: { id: metroId }, select: { timezone: true } });
  const dates = onlyDate === undefined ? bookingHorizon(todayInTz(metro.timezone), settings.bookingHorizonWeekdays) : [onlyDate];
  const firstDate = dates[0], lastDate = dates.at(-1);
  if (firstDate === undefined || lastDate === undefined) throw new Error("A booking horizon has at least one date");
  const requestId = randomUUID();
  let set;
  try {
    const snapshot = await buildClientSnapshot(clientId, metroId, dates);
    set = await createBookingOffers(clientId, { requestId, offerLimit: settings.offerLimit, snapshot,
      job: { id: job.id, serviceId: job.serviceId, durationMinutes: job.durationMin, location: { lat, lng } },
      horizon: { firstDate, lastDate } });
  } catch (error) {
    if (error instanceof SchedulerApiError && error.code === "BUSY") return failedSearch(jobId, "SERVICE_BUSY", elapsed());
    if (error instanceof SchedulerApiError && error.code === "ROUTING_UNAVAILABLE") return failedSearch(jobId, "ROUTING_UNAVAILABLE", elapsed());
    return refusedFor(error);
  }
  if (set.skippedTechnicianDays.length > 0)
    console.warn("Booking search skipped technician-days", set.skippedTechnicianDays.map(item => `${item.technicianId} ${item.serviceDate}: ${item.message}`));
  const offerSet = set;
  await prisma.$transaction(async tx => {
    // The scheduler ends a job's earlier offers when it searches again; the portal records the same.
    await tx.portalApiOfferSet.updateMany({ where: { jobId, endedAt: null, receiptId: null }, data: { endedAt: new Date() } });
    await tx.portalApiOfferSet.create({ data: { id: offerSet.offerSetId, clientId, jobId, metroId, requestId,
      expiresAt: new Date(offerSet.expiresAt), searchComplete: offerSet.searchComplete,
      offers: { create: offerSet.offers.map(item => ({ id: item.offerId, serviceDate: day(item.serviceDate),
        windowStart: new Date(item.window.start), windowEnd: new Date(item.window.end) })) } } });
  });
  return offersView(jobId, offerSet, elapsed());
}

export { StaleReceipt } from "./apiReceipt";

/** Writes a confirmed booking with a compare-and-set on every technician-day the receipt lists; any difference writes nothing. */
export async function writeBookingReceipt(clientId: string, jobId: string, offer: { serviceDate: string; windowStart: Date; windowEnd: Date },
  receipt: CommitReceipt): Promise<void> {
  const days = checkReceipt(receipt, jobId, offer.serviceDate);
  await writeReceipt(clientId, { receipt, complete: days, create: { jobId, windowStart: offer.windowStart, windowEnd: offer.windowEnd } });
}

/** Selects an offer, confirms its hold against a fresh snapshot of its date and writes the booking. */
export async function bookApiOffer(clientId: string, jobId: string, offerId: string) {
  const offer = await prisma.portalApiOffer.findFirst({ where: { id: offerId, offerSet: { jobId, clientId } },
    select: { id: true, serviceDate: true, windowStart: true, windowEnd: true,
      offerSet: { select: { id: true, metroId: true, expiresAt: true, endedAt: true, holdId: true, selectedOfferId: true, receiptId: true } } } });
  if (offer === null) throw new BookingRefused(404, "Booking not found");
  const set = offer.offerSet;
  const booked = { appointmentId: jobId, windowStart: offer.windowStart.toISOString(), windowEnd: offer.windowEnd.toISOString() };
  if (set.receiptId !== null) {
    if (set.selectedOfferId !== offerId || set.holdId === null) throw new BookingRefused(409, "This booking was completed with another time.");
    return { ...booked, holdId: set.holdId, expiresAt: set.expiresAt.toISOString() };
  }
  if (set.endedAt !== null || set.expiresAt <= new Date()) throw new BookingRefused(409, "That window is no longer available. Please refresh your options.");
  if (set.selectedOfferId !== null && set.selectedOfferId !== offerId) throw new BookingRefused(409, "Another time is already being booked for this request.");
  const serviceDate = offer.serviceDate.toISOString().slice(0, 10);
  let receipt: CommitReceipt;
  let held;
  try {
    held = await selectBookingOffer(clientId, offerId, randomUUID());
    await prisma.portalApiOfferSet.update({ where: { id: set.id }, data: { holdId: held.holdId, selectedOfferId: offerId } });
    const snapshot = await buildClientSnapshot(clientId, set.metroId, [serviceDate]);
    receipt = await confirmBookingHold(clientId, held.holdId, randomUUID(), snapshot);
  } catch (error) { return refusedFor(error); }
  await writeBookingReceipt(clientId, jobId, { serviceDate, windowStart: offer.windowStart, windowEnd: offer.windowEnd }, receipt);
  await prisma.portalApiOfferSet.update({ where: { id: set.id }, data: { receiptId: receipt.receiptId, endedAt: new Date() } });
  return { ...booked, holdId: held.holdId, expiresAt: held.expiresAt };
}

/** Releases every hold of the offer's set. */
export async function releaseApiOffers(clientId: string, jobId: string, offerId: string): Promise<{ success: true }> {
  const offer = await prisma.portalApiOffer.findFirst({ where: { id: offerId, offerSet: { jobId, clientId } }, select: { offerSet: { select: { id: true, receiptId: true } } } });
  if (offer === null) throw new BookingRefused(404, "Booking not found");
  if (offer.offerSet.receiptId !== null) throw new BookingRefused(409, "This booking is already confirmed.");
  try { await releaseBookingOffer(clientId, offerId, randomUUID()); }
  catch (error) { return refusedFor(error); }
  await prisma.portalApiOfferSet.update({ where: { id: offer.offerSet.id }, data: { endedAt: new Date() } });
  return { success: true };
}
