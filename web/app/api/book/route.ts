import { NextRequest, NextResponse } from "next/server";
import { prisma } from "@/lib/prisma";
import { searchAddress } from "@/lib/geocode";
import { resolveMetroForLocation } from "@/lib/serviceArea";
import { requestSlots, EngineError } from "@/lib/engineClient";

interface BookingRequestBody {
  requestId: string;
  firstName: string;
  lastName: string;
  email: string;
  phone: string;
  line1: string;
  line2?: string;
  city: string;
  state: string;
  postalCode: string;
  serviceCode: string;
  confirmedPin?: { lat: number; lng: number };
}

const REQUIRED_FIELDS: (keyof BookingRequestBody)[] = [
  "requestId",
  "firstName",
  "lastName",
  "email",
  "phone",
  "line1",
  "city",
  "state",
  "postalCode",
  "serviceCode",
];

export async function POST(req: NextRequest) {
  const body = (await req.json()) as Partial<BookingRequestBody>;

  const missing = REQUIRED_FIELDS.filter((f) => !body[f]);
  if (missing.length > 0) {
    return NextResponse.json({ error: `Missing required fields: ${missing.join(", ")}` }, { status: 400 });
  }
  const input = body as BookingRequestBody;

  if (typeof input.requestId !== "string" || input.requestId.trim().length < 16) {
    return NextResponse.json({ error: "Missing or invalid booking request ID." }, { status: 400 });
  }

  const candidates = await searchAddress(input);
  const geocode = candidates[0] ?? null;
  const selected = input.confirmedPin
    ? candidates.find((candidate) => Math.abs(candidate.lat - input.confirmedPin!.lat) < 0.0001
      && Math.abs(candidate.lng - input.confirmedPin!.lng) < 0.0001)
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
      if ((err as { code?: string }).code !== "P2002") throw err;
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
