import { z } from "zod";
import type { PublicSnapshot } from "./publicApi";

/**
 * The portal's client for the public scheduling API (/api/v1), acting as WaterFlex Software for one client at a time.
 * Turned on with SCHEDULER_PUBLIC_API=true. Each client's bearer token is read from the environment as
 * SCHEDULER_API_TOKEN_<CLIENT ID> (uppercase, hyphens as underscores) and is never stored in the database. Before a
 * client's first call, the token's tenant is checked to be that client, so a misplaced token cannot act for another.
 */

const id = z.string().min(1).max(128);
const instant = z.iso.datetime({ offset: true });
const serviceDate = z.iso.date();
const window = z.object({ start: instant, end: instant }).strict();

export const problem = z.object({ error: z.enum(["INVALID_REQUEST", "NOT_FOUND", "STALE", "NOT_COMMITTABLE", "HOLD_UNAVAILABLE",
  "INCOMPLETE_FACTS", "BUSY", "ROUTING_UNAVAILABLE", "CALCULATION_UNAVAILABLE"]), message: z.string().min(1) }).strict();
export const offerSet = z.object({ offerSetId: id, expiresAt: instant, searchComplete: z.boolean(),
  offers: z.array(z.object({ offerId: id, serviceDate, window }).strict()).max(4),
  skippedTechnicianDays: z.array(z.object({ technicianId: id, serviceDate, reason: z.enum(["LOCATION_UNRESOLVED"]), message: z.string().min(1) }).strict()),
}).strict();
export const hold = z.object({ holdId: id, offerId: id, expiresAt: instant }).strict();
export const released = z.object({ released: z.literal(true) }).strict();
export const commitReceipt = z.object({ receiptId: id,
  assignments: z.array(z.object({ appointmentId: id, technicianId: id, serviceDate, sequence: z.int().min(0), plannedStart: instant, plannedEnd: instant }).strict()),
  technicianDays: z.array(z.object({ technicianId: id, serviceDate, lastModified: instant }).strict()),
}).strict();
const whoami = z.object({ tenantId: z.string().min(1) }).strict();

export type OfferSet = z.infer<typeof offerSet>;
export type Hold = z.infer<typeof hold>;
export type CommitReceipt = z.infer<typeof commitReceipt>;
export type ProblemCode = z.infer<typeof problem>["error"];

/** A refused or failed call. `code` is the API's error, or TRANSPORT / MALFORMED / NOT_CONNECTED for the portal's own failures. */
export class SchedulerApiError extends Error {
  constructor(readonly status: number, readonly code: ProblemCode | "TRANSPORT" | "MALFORMED" | "NOT_CONNECTED", message: string) {
    super(message);
    this.name = "SchedulerApiError";
  }
}

const TOKEN = /^wfs_[A-Za-z0-9_-]{43}$/;

type Environment = Readonly<Record<string, string | undefined>>;

/** Whether booking runs through the public API. Anything but "true", "false" or unset is a configuration error. */
export function publicApiEnabled(env: Environment = process.env): boolean {
  const value = env.SCHEDULER_PUBLIC_API;
  if (value === undefined || value === "" || value === "false") return false;
  if (value === "true") return true;
  throw new Error(`SCHEDULER_PUBLIC_API must be "true" or "false", not "${value}"`);
}

export function tokenVariable(clientId: string): string {
  return `SCHEDULER_API_TOKEN_${clientId.toUpperCase().replaceAll("-", "_")}`;
}

/** The client's connection settings, or a NOT_CONNECTED error naming exactly what is missing. */
export function connection(clientId: string, env: Environment = process.env): { baseUrl: string; token: string } {
  const baseUrl = env.SCHEDULER_API_URL;
  if (baseUrl === undefined || baseUrl === "") throw new SchedulerApiError(503, "NOT_CONNECTED", "SCHEDULER_API_URL is not set");
  let parsed: URL;
  try { parsed = new URL(baseUrl); } catch { throw new SchedulerApiError(503, "NOT_CONNECTED", "SCHEDULER_API_URL is not a URL"); }
  const loopback = ["localhost", "127.0.0.1", "[::1]"].includes(parsed.hostname);
  const internal = !parsed.hostname.includes(".");
  if (parsed.protocol !== "https:" && !(parsed.protocol === "http:" && (loopback || internal)))
    throw new SchedulerApiError(503, "NOT_CONNECTED", "SCHEDULER_API_URL must use https outside the local network");
  const variable = tokenVariable(clientId);
  const token = env[variable];
  if (token === undefined || token === "") throw new SchedulerApiError(503, "NOT_CONNECTED", `Client ${clientId} has no scheduling API token (${variable})`);
  if (!TOKEN.test(token)) throw new SchedulerApiError(503, "NOT_CONNECTED", `${variable} is not a scheduling API token`);
  return { baseUrl: baseUrl.replace(/\/+$/, ""), token };
}

async function call<T>(clientId: string, method: "GET" | "POST", path: string, body: unknown, schema: z.ZodType<T>, timeoutMs: number): Promise<T> {
  const { baseUrl, token } = connection(clientId);
  let response: Response;
  try {
    response = await fetch(`${baseUrl}${path}`, { method, signal: AbortSignal.timeout(timeoutMs),
      headers: { Authorization: `Bearer ${token}`, ...(body === undefined ? {} : { "Content-Type": "application/json" }) },
      ...(body === undefined ? {} : { body: JSON.stringify(body) }) });
  } catch (error) {
    const timedOut = error instanceof Error && (error.name === "TimeoutError" || error.name === "AbortError");
    throw new SchedulerApiError(timedOut ? 504 : 503, "TRANSPORT", timedOut ? "The scheduling service did not answer in time" : "The scheduling service is unreachable");
  }
  let payload: unknown;
  try { payload = await response.json(); }
  catch { throw new SchedulerApiError(502, "MALFORMED", `The scheduling service sent an unreadable ${response.status} response`); }
  if (!response.ok) {
    const refused = problem.safeParse(payload);
    if (!refused.success)
      throw new SchedulerApiError(502, "MALFORMED", `The scheduling service sent an unexpected ${response.status} response: ${JSON.stringify(payload).slice(0, 300)}`);
    throw new SchedulerApiError(response.status, refused.data.error, refused.data.message);
  }
  const parsed = schema.safeParse(payload);
  if (!parsed.success) throw new SchedulerApiError(502, "MALFORMED", `The scheduling service's ${path} response does not match the API`);
  return parsed.data;
}

const verified = new Map<string, Promise<void>>();

/** Checks once per process that the client's token belongs to the tenant with the client's own ID. */
export function verifyTenant(clientId: string): Promise<void> {
  const known = verified.get(clientId);
  if (known) return known;
  const check = call(clientId, "GET", "/api/v1/whoami", undefined, whoami, 5000).then(result => {
    if (result.tenantId !== clientId)
      throw new SchedulerApiError(503, "NOT_CONNECTED", `${tokenVariable(clientId)} belongs to tenant ${result.tenantId}, not ${clientId}`);
  });
  verified.set(clientId, check);
  check.catch(() => verified.delete(clientId));
  return check;
}

async function tenantCall<T>(clientId: string, path: string, body: unknown, schema: z.ZodType<T>, timeoutMs: number): Promise<T> {
  await verifyTenant(clientId);
  return call(clientId, "POST", path, body, schema, timeoutMs);
}

export function createBookingOffers(clientId: string, request: { requestId: string; offerLimit: number;
  job: { id: string; serviceId: string; durationMinutes: number; location: { lat: number; lng: number } };
  horizon: { firstDate: string; lastDate: string }; snapshot: PublicSnapshot }): Promise<OfferSet> {
  // The API answers within five seconds; the extra time covers the snapshot upload and the network.
  return tenantCall(clientId, "/api/v1/booking/offers", request, offerSet, 8000);
}

export function selectBookingOffer(clientId: string, offerId: string, requestId: string): Promise<Hold> {
  return tenantCall(clientId, `/api/v1/booking/offers/${encodeURIComponent(offerId)}/select`, { requestId }, hold, 5000);
}

export function releaseBookingOffer(clientId: string, offerId: string, requestId: string): Promise<{ released: true }> {
  return tenantCall(clientId, `/api/v1/booking/offers/${encodeURIComponent(offerId)}/release`, { requestId }, released, 5000);
}

export function confirmBookingHold(clientId: string, holdId: string, requestId: string, snapshot: PublicSnapshot): Promise<CommitReceipt> {
  return tenantCall(clientId, `/api/v1/booking/holds/${encodeURIComponent(holdId)}/confirm`, { requestId, snapshot }, commitReceipt, 15000);
}
