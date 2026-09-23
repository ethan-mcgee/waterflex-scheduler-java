import { Prisma } from "@prisma/client";
import { readBody, bookingRequest } from "@/lib/contracts";
import { NextRequest, NextResponse } from "next/server";
import { prisma } from "@/lib/prisma";
import { GeocoderError, searchAddress } from "@/lib/geocode";
import { resolveMetroForLocation } from "@/lib/serviceArea";
import { requestSlots, EngineError } from "@/lib/engineClient";

export async function POST(req: NextRequest) {
  const parsed = await readBody(req, bookingRequest);
  if (!parsed.success) return NextResponse.json({ error: "Invalid booking request" }, { status: 400 });
  const input = parsed.data;
  const pin = input.confirmedPin;

  let candidates;
  try { candidates = await searchAddress(input); }
  catch (error) {
    if (!(error instanceof GeocoderError)) throw error;
    return NextResponse.json({ error: error.message }, { status: error.kind === "timeout" ? 504 : error.kind === "malformed" ? 502 : 503 });
  }
  const geocode = candidates[0] ?? null;
  const selected = pin
    ? candidates.find((candidate) => Math.abs(candidate.lat - pin.lat) < 0.0001
      && Math.abs(candidate.lng - pin.lng) < 0.0001)
    : geocode?.precision === "ROOFTOP" ? geocode : null;
  if (input.confirmedPin && !selected) return NextResponse.json({ error: "Please choose a verified address pin." }, { status: 422 });
  if (selected && !(await resolveMetroForLocation(selected.lat, selected.lng))) {
    return NextResponse.json(
      { error: "Sorry, that address is outside our current service area." },
      { status: 422 }
    );
  }

  const service = await prisma.serviceCatalog.findUnique({ where: { code: input.serviceCode } });
  if (!service || !service.active) {
    return NextResponse.json({ error: "That service is not currently available." }, { status: 400 });
  }
  const durationMin = Math.round(service.estDurationMin * (1 + service.bufferPct));

  let job = await prisma.job.findUnique({ where: { bookingRequestId: input.requestId } });
  if (job?.status === "SCHEDULED") {
    return NextResponse.json(
      { error: "This booking request has already been confirmed." },
      { status: 409 }
    );
  }

  if (!job) {
    try {
      job = await prisma.$transaction(async (tx) => {
        const existing = await tx.job.findUnique({ where: { bookingRequestId: input.requestId } });
        if (existing) return existing;

        const customer = await tx.customer.create({
          data: {
            firstName: input.firstName,
            lastName: input.lastName,
            email: input.email,
            phone: input.phone,
          },
        });
        const address = await tx.address.create({
          data: {
            customerId: customer.id,
            line1: input.line1,
            line2: input.line2,
            city: input.city,
            state: input.state,
            postalCode: input.postalCode,
            lat: selected?.lat,
            lng: selected?.lng,
            geocodePrecision: selected ? input.confirmedPin ? "CONFIRMED_PIN" : "ROOFTOP" : null,
            geocodedAt: selected ? new Date() : null,
          },
        });
        return tx.job.create({
          data: {
            customerId: customer.id,
            addressId: address.id,
            serviceId: service.id,
            durationMin,
            status: "PENDING",
            bookingRequestId: input.requestId,
            manualFollowUpStatus: !geocode ? "PENDING" : null,
            manualFollowUpReason: !geocode ? "ADDRESS_UNVERIFIED" : null,
          },
        });
      });
    } catch (err) {
      // Another request with the same id may have won the unique-key race.
      if ((err instanceof Prisma.PrismaClientKnownRequestError ? err.code : undefined) !== "P2002") throw err;
      job = await prisma.job.findUnique({ where: { bookingRequestId: input.requestId } });
      if (!job) throw err;
    }
  }

  if (!geocode) {
    await prisma.job.update({ where: { id: job.id }, data: {
      manualFollowUpStatus: "PENDING",
      manualFollowUpReason: "ADDRESS_UNVERIFIED",
    } });
    return NextResponse.json({ jobId: job.id, pendingReference: job.id, offers: [] });
  }
  if (!selected) {
    return NextResponse.json({ jobId: job.id, pinRequired: true, candidates: candidates.slice(0, 5) });
  }
  await prisma.$transaction([
    prisma.address.update({ where: { id: job.addressId }, data: {
      lat: selected.lat, lng: selected.lng,
      geocodePrecision: input.confirmedPin ? "CONFIRMED_PIN" : "ROOFTOP", geocodedAt: new Date(),
    } }),
    prisma.job.update({ where: { id: job.id }, data: { manualFollowUpStatus: null, manualFollowUpReason: null } }),
  ]);

  try {
    const { offers } = await requestSlots(job.id);
    if (offers.length === 0) await prisma.job.update({ where: { id: job.id }, data: {
      manualFollowUpStatus: "PENDING", manualFollowUpReason: "NO_CAPACITY",
    } });
    return NextResponse.json({ jobId: job.id, offers });
  } catch (err) {
    if (err instanceof EngineError) {
      if (err.status === 503) {
        await prisma.job.update({ where: { id: job.id }, data: { manualFollowUpStatus: "PENDING", manualFollowUpReason: "ROAD_ROUTING_UNAVAILABLE" } });
        return NextResponse.json({ jobId: job.id, pendingReference: job.id, offers: [] });
      }
      return NextResponse.json({ error: err.message }, { status: err.status === 404 ? 500 : err.status });
    }
    throw err;
  }
}
