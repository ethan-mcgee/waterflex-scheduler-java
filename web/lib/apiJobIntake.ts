import { Prisma, type Job, type ServiceCatalog } from "@prisma/client";
import type { z } from "zod";
import type { accountBookingRequest, bookingRequest, workOrderRequest } from "./contracts";
import { prisma } from "./prisma";
import { GeocoderError, searchAddress, type GeocodeResult } from "./geocode";
import { bookingServiceArea, resolveMetroForLocation } from "./serviceArea";
import { ownsJob } from "./clientScope";
import { bookingFingerprint } from "./bookingIdentity";
import { findAccount, type WaterFlexAccount } from "./accountLookup";
import { allotTime, suggestedMinutes } from "./reasonCodesCore";

/**
 * Taking in a work order: the customer, the address and a PENDING job, under the request's retry key. Shared by the
 * New work order page and the schedule page's new-job form, so the rules live in one place:
 * - its reason codes must be active, and their minutes give the suggested time unless the dispatcher allots another;
 * - a WaterFlex account brings its own address and WaterFlex's coordinates, which must be inside the client's service
 *   area; an account without coordinates can only be a follow-up;
 * - a typed address needs a rooftop match or a pin the caller explicitly confirmed, inside the service area, or it is
 *   a follow-up with no location.
 */

export type BookingInput = z.infer<typeof bookingRequest>;
export type AccountBookingInput = z.infer<typeof accountBookingRequest>;
export type WorkOrderInput = z.infer<typeof workOrderRequest>;

/** The job, or the answer that refuses it (the status and the body the route sends). */
export type Refusal = { refusal: { status: number; body: { error: string; status?: string } } };
export type Intake = { job: Job } | Refusal;

const refuse = (status: number, error: string, code?: string): Refusal => ({ refusal: { status, body: code === undefined ? { error } : { status: code, error } } });

/** A geocoder failure as the answer a route sends; any other error is not this function's to answer. */
export function geocoderRefusal(error: unknown): { status: number; body: { status: string; error: string } } | null {
  if (!(error instanceof GeocoderError)) return null;
  return { status: error.kind === "timeout" ? 504 : error.kind === "malformed" ? 502 : 503, body: { status: "LOOKUP_FAILED", error: error.message } };
}

/** The request's codes from the catalog, in the order given, or the refusal when one is unknown or retired. */
async function reasonCodes(input: WorkOrderInput): Promise<ServiceCatalog[] | Refusal> {
  const codes = [input.serviceCode, ...(input.extraReasonCodes ?? [])];
  const rows = await prisma.serviceCatalog.findMany({ where: { code: { in: codes } } });
  const ordered: ServiceCatalog[] = [];
  for (const code of codes) {
    const row = rows.find(item => item.code === code);
    if (row === undefined || !row.active) return refuse(400, `Reason code ${code} is not currently available.`);
    ordered.push(row);
  }
  return ordered;
}

/** Where a typed address is: the geocoder's rooftop match or the caller's confirmed pin, inside the service area. */
async function typedLocation(clientId: string, input: BookingInput): Promise<{ typed: BookingInput; selected: GeocodeResult | null } | Refusal> {
  if (input.followUp) return { typed: input, selected: null };
  const pin = input.confirmedPin;
  let selected: GeocodeResult | null = null;
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
  return { typed: input, selected };
}

/** The account a request names, checked the way a typed pin is: it needs WaterFlex's coordinates unless a follow-up. */
async function accountLocation(clientId: string, input: AccountBookingInput): Promise<{ account: WaterFlexAccount } | Refusal> {
  const account = await findAccount(clientId, input.accountNumber);
  if (account === null) return refuse(404, `There is no account ${input.accountNumber} for this client.`, "ACCOUNT_NOT_FOUND");
  if (input.followUp) return { account };
  if (account.location === null)
    return refuse(422, `WaterFlex has no coordinates for account ${account.accountNumber}. Request a follow-up instead.`, "LOCATION_REQUIRED");
  if ((await resolveMetroForLocation(account.location.lat, account.location.lng, clientId)) == null)
    return refuse(422, `Account ${account.accountNumber} is outside our service area.`, "OUTSIDE_COVERAGE");
  return { account };
}

/**
 * The PENDING job for a request: the one already taken in under its request ID (when it is this client's, has the same
 * details and is still pending), or a new one. A geocoder failure throws GeocoderError (see geocoderRefusal).
 */
export async function intakeJob(clientId: string, input: WorkOrderInput): Promise<Intake> {
  const fingerprint = bookingFingerprint(input);
  const conflict = () => refuse(409, "This request ID belongs to different booking details. Start a new request.");
  let job = await prisma.job.findUnique({ where: { bookingRequestId: input.requestId } });
  if (job && !(await ownsJob(clientId, job.id))) return refuse(404, "Booking request not found");
  if (job && job.bookingRequestFingerprint !== fingerprint) return conflict();
  if (job && job.status !== "PENDING") return refuse(409, "This booking request has already been completed.");
  if (job) return { job };

  const codes = await reasonCodes(input);
  if (!Array.isArray(codes)) return codes;
  const [first, ...extra] = codes;
  if (first === undefined) return refuse(400, "A work order needs a reason code.");
  const allotted = allotTime(suggestedMinutes(codes), input.durationMin);
  if ("refusal" in allotted) return refuse(allotted.refusal.status, allotted.refusal.error);

  const located = input.accountNumber !== undefined ? await accountLocation(clientId, input) : await typedLocation(clientId, input);
  if ("refusal" in located) return located;
  const followUp = input.followUp === true;
  const details = { workOrderType: input.workOrderType ?? null, purchaseOrder: input.purchaseOrder ?? null,
    technicianMessage: input.technicianMessage ?? null, customerRequest: input.customerRequest ?? null, callFirst: input.callFirst ?? null };

  try {
    job = await prisma.$transaction(async tx => {
      const existing = await tx.job.findUnique({ where: { bookingRequestId: input.requestId } });
      if (existing) return existing;
      const contact = { firstName: input.firstName, lastName: input.lastName, email: input.email, phone: input.phone };
      let customerId: string, addressId: string;
      if ("account" in located) {
        const { account } = located;
        // One customer per account, kept to the contact details the dispatcher last confirmed.
        const customer = await tx.customer.upsert({ where: { accountId: account.id }, update: contact, create: { clientId, accountId: account.id, ...contact } });
        const place = { line1: account.line1, line2: account.line2, city: account.city, state: account.state, postalCode: account.postalCode,
          lat: account.location?.lat ?? null, lng: account.location?.lng ?? null };
        const reused = await tx.address.findFirst({ where: { customerId: customer.id, ...place } });
        const address = reused ?? await tx.address.create({ data: { customerId: customer.id, ...place,
          geocodePrecision: account.location === null ? null : "WATERFLEX_ACCOUNT", geocodedAt: account.location === null ? null : new Date(account.importedAt) } });
        customerId = customer.id; addressId = address.id;
      } else {
        const { typed, selected } = located;
        const pin = typed.confirmedPin;
        const customer = await tx.customer.create({ data: { clientId, ...contact } });
        const address = await tx.address.create({ data: { customerId: customer.id, line1: typed.line1, line2: typed.line2,
          city: typed.city, state: typed.state, postalCode: typed.postalCode, lat: selected?.lat ?? null, lng: selected?.lng ?? null,
          geocodePrecision: selected ? pin?.manuallyConfirmed ? "MANUALLY_CONFIRMED" : "ROOFTOP" : null,
          geocodedAt: selected && !pin?.manuallyConfirmed ? new Date() : null, pinConfirmedAt: selected && pin ? new Date() : null } });
        customerId = customer.id; addressId = address.id;
      }
      return tx.job.create({ data: { customerId, addressId, serviceId: first.id, durationMin: allotted.durationMin,
        suggestedDurationMin: allotted.suggestedMin, durationOverridden: allotted.overridden, ...details,
        reasonCodes: { create: extra.map((code, index) => ({ position: index + 2, reasonCodeId: code.id })) },
        status: "PENDING", bookingRequestId: input.requestId, bookingRequestFingerprint: fingerprint,
        manualFollowUpStatus: followUp ? "PENDING" : null,
        manualFollowUpReason: !followUp ? null : !("account" in located) ? "ADDRESS_UNVERIFIED"
          : located.account.location === null ? "ACCOUNT_UNLOCATED" : "FOLLOW_UP_REQUESTED" } });
    });
  } catch (error) {
    if (!(error instanceof Prisma.PrismaClientKnownRequestError) || error.code !== "P2002") throw error;
    job = await prisma.job.findUnique({ where: { bookingRequestId: input.requestId } });
    if (!job) throw error;
  }
  if (job.bookingRequestFingerprint !== fingerprint) return conflict();
  return { job };
}
