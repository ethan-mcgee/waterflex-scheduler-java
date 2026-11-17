import { Prisma, type Job } from "@prisma/client";
import type { z } from "zod";
import type { bookingRequest } from "./contracts";
import { prisma } from "./prisma";
import { GeocoderError, searchAddress, type GeocodeResult } from "./geocode";
import { bookingServiceArea, resolveMetroForLocation } from "./serviceArea";
import { ownsJob } from "./clientScope";
import { bookingFingerprint } from "./bookingIdentity";

/**
 * Taking in a customer's job: the customer, the address and a PENDING job, under the request's retry key. Shared by the
 * booking wizard and the schedule page's new-job form, so the address rule lives in one place: a rooftop match or a
 * pin the caller explicitly confirmed, inside the client's own service area, or a follow-up with no location.
 */

export type BookingInput = z.infer<typeof bookingRequest>;

/** The job, or the answer that refuses it (the status and the body the route sends). */
export type Intake = { job: Job } | { refusal: { status: number; body: { error: string; status?: string } } };

const refuse = (status: number, error: string, code?: string): Intake => ({ refusal: { status, body: code === undefined ? { error } : { status: code, error } } });

/** A geocoder failure as the answer a route sends; any other error is not this function's to answer. */
export function geocoderRefusal(error: unknown): { status: number; body: { status: string; error: string } } | null {
  if (!(error instanceof GeocoderError)) return null;
  return { status: error.kind === "timeout" ? 504 : error.kind === "malformed" ? 502 : 503, body: { status: "LOOKUP_FAILED", error: error.message } };
}

/**
 * The PENDING job for a request: the one already taken in under its request ID (when it is this client's, has the same
 * details and is still pending), or a new one. A geocoder failure throws GeocoderError (see geocoderRefusal).
 */
export async function intakeJob(clientId: string, input: BookingInput): Promise<Intake> {
  const fingerprint = bookingFingerprint(input);
  const conflict = () => refuse(409, "This request ID belongs to different booking details. Start a new request.");
  let job = await prisma.job.findUnique({ where: { bookingRequestId: input.requestId } });
  if (job && !(await ownsJob(clientId, job.id))) return refuse(404, "Booking request not found");
  if (job && job.bookingRequestFingerprint !== fingerprint) return conflict();
  if (job && job.status !== "PENDING") return refuse(409, "This booking request has already been completed.");
  if (job) return { job };
  const service = await prisma.serviceCatalog.findUnique({ where: { code: input.serviceCode } });
  if (!service?.active) return refuse(400, "That service is not currently available.");
  const durationMin = Math.round(service.estDurationMin * (1 + service.bufferPct));
  if (!Number.isSafeInteger(durationMin) || durationMin <= 0) return refuse(503, "Service duration is unavailable.");
  let selected: GeocodeResult | null = null;
  const pin = input.confirmedPin;
  if (!input.followUp) {
    if (pin?.manuallyConfirmed) selected = { lat: pin.lat, lng: pin.lng, precision: "APPROXIMATE" };
    else {
      const area = await bookingServiceArea(clientId);
      if (area == null) return refuse(422, "This client has no service area yet.", "OUTSIDE_COVERAGE");
      const candidates = await searchAddress(input, area.bounds);
      selected = candidates.find(c => c.precision === "ROOFTOP" && (!pin ||
        (Math.abs(c.lat - pin.lat) < 0.0001 && Math.abs(c.lng - pin.lng) < 0.0001))) ?? null;
    }
    if (!selected) return refuse(422, "Review and explicitly confirm your service location, or request follow-up.", "LOCATION_REQUIRED");
    // The pin must be inside this client's own service area. A pin the scheduler cannot reach by road gets no offer.
    if ((await resolveMetroForLocation(selected.lat, selected.lng, clientId)) == null)
      return refuse(422, "That pin is outside our service area.", "OUTSIDE_COVERAGE");
  }
  try {
    job = await prisma.$transaction(async tx => {
      const existing = await tx.job.findUnique({ where: { bookingRequestId: input.requestId } });
      if (existing) return existing;
      const customer = await tx.customer.create({ data: { clientId, firstName: input.firstName, lastName: input.lastName, email: input.email, phone: input.phone } });
      const address = await tx.address.create({ data: { customerId: customer.id, line1: input.line1, line2: input.line2,
        city: input.city, state: input.state, postalCode: input.postalCode, lat: selected?.lat ?? null, lng: selected?.lng ?? null,
        geocodePrecision: selected ? pin?.manuallyConfirmed ? "MANUALLY_CONFIRMED" : "ROOFTOP" : null,
        geocodedAt: selected && !pin?.manuallyConfirmed ? new Date() : null, pinConfirmedAt: selected && pin ? new Date() : null } });
      return tx.job.create({ data: { customerId: customer.id, addressId: address.id, serviceId: service.id, durationMin,
        status: "PENDING", bookingRequestId: input.requestId, bookingRequestFingerprint: fingerprint,
        manualFollowUpStatus: input.followUp ? "PENDING" : null, manualFollowUpReason: input.followUp ? "ADDRESS_UNVERIFIED" : null } });
    });
  } catch (error) {
    if (!(error instanceof Prisma.PrismaClientKnownRequestError) || error.code !== "P2002") throw error;
    job = await prisma.job.findUnique({ where: { bookingRequestId: input.requestId } });
    if (!job) throw error;
  }
  if (job.bookingRequestFingerprint !== fingerprint) return conflict();
  return { job };
}
