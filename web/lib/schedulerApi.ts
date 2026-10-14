import { readFileSync } from "node:fs";
import { join } from "node:path";
import { z } from "zod";
import type { PublicSnapshot } from "./publicApi";
import { TENANT_ID_PATTERN, TENANT_TOKEN_PATTERN } from "./tenantTokens";

/**
 * The portal's client for the public scheduling API (/api/v1), acting as WaterFlex Software for one client at a time;
 * the portal schedules through nothing else. Each client's bearer token is read from the environment as
 * SCHEDULER_API_TOKEN_<CLIENT ID> (uppercase, hyphens as underscores) or, when that is unset, from the file named after
 * the client in SCHEDULER_API_TOKEN_DIR; it is never stored in the database. Before a
 * client's first call, the token's tenant is checked to be that client, so a misplaced token cannot act for another.
 */

const id = z.string().min(1).max(128);
/** Time a booking call may take beyond the client's scheduler deadline: the snapshot upload and the network. */
export const CALLER_MARGIN_MS = 3000;
const instant = z.iso.datetime({ offset: true });
const serviceDate = z.iso.date();
const window = z.object({ start: instant, end: instant }).strict();

const skippedTechnicianDay = z.object({ technicianId: id, serviceDate, reason: z.enum(["LOCATION_UNRESOLVED"]), message: z.string().min(1) }).strict();
const technicianDayVersion = z.object({ technicianId: id, serviceDate, lastModified: instant }).strict();
/** A refusal. Only a STALE commit carries `changed`: the technician-days whose recorded timestamp differs. */
export const problem = z.object({ error: z.enum(["INVALID_REQUEST", "NOT_FOUND", "STALE", "NOT_COMMITTABLE", "HOLD_UNAVAILABLE",
  "INCOMPLETE_FACTS", "BUSY", "ROUTING_UNAVAILABLE", "CALCULATION_UNAVAILABLE", "CANCELLED"]), message: z.string().min(1),
  changed: z.array(technicianDayVersion).min(1).optional() }).strict()
  .refine(value => (value.changed !== undefined) === (value.error === "STALE"), "Only a STALE problem lists changed technician-days");
/** `refinement` is absent only on a replay of an offer set answered before spec 1.2.0, and `incompleteDates` before 1.3.0. */
export const offerSet = z.object({ offerSetId: id, expiresAt: instant, searchComplete: z.boolean(),
  refinement: z.enum(["COMPLETE", "SHORTENED", "SKIPPED", "UNAVAILABLE", "SHED"]).optional(),
  incompleteDates: z.array(serviceDate).optional(),
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
const position = z.tuple([z.number().min(-180).max(180), z.number().min(-90).max(90)]);
const geometryRoute = z.object({ technicianId: id, serviceDate,
  stops: z.array(z.object({ appointmentId: id, sequence: z.int().min(0), lat: z.number().min(-90).max(90), lng: z.number().min(-180).max(180),
    source: z.enum(["HOST", "NOMINATIM"]) }).strict()),
  legs: z.array(z.object({ segment: z.int().min(0), legIndex: z.int().min(0), seconds: z.int().min(0), meters: z.int().min(0),
    coordinates: z.array(position).min(2) }).strict()),
}).strict().refine(value => (value.stops.length === 0) === (value.legs.length === 0), "A route is drawn exactly when it has stops");
/** A day's routes drawn on roads in their given order; a technician-day that could not be located is skipped. */
export const routeGeometry = z.object({ routingIdentity: z.string().min(1), routes: z.array(geometryRoute), skippedTechnicianDays: z.array(skippedTechnicianDay) }).strict();
const whoami = z.object({ tenantId: z.string().min(1) }).strict();
/** The answer a queued proposal ended with; its body is checked against the answer's own schema when it is read. */
const requestResult = z.object({ status: z.int().min(200).max(599), body: z.unknown(), retryAfterSeconds: z.int().min(1).max(300).optional() }).strict();
/** GET /api/v1/requests/{requestId}, and the 202 to a proposal sent with Prefer: respond-async (spec 1.4.0). */
export const requestStatus = z.object({ requestId: z.uuid(), operation: z.enum(["DAILY_PROPOSAL", "REPAIR_PROPOSAL"]),
  state: z.enum(["QUEUED", "RUNNING", "COMPLETED"]), queuedAt: instant.optional(), startedAt: instant.optional(), result: requestResult.optional(),
}).strict().refine(value => (value.state === "COMPLETED") === (value.result !== undefined), "A request has a result exactly when it is COMPLETED");

export type OfferSet = z.infer<typeof offerSet>;
export type Hold = z.infer<typeof hold>;
export type CommitReceipt = z.infer<typeof commitReceipt>;
export type DailyProposal = z.infer<typeof dailyProposal>;
export type RouteEvaluation = z.infer<typeof routeEvaluation>;
export type RouteGeometry = z.infer<typeof routeGeometry>;
export type TechnicianDayVersion = z.infer<typeof technicianDayVersion>;
export type ProblemCode = z.infer<typeof problem>["error"];
export type RequestStatus = z.infer<typeof requestStatus>;

/** A refused or failed call. `code` is the API's error, or TRANSPORT / MALFORMED / NOT_CONNECTED for the portal's own failures. */
export class SchedulerApiError extends Error {
  /** `retryAfterMs`: a 429's Retry-After in milliseconds, or null when the scheduler sent none. */
  constructor(readonly status: number, readonly code: ProblemCode | "TRANSPORT" | "MALFORMED" | "NOT_CONNECTED", message: string,
    readonly changed: readonly TechnicianDayVersion[] = [], readonly retryAfterMs: number | null = null) {
    super(message);
    this.name = "SchedulerApiError";
  }
}

type Environment = Readonly<Record<string, string | undefined>>;
/** A token file's contents, or null when the file does not exist. Any other failure throws. */
export type TokenFileReader = (path: string) => string | null;

export function tokenVariable(clientId: string): string {
  return `SCHEDULER_API_TOKEN_${clientId.toUpperCase().replaceAll("-", "_")}`;
}

const readTokenFile: TokenFileReader = path => {
  try { return readFileSync(path, "utf8"); }
  catch (error) { if (error instanceof Error && "code" in error && error.code === "ENOENT") return null; throw error; }
};

/**
 * The client's token: SCHEDULER_API_TOKEN_<CLIENT ID> when set, otherwise the file named after the client in
 * SCHEDULER_API_TOKEN_DIR (local Compose provisions these). Never another client's token, never a default.
 */
function clientToken(clientId: string, env: Environment, readToken: TokenFileReader): { token: string; source: string } {
  const variable = tokenVariable(clientId);
  const fromEnv = env[variable];
  if (fromEnv !== undefined && fromEnv !== "") return { token: fromEnv, source: variable };
  const dir = env.SCHEDULER_API_TOKEN_DIR;
  if (dir === undefined || dir === "") throw new SchedulerApiError(503, "NOT_CONNECTED", `Client ${clientId} has no scheduling API token (${variable})`);
  if (!TENANT_ID_PATTERN.test(clientId)) throw new SchedulerApiError(503, "NOT_CONNECTED", `Client ${clientId} cannot have a token file`);
  const file = join(dir, clientId);
  let contents: string | null;
  try { contents = readToken(file); }
  catch { throw new SchedulerApiError(503, "NOT_CONNECTED", `Token file ${file} cannot be read`); }
  if (contents === null) throw new SchedulerApiError(503, "NOT_CONNECTED", `Client ${clientId} has no scheduling API token (${variable} or ${file})`);
  return { token: contents.trim(), source: file };
}

/** The client's connection settings, or a NOT_CONNECTED error naming exactly what is missing. */
export function connection(clientId: string, env: Environment = process.env, readToken: TokenFileReader = readTokenFile): { baseUrl: string; token: string } {
  const baseUrl = env.SCHEDULER_API_URL;
  if (baseUrl === undefined || baseUrl === "") throw new SchedulerApiError(503, "NOT_CONNECTED", "SCHEDULER_API_URL is not set");
  let parsed: URL;
  try { parsed = new URL(baseUrl); } catch { throw new SchedulerApiError(503, "NOT_CONNECTED", "SCHEDULER_API_URL is not a URL"); }
  const loopback = ["localhost", "127.0.0.1", "[::1]"].includes(parsed.hostname);
  const internal = !parsed.hostname.includes(".");
  if (parsed.protocol !== "https:" && !(parsed.protocol === "http:" && (loopback || internal)))
    throw new SchedulerApiError(503, "NOT_CONNECTED", "SCHEDULER_API_URL must use https outside the local network");
  const { token, source } = clientToken(clientId, env, readToken);
  if (!TENANT_TOKEN_PATTERN.test(token)) throw new SchedulerApiError(503, "NOT_CONNECTED", `${source} is not a scheduling API token`);
  return { baseUrl: baseUrl.replace(/\/+$/, ""), token };
}

async function send(clientId: string, method: "GET" | "POST" | "DELETE", path: string, body: unknown, timeoutMs: number,
  extraHeaders: Readonly<Record<string, string>> = {}): Promise<Response> {
  const { baseUrl, token } = connection(clientId);
  let response: Response;
  try {
    response = await fetch(`${baseUrl}${path}`, { method, signal: AbortSignal.timeout(timeoutMs),
      headers: { ...extraHeaders, Authorization: `Bearer ${token}`, ...(body === undefined ? {} : { "Content-Type": "application/json" }) },
      ...(body === undefined ? {} : { body: JSON.stringify(body) }) });
  } catch (error) {
    const timedOut = error instanceof Error && (error.name === "TimeoutError" || error.name === "AbortError");
    throw new SchedulerApiError(timedOut ? 504 : 503, "TRANSPORT", timedOut ? "The scheduling service did not answer in time" : "The scheduling service is unreachable");
  }
  // The tenant filter answers an unknown, revoked or disabled token with a bare 401, not an API problem.
  if (response.status === 401) throw new SchedulerApiError(503, "NOT_CONNECTED", `The scheduling service does not accept client ${clientId}'s token`);
  return response;
}

async function call<T>(clientId: string, method: "GET" | "POST", path: string, body: unknown, schema: z.ZodType<T>, timeoutMs: number): Promise<T> {
  return parsed(await send(clientId, method, path, body, timeoutMs), path, schema);
}

/** A response's body checked against `schema`, or the refusal it carries as a SchedulerApiError. */
async function parsed<T>(response: Response, path: string, schema: z.ZodType<T>): Promise<T> {
  let payload: unknown;
  try { payload = await response.json(); }
  catch { throw new SchedulerApiError(502, "MALFORMED", `The scheduling service sent an unreadable ${response.status} response`); }
  if (!response.ok) {
    const refused = problem.safeParse(payload);
    if (!refused.success)
      throw new SchedulerApiError(502, "MALFORMED", `The scheduling service sent an unexpected ${response.status} response: ${JSON.stringify(payload).slice(0, 300)}`);
    throw new SchedulerApiError(response.status, refused.data.error, refused.data.message, refused.data.changed, retryAfterMs(response));
  }
  const parsed = schema.safeParse(payload);
  if (!parsed.success) throw new SchedulerApiError(502, "MALFORMED", `The scheduling service's ${path} response does not match the API`);
  return parsed.data;
}

/** A 429's Retry-After (whole seconds, as the scheduler sends it) in milliseconds; null when absent or not a positive integer. */
export function retryAfterMs(response: Pick<Response, "status" | "headers">): number | null {
  if (response.status !== 429) return null;
  const header = response.headers.get("retry-after");
  if (header === null || !/^[1-9][0-9]{0,5}$/.test(header)) return null;
  return Number(header) * 1000;
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

export function createBookingOffers(clientId: string, request: { requestId: string; offerLimit: number; windowHours: number;
  job: { id: string; serviceId: string; durationMinutes: number; location: { lat: number; lng: number } };
  horizon: { firstDate: string; lastDate: string }; snapshot: PublicSnapshot }): Promise<OfferSet> {
  // The API answers within the client's booking deadline; the extra time covers the snapshot upload and the network.
  return tenantCall(clientId, "/api/v1/booking/offers", request, offerSet, request.snapshot.policy.bookingDeadlineMs + CALLER_MARGIN_MS);
}

export function selectBookingOffer(clientId: string, offerId: string, requestId: string): Promise<Hold> {
  return tenantCall(clientId, `/api/v1/booking/offers/${encodeURIComponent(offerId)}/select`, { requestId }, hold, 5000);
}

export function releaseBookingOffer(clientId: string, offerId: string, requestId: string): Promise<{ released: true }> {
  return tenantCall(clientId, `/api/v1/booking/offers/${encodeURIComponent(offerId)}/release`, { requestId }, released, 5000);
}

/**
 * Asks the scheduler to cancel a calculation still running under `requestId`. "CANCELLING" means the call will answer
 * 409 CANCELLED; "NOT_RUNNING" means no replica that received this found it (finished, or running elsewhere), so the
 * call ends on its own.
 */
export async function cancelRequest(clientId: string, requestId: string): Promise<"CANCELLING" | "NOT_RUNNING"> {
  await verifyTenant(clientId);
  const response = await send(clientId, "DELETE", `/api/v1/requests/${encodeURIComponent(requestId)}`, undefined, 5000);
  if (response.status === 202) return "CANCELLING";
  let payload: unknown;
  try { payload = await response.json(); }
  catch { throw new SchedulerApiError(502, "MALFORMED", `The scheduling service sent an unreadable ${response.status} cancel response`); }
  const refused = problem.safeParse(payload);
  if (!refused.success) throw new SchedulerApiError(502, "MALFORMED", `The scheduling service sent an unexpected ${response.status} cancel response`);
  if (response.status === 404 && refused.data.error === "NOT_FOUND") return "NOT_RUNNING";
  throw new SchedulerApiError(response.status, refused.data.error, refused.data.message, refused.data.changed);
}

export function confirmBookingHold(clientId: string, holdId: string, requestId: string, snapshot: PublicSnapshot): Promise<CommitReceipt> {
  return tenantCall(clientId, `/api/v1/booking/holds/${encodeURIComponent(holdId)}/confirm`, { requestId, snapshot }, commitReceipt, 15000);
}

/**
 * How long to wait for a daily or repair proposal: the scheduler runs the client's cost and fairness search time plus a
 * 5-second reserve, and the rest covers the snapshot upload, routing and the network. The client's earlier fixed 15
 * seconds of search waits 30 seconds; the longest, 240 seconds, waits 255.
 */
export function proposalTimeoutMs(policy: PublicSnapshot["policy"]): number {
  return (policy.costSearchSeconds + policy.fairnessSearchSeconds + 5) * 1000 + 10000;
}

export function createDailyProposal(clientId: string, request: { requestId: string; serviceDate: string; snapshot: PublicSnapshot }): Promise<DailyProposal> {
  return tenantCall(clientId, "/api/v1/daily/proposals", request, dailyProposal, proposalTimeoutMs(request.snapshot.policy));
}

/** Seconds to wait before polling when a status carries no Retry-After. */
const DEFAULT_POLL_MS = 3000;

/** Retry-After in milliseconds for a poll, at least one second; the default when absent or malformed. */
export function pollAfterMs(response: Pick<Response, "headers">): number {
  const header = response.headers.get("retry-after");
  return header !== null && /^[1-9][0-9]{0,5}$/.test(header) ? Number(header) * 1000 : DEFAULT_POLL_MS;
}

/** What queuing a proposal answered: its status and when to poll it, or (for a requestId that already finished) the stored proposal. */
export type Queued = { readonly status: RequestStatus; readonly pollAfterMs: number } | { readonly proposal: DailyProposal };

/** Queues a daily proposal with Prefer: respond-async: 202 with the request's status, or 201 replaying a finished one. */
export async function queueDailyProposal(clientId: string, request: { requestId: string; serviceDate: string; snapshot: PublicSnapshot }): Promise<Queued> {
  await verifyTenant(clientId);
  const path = "/api/v1/daily/proposals";
  const response = await send(clientId, "POST", path, request, 30000, { Prefer: "respond-async" });
  if (response.status === 201) return { proposal: await parsed(response, path, dailyProposal) };
  if (response.status !== 202 && response.ok)
    throw new SchedulerApiError(502, "MALFORMED", `The scheduling service answered ${response.status} to a queued proposal`);
  return { status: await parsed(response, path, requestStatus), pollAfterMs: pollAfterMs(response) };
}

/** The status of a daily or repair proposal request, and when to poll it again. */
export async function readRequestStatus(clientId: string, requestId: string): Promise<{ status: RequestStatus; pollAfterMs: number }> {
  await verifyTenant(clientId);
  const path = `/api/v1/requests/${encodeURIComponent(requestId)}`;
  const response = await send(clientId, "GET", path, undefined, 10000);
  return { status: await parsed(response, path, requestStatus), pollAfterMs: pollAfterMs(response) };
}

export interface QueueDependencies {
  queue: typeof queueDailyProposal;
  read: typeof readRequestStatus;
  sleep: (ms: number) => Promise<void>;
  now: () => number;
}

const queueDefaults: QueueDependencies = { queue: queueDailyProposal, read: readRequestStatus,
  sleep: ms => new Promise(resolve => setTimeout(resolve, ms)), now: () => Date.now() };

/**
 * Queues a daily proposal and polls its status as Retry-After asks until it completes, within `limitMs`: the
 * proposal, or the refusal it ended with as a SchedulerApiError (a 429 keeps its Retry-After). The scheduler takes
 * queued proposals from every client in turn, so one client's many days never hold back another client's.
 */
export async function awaitDailyProposal(clientId: string, request: { requestId: string; serviceDate: string; snapshot: PublicSnapshot },
  limitMs: number, dependencies: Partial<QueueDependencies> = {}): Promise<DailyProposal> {
  const { queue, read, sleep, now } = { ...queueDefaults, ...dependencies };
  const until = now() + limitMs;
  const queued = await queue(clientId, request);
  if ("proposal" in queued) return queued.proposal;
  let current = queued;
  while (current.status.state !== "COMPLETED") {
    if (now() + current.pollAfterMs > until)
      throw new SchedulerApiError(504, "TRANSPORT", `The queued proposal was still ${current.status.state.toLowerCase()} after ${Math.round(limitMs / 1000)} seconds`);
    await sleep(current.pollAfterMs);
    current = await read(clientId, request.requestId);
    if (current.status.requestId !== request.requestId) throw new SchedulerApiError(502, "MALFORMED", "The scheduling service reported another request's status");
  }
  const result = current.status.result;
  if (result === undefined) throw new SchedulerApiError(502, "MALFORMED", "A completed request has no result");
  if (result.status === 201) {
    const proposal = dailyProposal.safeParse(result.body);
    if (!proposal.success) throw new SchedulerApiError(502, "MALFORMED", "The queued proposal does not match the API");
    return proposal.data;
  }
  const refused = problem.safeParse(result.body);
  if (!refused.success || result.status < 400) throw new SchedulerApiError(502, "MALFORMED", `The queued proposal ended with an unexpected ${result.status} result`);
  throw new SchedulerApiError(result.status, refused.data.error, refused.data.message, refused.data.changed,
    result.status === 429 && result.retryAfterSeconds !== undefined ? result.retryAfterSeconds * 1000 : null);
}

export function commitDailyProposal(clientId: string, proposalId: string, request: { requestId: string; technicianDays: TechnicianDayVersion[] }): Promise<CommitReceipt> {
  return tenantCall(clientId, `/api/v1/daily/proposals/${encodeURIComponent(proposalId)}/commit`, request, commitReceipt, 10000);
}

/** Times one metro day's routes in their given order, from the snapshot's start and end points. Nothing is stored. */
export function evaluateRoutes(clientId: string, request: { serviceDate: string; snapshot: PublicSnapshot }): Promise<RouteEvaluation> {
  // Routing only, inside the scheduler's 20-second deadline; the rest covers the upload and the network.
  return tenantCall(clientId, "/api/v1/routes/evaluate", request, routeEvaluation, 25000);
}

export function drawRoutes(clientId: string, request: { serviceDate: string; snapshot: PublicSnapshot }): Promise<RouteGeometry> {
  // Drawing calls the routing service once per 65 points under the scheduler's 20-second limit.
  return tenantCall(clientId, "/api/v1/routes/geometry", request, routeGeometry, 25000);
}

export function createRepairProposal(clientId: string, request: { requestId: string; absence: { technicianId: string; serviceDate: string; window: { start: string; end: string } };
  snapshot: PublicSnapshot }): Promise<DailyProposal> {
  // The same calculation as a daily proposal; a repair spends both search times in its one phase.
  return tenantCall(clientId, "/api/v1/repairs/proposals", request, dailyProposal, proposalTimeoutMs(request.snapshot.policy));
}
