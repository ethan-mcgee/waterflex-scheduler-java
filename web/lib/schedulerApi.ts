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

const skippedTechnicianDay = z.object({ technicianId: id, serviceDate, reason: z.enum(["LOCATION_UNRESOLVED"]), message: z.string().min(1) }).strict();
const technicianDayVersion = z.object({ technicianId: id, serviceDate, lastModified: instant }).strict();
/** A refusal. Only a STALE commit carries `changed`: the technician-days whose recorded timestamp differs. */
export const problem = z.object({ error: z.enum(["INVALID_REQUEST", "NOT_FOUND", "STALE", "NOT_COMMITTABLE", "HOLD_UNAVAILABLE",
  "INCOMPLETE_FACTS", "BUSY", "ROUTING_UNAVAILABLE", "CALCULATION_UNAVAILABLE"]), message: z.string().min(1),
  changed: z.array(technicianDayVersion).min(1).optional() }).strict()
  .refine(value => (value.changed !== undefined) === (value.error === "STALE"), "Only a STALE problem lists changed technician-days");
export const offerSet = z.object({ offerSetId: id, expiresAt: instant, searchComplete: z.boolean(),
  offers: z.array(z.object({ offerId: id, serviceDate, window }).strict()).max(4),
  skippedTechnicianDays: z.array(skippedTechnicianDay),
}).strict();
export const hold = z.object({ holdId: id, offerId: id, expiresAt: instant }).strict();
export const released = z.object({ released: z.literal(true) }).strict();
export const commitReceipt = z.object({ receiptId: id,
  assignments: z.array(z.object({ appointmentId: id, technicianId: id, serviceDate, sequence: z.int().min(0), plannedStart: instant, plannedEnd: instant }).strict()),
  technicianDays: z.array(technicianDayVersion),
}).strict();
const plannedRoute = z.object({ technicianId: id, serviceDate, stops: z.array(z.object({ appointmentId: id, sequence: z.int().min(0),
  plannedStart: instant, plannedEnd: instant }).strict()) }).strict();
export const dailyProposal = z.object({ proposalId: id, inputRevision: z.string().regex(/^[0-9a-f]{64}$/),
  decision: z.enum(["IMPROVED", "NO_IMPROVEMENT", "REJECTED_BY_POLICY"]), reason: z.string().min(1),
  routes: z.array(plannedRoute),
  unresolvedAppointmentIds: z.array(id), skippedTechnicianDays: z.array(skippedTechnicianDay),
  costCents: z.int().min(0), overtimeMinutes: z.literal(0),
}).strict();
/** A day's routes timed in their given order; a day that does not hold has no routes. */
export const routeEvaluation = z.object({ feasible: z.boolean(), routes: z.array(plannedRoute), skippedTechnicianDays: z.array(skippedTechnicianDay),
  costCents: z.int().min(0), overtimeMinutes: z.int().min(0).max(1440),
}).strict().refine(value => value.feasible || value.routes.length === 0, "A day that does not hold has no timed routes");
const whoami = z.object({ tenantId: z.string().min(1) }).strict();

export type OfferSet = z.infer<typeof offerSet>;
export type Hold = z.infer<typeof hold>;
export type CommitReceipt = z.infer<typeof commitReceipt>;
export type DailyProposal = z.infer<typeof dailyProposal>;
export type RouteEvaluation = z.infer<typeof routeEvaluation>;
export type TechnicianDayVersion = z.infer<typeof technicianDayVersion>;
export type ProblemCode = z.infer<typeof problem>["error"];

/** A refused or failed call. `code` is the API's error, or TRANSPORT / MALFORMED / NOT_CONNECTED for the portal's own failures. */
export class SchedulerApiError extends Error {
  constructor(readonly status: number, readonly code: ProblemCode | "TRANSPORT" | "MALFORMED" | "NOT_CONNECTED", message: string,
    readonly changed: readonly TechnicianDayVersion[] = []) {
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
    throw new SchedulerApiError(response.status, refused.data.error, refused.data.message, refused.data.changed);
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

export function createDailyProposal(clientId: string, request: { requestId: string; serviceDate: string; snapshot: PublicSnapshot }): Promise<DailyProposal> {
  // The calculation stops at its own 20-second deadline; the rest covers the snapshot upload, routing and the network.
  return tenantCall(clientId, "/api/v1/daily/proposals", request, dailyProposal, 30000);
}

export function commitDailyProposal(clientId: string, proposalId: string, request: { requestId: string; technicianDays: TechnicianDayVersion[] }): Promise<CommitReceipt> {
  return tenantCall(clientId, `/api/v1/daily/proposals/${encodeURIComponent(proposalId)}/commit`, request, commitReceipt, 10000);
}

/** Times one metro day's routes in their given order, from the snapshot's start and end points. Nothing is stored. */
export function evaluateRoutes(clientId: string, request: { serviceDate: string; snapshot: PublicSnapshot }): Promise<RouteEvaluation> {
  // Routing only, inside the scheduler's 20-second deadline; the rest covers the upload and the network.
  return tenantCall(clientId, "/api/v1/routes/evaluate", request, routeEvaluation, 25000);
}

export function createRepairProposal(clientId: string, request: { requestId: string; absence: { technicianId: string; serviceDate: string; window: { start: string; end: string } };
  snapshot: PublicSnapshot }): Promise<DailyProposal> {
  // The same 20-second calculation as a daily proposal.
  return tenantCall(clientId, "/api/v1/repairs/proposals", request, dailyProposal, 30000);
}
