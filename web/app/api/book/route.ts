import { Prisma } from "@prisma/client";
import { readBody, bookingRequest } from "@/lib/contracts";
import { NextRequest, NextResponse } from "next/server";
import { prisma } from "@/lib/prisma";
import { GeocoderError, searchAddress, type GeocodeResult } from "@/lib/geocode";
import { bookingServiceArea, resolveMetroForLocation } from "@/lib/serviceArea";
import { activeClient } from "@/lib/activeClient";
import { notFound, ownsJob } from "@/lib/clientScope";
import { startBookingSearch, requestSlots, validateBookingLocation, EngineError } from "@/lib/engineClient";
import { bookingFingerprint } from "@/lib/bookingIdentity";
import { publicApiEnabled } from "@/lib/schedulerApi";
import { apiBookingStep } from "@/lib/apiBookingRoute";
import { searchApiOffers } from "@/lib/apiBooking";

export async function POST(req: NextRequest) {
  const parsed = await readBody(req, bookingRequest);
  if (!parsed.success) return NextResponse.json({ error: "Invalid booking request" }, { status: 400 });
  const input = parsed.data;
  const client = await activeClient(req);
  const fingerprint = bookingFingerprint(input);
  const conflict = () => NextResponse.json({ error: "This request ID belongs to different booking details. Start a new request." }, { status: 409 });
  let job = await prisma.job.findUnique({ where: { bookingRequestId: input.requestId } });
  if (job && !(await ownsJob(client.id, job.id))) return notFound("Booking request");
  if (job && job.bookingRequestFingerprint !== fingerprint) return conflict();
  if (job && job.status !== "PENDING") return NextResponse.json({ error: "This booking request has already been completed." }, { status: 409 });
  try {
    if (!job) {
      const service = await prisma.serviceCatalog.findUnique({ where: { code: input.serviceCode } });
      if (!service?.active) return NextResponse.json({ error: "That service is not currently available." }, { status: 400 });
      const durationMin = Math.round(service.estDurationMin * (1 + service.bufferPct));
      if (!Number.isSafeInteger(durationMin) || durationMin <= 0) throw new EngineError(503, "Service duration is unavailable.");
      let selected: GeocodeResult | null = null;
      const pin = input.confirmedPin;
      if (!input.followUp) {
        if (pin?.manuallyConfirmed) selected = { lat: pin.lat, lng: pin.lng, precision: "APPROXIMATE" };
        else {
          const area = await bookingServiceArea(client.id);
          if (area == null) return NextResponse.json({ status: "OUTSIDE_COVERAGE", error: "This client has no service area yet." }, { status: 422 });
          const candidates = await searchAddress(input, area.bounds);
          selected = candidates.find(c => c.precision === "ROOFTOP" && (!pin ||
            (Math.abs(c.lat - pin.lat) < 0.0001 && Math.abs(c.lng - pin.lng) < 0.0001))) ?? null;
        }
        if (!selected) return NextResponse.json({ status: "LOCATION_REQUIRED", error: "Review and explicitly confirm your service location, or request follow-up." }, { status: 422 });
        // The engine checks coverage against every depot; the pin must also be inside this client's own area.
        if ((await resolveMetroForLocation(selected.lat, selected.lng, client.id)) == null)
          return NextResponse.json({ status: "OUTSIDE_COVERAGE", error: "That pin is outside our service area." }, { status: 422 });
        const validation = await validateBookingLocation(selected, req.signal);
        if (validation.status !== "VALID") {
          const messages = { OUTSIDE_COVERAGE: "That pin is outside our service area.", UNROUTABLE: "We cannot reach that pin by road. Place it at your driveway entrance.",
            ROUTING_UNAVAILABLE: "Road validation is unavailable. Please retry." };
          return NextResponse.json({ status: validation.status, error: messages[validation.status] }, { status: validation.status === "ROUTING_UNAVAILABLE" ? 503 : 422 });
        }
      }
      try {
        job = await prisma.$transaction(async tx => {
          const existing = await tx.job.findUnique({ where: { bookingRequestId: input.requestId } });
          if (existing) return existing;
          const customer = await tx.customer.create({ data: { clientId: client.id, firstName: input.firstName, lastName: input.lastName, email: input.email, phone: input.phone } });
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
    }
    if (input.followUp) return NextResponse.json({ status: "FOLLOW_UP", jobId: job.id, pendingReference: job.id });
    if (publicApiEnabled()) {
      const jobId = job.id;
      return apiBookingStep(client.id, jobId, () => searchApiOffers(client.id, jobId));
    }
    if (input.backgroundSearch) {
      const search = await startBookingSearch(job.id, input.requestId, false);
      return NextResponse.json({ jobId: job.id, searchRequestId: search.id });
    }
    return NextResponse.json(await requestSlots(job.id, false, 5000, req.signal));
  } catch (error) {
    if (error instanceof GeocoderError) return NextResponse.json({ status: "LOOKUP_FAILED", error: error.message }, { status: error.kind === "timeout" ? 504 : error.kind === "malformed" ? 502 : 503 });
    if (error instanceof EngineError) return NextResponse.json({ error: error.message }, { status: error.status });
    throw error;
  }
}
