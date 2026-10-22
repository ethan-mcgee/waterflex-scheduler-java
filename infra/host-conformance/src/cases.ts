// The conformance cases. Each is self-contained: it builds its own snapshot under the run's unique IDs, uses its own
// requestIds and service dates, and leaves no open hold behind, so cases neither depend on nor disturb each other.
import { randomUUID } from "node:crypto";
import { isDeepStrictEqual } from "node:util";
import type { z } from "zod";
import * as Api from "./api.ts";
import type { Reply, SchedulerApi } from "./client.ts";
import type { Point } from "./points.ts";
import { KIT_POLICY, SnapshotBuilder, UNLOCATABLE, versions, type Snapshot } from "./snapshot.ts";
import { addDays, wallClock } from "./time.ts";

export interface Context {
  readonly api: SchedulerApi;
  readonly metroId: string;
  readonly timeZone: string;
  /** At least four distinct routable points: a home, a visit point and two job points. */
  readonly points: readonly Point[];
  /** Prefix of every ID this run sends, unique per run. */
  readonly run: string;
  /** Today in the metro's zone when the run started. */
  readonly today: string;
  readonly lastModified: string;
}

export interface Case {
  readonly group: "Integration" | "Strict parsing" | "Routes" | "Booking" | "Daily" | "Repair" | "Cancellation";
  readonly name: string;
  /** What a passing case proves to the host. */
  readonly proves: string;
  readonly run: (context: Context) => Promise<void>;
}

export class CaseFailure extends Error { }

function fail(message: string): never {
  throw new CaseFailure(message);
}

function check(condition: boolean, message: string): asserts condition {
  if (!condition) fail(message);
}

function describe(reply: Reply): string {
  const body = reply.text.length > 400 ? `${reply.text.slice(0, 400)}...` : reply.text;
  return `HTTP ${reply.status}${body === "" ? "" : ` ${body}`}`;
}

/** The reply must have `status` and a body matching the spec's schema exactly. */
function expectBody<T extends z.ZodType>(reply: Reply, status: number, schema: T, what: string): z.infer<T> {
  check(reply.status === status, `${what}: expected HTTP ${status}, got ${describe(reply)}`);
  check(reply.json !== undefined, `${what}: the body is not JSON: ${reply.text.slice(0, 200)}`);
  const parsed = schema.safeParse(reply.json);
  if (!parsed.success)
    fail(`${what}: the body does not match the spec: ${parsed.error.issues.map(issue => `${issue.path.join(".") || "(body)"} ${issue.message}`).join("; ")}`);
  return parsed.data;
}

/** The reply must be the named Problem; the message is for people and never compared. */
function expectProblem(reply: Reply, status: number, code: Api.ErrorCode, what: string): z.infer<typeof Api.Problem> {
  const problem = expectBody(reply, status, Api.Problem, what);
  check(problem.error === code, `${what}: expected error ${code}, got ${problem.error}: ${problem.message}`);
  return problem;
}

const sameInstant = (a: string, b: string) => Date.parse(a) === Date.parse(b);
const minutes = (from: string, to: string) => (Date.parse(to) - Date.parse(from)) / 60_000;

/** Search time plus the contract's five seconds, plus margin for the network. */
const PROPOSAL_TIMEOUT_MS = (KIT_POLICY.costSearchSeconds + KIT_POLICY.fairnessSearchSeconds + 5) * 1000 + 30_000;
const BOOKING_TIMEOUT_MS = KIT_POLICY.bookingDeadlineMs + 10_000;

function ids(context: Context, tag: string) {
  return (name: string) => `${context.run}-${tag}-${name}`;
}

function builder(context: Context): SnapshotBuilder {
  return new SnapshotBuilder(context.metroId, context.timeZone, `${context.run}-service`, context.lastModified);
}

function home(context: Context): Point {
  return context.points[0] ?? fail("No home point");
}

function visitPoint(context: Context): Point {
  return context.points[1] ?? fail("No visit point");
}

function jobPoint(context: Context, index: number): Point {
  return context.points[2 + index] ?? fail(`No job point ${index}`);
}

/**
 * Two technicians from the same home, each making one 45-minute visit to the same place at 09:00: one technician
 * making both visits saves a round trip, so a daily proposal for it is IMPROVED.
 */
function sharedVisit(context: Context, tag: string, date: string) {
  const id = ids(context, tag);
  const a = id("tech-a"), b = id("tech-b"), visitA = id("visit-a"), visitB = id("visit-b");
  const snapshot = builder(context).day(a, date, home(context)).day(b, date, home(context))
    .visit(visitA, a, date, visitPoint(context), 0, 540).visit(visitB, b, date, visitPoint(context), 0, 540).build();
  return { snapshot, a, b, visits: [visitA, visitB] };
}

/** One technician on `date` with one visit at 09:00, the day a booking search fits a job into. */
function bookingDay(context: Context, tag: string, date: string) {
  const id = ids(context, tag);
  const technician = id("tech"), visit = id("visit");
  const snapshot = builder(context).day(technician, date, home(context)).visit(visit, technician, date, visitPoint(context), 0, 540).build();
  return { snapshot, technician, visit, jobId: id("job") };
}

function offersBody(context: Context, snapshot: Snapshot, jobId: string, first: string, last: string, offerLimit = 2) {
  return { requestId: randomUUID(), job: { id: jobId, serviceId: `${context.run}-service`, durationMinutes: 60, location: jobPoint(context, 0) },
    horizon: { firstDate: first, lastDate: last }, offerLimit, windowHours: 2, snapshot };
}

async function propose(context: Context, body: object, what: string): Promise<Api.DailyProposal> {
  return expectBody(await context.api.call("POST", "/api/v1/daily/proposals", body, { timeoutMs: PROPOSAL_TIMEOUT_MS }), 201, Api.DailyProposal, what);
}

async function offers(context: Context, body: object, what: string): Promise<{ set: Api.OfferSet; receivedAt: number }> {
  const reply = await context.api.call("POST", "/api/v1/booking/offers", body, { timeoutMs: BOOKING_TIMEOUT_MS });
  const set = expectBody(reply, 201, Api.OfferSet, what);
  // Every offer set this kit gets was answered by a 1.3.0 scheduler, which always says how the refinement ended and
  // which dates the search did not finish.
  check(set.refinement !== undefined, `${what}: no refinement outcome`);
  const incomplete = set.incompleteDates;
  check(incomplete !== undefined, `${what}: no incompleteDates`);
  check(incomplete.every((date, index) => index === 0 || (incomplete[index - 1] ?? "") < date), `${what}: incompleteDates are not ascending`);
  check(!set.searchComplete || incomplete.length === 0, `${what}: a complete search lists incomplete dates`);
  return { set, receivedAt: Date.now() };
}

function firstOffer(set: Api.OfferSet, what: string) {
  const offer = set.offers[0];
  check(offer !== undefined, `${what}: no offer for an open day (searchComplete ${set.searchComplete})`);
  return offer;
}

const requestOnly = () => ({ requestId: randomUUID() });

const RESPOND_ASYNC = { Prefer: "respond-async" } as const;
/** How long a queued request may take: a wait behind other work on a busy scheduler, then its own calculation. */
const QUEUED_TIMEOUT_MS = PROPOSAL_TIMEOUT_MS + 120_000;

/** A 202 to Prefer: respond-async: Location names the request's status, Retry-After when to poll, and the body is its status. */
function expectQueued(reply: Reply, requestId: string, what: string): Api.RequestStatus {
  const status = expectBody(reply, 202, Api.RequestStatus, what);
  check(reply.headers.get("location") === `/api/v1/requests/${requestId}`, `${what}: Location is ${reply.headers.get("location") ?? "missing"}`);
  check(Number(reply.headers.get("retry-after")) >= 1, `${what}: no Retry-After`);
  check(reply.headers.get("preference-applied") === "respond-async", `${what}: no Preference-Applied: respond-async`);
  check(status.requestId === requestId && status.state !== "COMPLETED" && status.result === undefined, `${what}: not waiting or running: ${reply.text}`);
  return status;
}

/** Polls GET /api/v1/requests/{requestId} as Retry-After asks until the request completes, and returns its status. */
async function awaitCompleted(context: Context, requestId: string, what: string): Promise<Api.RequestStatus> {
  const until = Date.now() + QUEUED_TIMEOUT_MS;
  while (true) {
    const reply = await context.api.call("GET", `/api/v1/requests/${requestId}`);
    const status = expectBody(reply, 200, Api.RequestStatus, what);
    check(status.requestId === requestId, `${what}: the status names another request`);
    check((status.state === "COMPLETED") === (status.result !== undefined), `${what}: a result exactly when COMPLETED`);
    if (status.state === "COMPLETED") return status;
    check(Date.now() < until, `${what}: still ${status.state} after ${QUEUED_TIMEOUT_MS / 1000} s`);
    const wait = Number(reply.headers.get("retry-after"));
    check(wait >= 1, `${what}: a ${status.state} status without Retry-After`);
    await new Promise(resolve => setTimeout(resolve, Math.min(wait, 5) * 1000));
  }
}

/** The day's appointments as the kit sent them must all appear exactly once across the proposal's routes. */
function checkRoutesCover(proposal: Api.DailyProposal, appointmentIds: readonly string[], what: string) {
  const routed = proposal.routes.flatMap(route => route.stops.map(stop => stop.appointmentId)).sort();
  check(isDeepStrictEqual(routed, [...appointmentIds].sort()), `${what}: routes hold ${routed.join(", ")}, expected ${appointmentIds.join(", ")}`);
}

export const STRICT_MUTATIONS: readonly { name: string; proves: string; mutate: (body: { serviceDate: string; snapshot: Snapshot } & Record<string, unknown>) => string | object }[] = [
  { name: "unknown field", proves: "A field the contract does not define is refused, so a host typo never passes silently.",
    mutate: body => ({ ...body, comment: "not in the contract" }) },
  { name: "duplicate key", proves: "A body with the same key twice is refused rather than resolved by whichever value wins.",
    mutate: body => `{"serviceDate":"${body.serviceDate}",${JSON.stringify(body).slice(1)}` },
  { name: "numeric money", proves: "Money is a decimal string; a JSON number is refused.",
    mutate: body => { const copy = structuredClone(body); (copy.snapshot.rates as Record<string, unknown>).regularHourly = 30; return copy; } },
  { name: "hourly rate above its cap", proves: "An hourly rate is at most 500 (spec 1.7.0); a dearer one is refused, never priced another way.",
    mutate: body => { const copy = structuredClone(body); copy.snapshot.rates.overtimeHourly = "500.0001"; return copy; } },
  { name: "five-place mileage rate", proves: "A cost rate has at most four decimal places (spec 1.7.0); a fifth is refused, never rounded.",
    mutate: body => { const copy = structuredClone(body); copy.snapshot.rates.mileagePerMile = "0.65555"; return copy; } },
  { name: "missing plannedStart", proves: "Appointment.plannedStart is required and never assumed.",
    mutate: body => { const copy = structuredClone(body); delete (copy.snapshot.appointments[0] as Partial<Snapshot["appointments"][number]>).plannedStart; return copy; } },
  { name: "missing absences", proves: "TechnicianDay.absences is required; an empty list must be sent explicitly.",
    mutate: body => { const copy = structuredClone(body); delete (copy.snapshot.technicianDays[0] as Partial<Snapshot["technicianDays"][number]>).absences; return copy; } },
  { name: "tenantId in the body", proves: "The tenant comes only from the token; a request naming one is refused.",
    mutate: body => ({ ...body, tenantId: "another-tenant" }) },
];

export const CASES: readonly Case[] = [
  {
    group: "Integration", name: "no token", proves: "Calls without a valid bearer token are refused with 401 and a Bearer challenge.",
    run: async context => {
      for (const [token, label] of [[null, "no token"], ["wfs_not-a-real-token", "a malformed token"]] as const) {
        const reply = await context.api.call("GET", "/api/v1/whoami", undefined, { token });
        check(reply.status === 401, `whoami with ${label}: expected HTTP 401, got ${describe(reply)}`);
        check(reply.headers.get("www-authenticate") === "Bearer", `whoami with ${label}: WWW-Authenticate is "${reply.headers.get("www-authenticate")}", expected "Bearer"`);
      }
    },
  },
  {
    group: "Integration", name: "whoami", proves: "The token is active and names the tenant every other call runs as.",
    run: async context => { expectBody(await context.api.call("GET", "/api/v1/whoami"), 200, Api.WhoAmI, "whoami"); },
  },
  ...STRICT_MUTATIONS.map((mutation): Case => ({
    group: "Strict parsing", name: mutation.name, proves: mutation.proves,
    run: async context => {
      const date = addDays(context.today, 2);
      const body = { requestId: randomUUID(), serviceDate: date, snapshot: sharedVisit(context, "strict", date).snapshot };
      const reply = await context.api.call("POST", "/api/v1/daily/proposals", mutation.mutate(body), { timeoutMs: PROPOSAL_TIMEOUT_MS });
      expectProblem(reply, 400, "INVALID_REQUEST", `A daily proposal with ${mutation.name}`);
    },
  })),
  {
    group: "Routes", name: "evaluate", proves: "A day's routes are timed in the host's order and reported feasible without storing anything.",
    run: async context => {
      const date = addDays(context.today, 2);
      const id = ids(context, "evaluate");
      const snapshot = builder(context).day(id("tech"), date, home(context))
        .visit(id("first"), id("tech"), date, visitPoint(context), 0, 540).visit(id("second"), id("tech"), date, jobPoint(context, 0), 1, 660).build();
      const evaluation = expectBody(await context.api.call("POST", "/api/v1/routes/evaluate", { serviceDate: date, snapshot }, { timeoutMs: 30_000 }),
        200, Api.RouteEvaluation, "Route evaluation");
      check(evaluation.feasible, "Route evaluation: a day with two visits hours apart is not feasible");
      check(evaluation.skippedTechnicianDays.length === 0, "Route evaluation: a located day was skipped");
      const stops = evaluation.routes.find(route => route.technicianId === id("tech"))?.stops.map(stop => stop.appointmentId);
      check(isDeepStrictEqual(stops, [id("first"), id("second")]), `Route evaluation: stops ${String(stops)} are not in the host's order`);
      check(evaluation.overtimeMinutes === 0, `Route evaluation: ${evaluation.overtimeMinutes} overtime minutes on a short day`);
    },
  },
  {
    group: "Routes", name: "geometry", proves: "Each drawn stop carries the host's coordinates with source HOST, and every leg is drawn.",
    run: async context => {
      const date = addDays(context.today, 2);
      const id = ids(context, "geometry");
      const snapshot = builder(context).day(id("tech"), date, home(context))
        .visit(id("first"), id("tech"), date, visitPoint(context), 0, 540).visit(id("second"), id("tech"), date, jobPoint(context, 0), 1, 660).build();
      const geometry = expectBody(await context.api.call("POST", "/api/v1/routes/geometry", { serviceDate: date, snapshot }, { timeoutMs: 30_000 }),
        200, Api.RouteGeometry, "Route geometry");
      const route = geometry.routes.find(item => item.technicianId === id("tech"));
      check(route !== undefined, "Route geometry: the technician-day was not drawn");
      check(route.stops.length === 2, `Route geometry: ${route.stops.length} stops drawn, expected 2`);
      for (const stop of route.stops) {
        const sent = snapshot.appointments.find(appointment => appointment.id === stop.appointmentId);
        check(sent !== undefined, `Route geometry: unknown stop ${stop.appointmentId}`);
        check(stop.source === "HOST", `Route geometry: stop ${stop.appointmentId} has source ${stop.source}, but the host sent coordinates`);
        check("lat" in sent.location && stop.lat === sent.location.lat && stop.lng === sent.location.lng,
          `Route geometry: stop ${stop.appointmentId} is not at the host's coordinates`);
      }
      check(route.legs.length === 3, `Route geometry: ${route.legs.length} legs, expected 3 (home, two visits, home)`);
      check(route.legs.every((leg, index) => leg.legIndex === index && leg.segment === 0), "Route geometry: legs are not numbered 0, 1, 2 in segment 0");
    },
  },
  {
    group: "Routes", name: "unlocatable day is skipped", proves: "A technician-day with an address no map holds is skipped as LOCATION_UNRESOLVED; the rest is still timed.",
    run: async context => {
      const date = addDays(context.today, 2);
      const id = ids(context, "skip");
      const snapshot = builder(context).day(id("located"), date, home(context)).day(id("unlocatable"), date, UNLOCATABLE)
        .visit(id("visit"), id("located"), date, visitPoint(context), 0, 540).build();
      const evaluation = expectBody(await context.api.call("POST", "/api/v1/routes/evaluate", { serviceDate: date, snapshot }, { timeoutMs: 30_000 }),
        200, Api.RouteEvaluation, "Route evaluation with an unlocatable day");
      const skipped = evaluation.skippedTechnicianDays.map(day => `${day.technicianId} ${day.reason}`);
      check(isDeepStrictEqual(skipped, [`${id("unlocatable")} LOCATION_UNRESOLVED`]), `Skipped days are [${skipped.join(", ")}]`);
      check(evaluation.routes.some(route => route.technicianId === id("located")), "The located technician-day was not timed");
    },
  },
  {
    group: "Booking", name: "offers, select and release", proves:
      "Offers honour offerLimit and windowHours and hold for 10 minutes; a requestId replays its answer; select keeps one hold; release ends them.",
    run: async context => {
      const date = addDays(context.today, 3);
      const day = bookingDay(context, "offers", date);
      const body = offersBody(context, day.snapshot, day.jobId, date, date);
      const { set, receivedAt } = await offers(context, body, "Booking offers");
      const offer = firstOffer(set, "Booking offers");
      check(set.offers.length <= body.offerLimit, `Booking offers: ${set.offers.length} offers above offerLimit ${body.offerLimit}`);
      check(new Set(set.offers.map(item => item.offerId)).size === set.offers.length, "Booking offers: offer IDs repeat");
      for (const item of set.offers) {
        check(item.serviceDate === date, `Booking offers: offer ${item.offerId} is on ${item.serviceDate}, outside the horizon`);
        check(minutes(item.window.start, item.window.end) === body.windowHours * 60, `Booking offers: offer ${item.offerId} is not ${body.windowHours} hours long`);
        const start = wallClock(item.window.start, context.timeZone);
        check(start.minute % 60 === 0 && start.second === 0, `Booking offers: offer ${item.offerId} does not start on the hour in ${context.timeZone}`);
      }
      const lifetime = (Date.parse(set.expiresAt) - receivedAt) / 60_000;
      check(lifetime > 8 && lifetime <= 10.5, `Booking offers: the holds expire ${lifetime.toFixed(1)} minutes out, expected about 10 (check this host's clock)`);
      check(set.skippedTechnicianDays.length === 0, "Booking offers: a located technician-day was skipped");

      const replay = await context.api.call("POST", "/api/v1/booking/offers", body, { timeoutMs: BOOKING_TIMEOUT_MS });
      check(isDeepStrictEqual(expectBody(replay, 201, Api.OfferSet, "Booking offers replay"), set), "Booking offers replay: the same requestId answered differently");
      expectProblem(await context.api.call("POST", "/api/v1/booking/offers", { ...body, offerLimit: 1 }, { timeoutMs: BOOKING_TIMEOUT_MS }), 400, "INVALID_REQUEST",
        "Booking offers reusing a requestId for a different body");

      const path = (offerId: string, action: "select" | "release") => `/api/v1/booking/offers/${encodeURIComponent(offerId)}/${action}`;
      const hold = expectBody(await context.api.call("POST", path(offer.offerId, "select"), requestOnly()), 200, Api.Hold, "Select");
      check(hold.offerId === offer.offerId, "Select: the hold names another offer");
      check(sameInstant(hold.expiresAt, set.expiresAt), "Select: the hold's expiry moved; it keeps the offer set's");
      const again = expectBody(await context.api.call("POST", path(offer.offerId, "select"), requestOnly()), 200, Api.Hold, "Select again");
      check(isDeepStrictEqual(again, hold), "Select again: selecting the selected offer did not return its hold");
      const other = set.offers[1];
      if (other !== undefined)
        expectProblem(await context.api.call("POST", path(other.offerId, "select"), requestOnly()), 409, "HOLD_UNAVAILABLE", "Selecting a second offer of the set");
      expectBody(await context.api.call("POST", path(offer.offerId, "release"), requestOnly()), 200, Api.Released, "Release");
      expectBody(await context.api.call("POST", path(offer.offerId, "release"), requestOnly()), 200, Api.Released, "Release again");
      expectProblem(await context.api.call("POST", path(offer.offerId, "select"), requestOnly()), 409, "HOLD_UNAVAILABLE", "Select after release");
      expectProblem(await context.api.call("POST", path(`${context.run}-no-such-offer`, "select"), requestOnly()), 404, "NOT_FOUND", "Select an unknown offer");
    },
  },
  {
    group: "Booking", name: "new search ends earlier offers", proves: "Searching again for the same job ends its earlier offers.",
    run: async context => {
      const date = addDays(context.today, 4);
      const day = bookingDay(context, "research", date);
      const earlier = firstOffer((await offers(context, offersBody(context, day.snapshot, day.jobId, date, date), "First search")).set, "First search");
      const later = firstOffer((await offers(context, offersBody(context, day.snapshot, day.jobId, date, date), "Second search")).set, "Second search");
      expectProblem(await context.api.call("POST", `/api/v1/booking/offers/${encodeURIComponent(earlier.offerId)}/select`, requestOnly()), 409, "HOLD_UNAVAILABLE",
        "Selecting an offer of the earlier search");
      expectBody(await context.api.call("POST", `/api/v1/booking/offers/${encodeURIComponent(later.offerId)}/release`, requestOnly()), 200, Api.Released, "Release");
    },
  },
  {
    group: "Booking", name: "confirm", proves: "A selected hold confirms against a fresh snapshot of its date into the job's appointment; a confirm replays its receipt.",
    run: async context => {
      const date = addDays(context.today, 5);
      const day = bookingDay(context, "confirm", date);
      const offer = firstOffer((await offers(context, offersBody(context, day.snapshot, day.jobId, date, date), "Booking offers")).set, "Booking offers");
      const hold = expectBody(await context.api.call("POST", `/api/v1/booking/offers/${encodeURIComponent(offer.offerId)}/select`, requestOnly()), 200, Api.Hold, "Select");
      const confirmPath = `/api/v1/booking/holds/${encodeURIComponent(hold.holdId)}/confirm`;
      const body = { requestId: randomUUID(), snapshot: day.snapshot };
      const receipt = expectBody(await context.api.call("POST", confirmPath, body, { timeoutMs: BOOKING_TIMEOUT_MS }), 200, Api.CommitReceipt, "Confirm");
      const job = receipt.assignments.find(assignment => assignment.appointmentId === day.jobId);
      check(job !== undefined, "Confirm: the receipt has no assignment under the job's ID");
      check(job.technicianId === day.technician && job.serviceDate === date, "Confirm: the job is assigned outside the snapshot");
      check(Date.parse(job.plannedStart) >= Date.parse(offer.window.start) && Date.parse(job.plannedStart) <= Date.parse(offer.window.end),
        "Confirm: the job's planned arrival is outside the offered window");
      const version = receipt.technicianDays.find(item => item.technicianId === day.technician && item.serviceDate === date);
      check(version !== undefined && sameInstant(version.lastModified, context.lastModified), "Confirm: the receipt does not carry the technician-day's lastModified");
      check(receipt.assignments.every(item => item.technicianId === day.technician && item.serviceDate === date), "Confirm: an assignment is outside the snapshot");
      const replay = expectBody(await context.api.call("POST", confirmPath, body, { timeoutMs: BOOKING_TIMEOUT_MS }), 200, Api.CommitReceipt, "Confirm replay");
      check(isDeepStrictEqual(replay, receipt), "Confirm replay: the same requestId answered differently");
      const again = expectBody(await context.api.call("POST", confirmPath, { ...body, requestId: randomUUID() }, { timeoutMs: BOOKING_TIMEOUT_MS }),
        200, Api.CommitReceipt, "Confirm again");
      check(isDeepStrictEqual(again, receipt), "Confirm again: confirming a confirmed hold did not return the same receipt");
      expectProblem(await context.api.call("POST", `/api/v1/booking/holds/${encodeURIComponent(`${context.run}-no-such-hold`)}/confirm`,
        { ...body, requestId: randomUUID() }, { timeoutMs: BOOKING_TIMEOUT_MS }), 404, "NOT_FOUND", "Confirm an unknown hold");
    },
  },
  {
    group: "Booking", name: "late older snapshot is stale", proves:
      "A snapshot older than one the scheduler already used for a technician-day is refused as STALE, listing the newest timestamp seen; it never loses other holds.",
    run: async context => {
      const date = addDays(context.today, 6);
      const day = bookingDay(context, "stale", date);
      const id = ids(context, "stale");
      const newer = new Date(Date.parse(context.lastModified) + 60_000).toISOString();
      const current: Snapshot = { ...day.snapshot, technicianDays: day.snapshot.technicianDays.map(item => ({ ...item, lastModified: newer })) };
      const held = firstOffer((await offers(context, offersBody(context, day.snapshot, id("job-1"), date, date), "Search on the first records")).set, "Search on the first records");
      const later = firstOffer((await offers(context, offersBody(context, current, id("job-2"), date, date), "Search on newer records")).set, "Search on newer records");
      const late = offersBody(context, day.snapshot, id("job-3"), date, date);
      const stale = expectBody(await context.api.call("POST", "/api/v1/booking/offers", late, { timeoutMs: BOOKING_TIMEOUT_MS }), 409, Api.StaleProblem,
        "Search on the first records after newer ones were used");
      const listed = stale.changed.find(item => item.technicianId === day.technician && item.serviceDate === date);
      check(listed !== undefined && sameInstant(listed.lastModified, newer), `Stale search: changed does not list ${day.technician} at ${newer}`);
      const replay = expectBody(await context.api.call("POST", "/api/v1/booking/offers", late, { timeoutMs: BOOKING_TIMEOUT_MS }), 409, Api.StaleProblem, "Stale search replay");
      check(isDeepStrictEqual(replay, stale), "Stale search replay: the same requestId answered differently");
      for (const offer of [held, later]) {
        expectBody(await context.api.call("POST", `/api/v1/booking/offers/${encodeURIComponent(offer.offerId)}/select`, requestOnly()), 200, Api.Hold,
          "Selecting an offer held before the stale search");
        expectBody(await context.api.call("POST", `/api/v1/booking/offers/${encodeURIComponent(offer.offerId)}/release`, requestOnly()), 200, Api.Released, "Release");
      }
    },
  },
  {
    group: "Booking", name: "client refinement budget", proves:
      "A client's optional bookingRefinementMs is honoured from 0 (the optional pass is skipped) to 3000, and a larger one is refused.",
    run: async context => {
      const date = addDays(context.today, 5);
      const day = bookingDay(context, "budget", date);
      const snapshot = { ...day.snapshot, policy: { ...day.snapshot.policy, bookingRefinementMs: 0 } };
      const { set } = await offers(context, offersBody(context, snapshot, day.jobId, date, date), "Booking offers with no optional refinement");
      check(set.refinement !== "SHORTENED" && set.refinement !== "SHED",
        `Booking offers with no optional refinement: refinement ${set.refinement ?? "absent"}; an optional pass must be skipped, a required one run`);
      const offer = set.offers[0];
      if (offer !== undefined)
        expectBody(await context.api.call("POST", `/api/v1/booking/offers/${encodeURIComponent(offer.offerId)}/release`, requestOnly()), 200, Api.Released, "Release");
      const longest = { ...day.snapshot, policy: { ...day.snapshot.policy, bookingRefinementMs: 3000 } };
      const { set: capped } = await offers(context, offersBody(context, longest, day.jobId, date, date), "Booking offers with a 3000 ms refinement budget");
      check(capped.refinementStop === undefined || capped.refinement === "SHORTENED",
        `Booking offers with a 3000 ms refinement budget: refinementStop ${capped.refinementStop} on a ${capped.refinement} refinement`);
      check(capped.regularWindowsFound === undefined || capped.regularWindowsFound >= 0, "Booking offers with a 3000 ms refinement budget: regularWindowsFound is a count");
      const cappedOffer = capped.offers[0];
      if (cappedOffer !== undefined)
        expectBody(await context.api.call("POST", `/api/v1/booking/offers/${encodeURIComponent(cappedOffer.offerId)}/release`, requestOnly()), 200, Api.Released, "Release");
      const tooLong = { ...day.snapshot, policy: { ...day.snapshot.policy, bookingRefinementMs: 3001 } };
      expectProblem(await context.api.call("POST", "/api/v1/booking/offers", offersBody(context, tooLong, day.jobId, date, date),
        { timeoutMs: BOOKING_TIMEOUT_MS }), 400, "INVALID_REQUEST", "Booking offers with a 3001 ms refinement budget");
    },
  },
  {
    group: "Booking", name: "horizon of 22 dates", proves: "A booking horizon covers at most 21 dates.",
    run: async context => {
      const first = addDays(context.today, 2);
      const day = bookingDay(context, "horizon", first);
      expectProblem(await context.api.call("POST", "/api/v1/booking/offers", offersBody(context, day.snapshot, day.jobId, first, addDays(first, 21)),
        { timeoutMs: BOOKING_TIMEOUT_MS }), 400, "INVALID_REQUEST", "Booking offers over 22 dates");
    },
  },
  {
    group: "Booking", name: "job ID equal to an appointment ID", proves: "job.id must differ from every appointment and technician ID in the snapshot.",
    run: async context => {
      const date = addDays(context.today, 2);
      const day = bookingDay(context, "clash", date);
      expectProblem(await context.api.call("POST", "/api/v1/booking/offers", offersBody(context, day.snapshot, day.visit, date, date),
        { timeoutMs: BOOKING_TIMEOUT_MS }), 400, "INVALID_REQUEST", "Booking offers whose job ID is an appointment's");
    },
  },
  {
    group: "Booking", name: "frozen date", proves: "A horizon date past its 6 a.m. cutoff is refused as INCOMPLETE_FACTS.",
    run: async context => {
      const yesterday = addDays(context.today, -1);
      const day = bookingDay(context, "frozen-booking", yesterday);
      expectProblem(await context.api.call("POST", "/api/v1/booking/offers", offersBody(context, day.snapshot, day.jobId, yesterday, yesterday),
        { timeoutMs: BOOKING_TIMEOUT_MS }), 422, "INCOMPLETE_FACTS", "Booking offers for yesterday");
    },
  },
  {
    group: "Daily", name: "propose and commit", proves:
      "An improvable day is proposed as IMPROVED and committed once; a requestId replays its answer; a proposal cannot be committed twice.",
    run: async context => {
      const date = addDays(context.today, 2);
      const day = sharedVisit(context, "daily", date);
      const body = { requestId: randomUUID(), serviceDate: date, snapshot: day.snapshot };
      const proposal = await propose(context, body, "Daily proposal");
      check(proposal.decision === "IMPROVED", `Daily proposal: one technician making both visits was not proposed (${proposal.decision}: ${proposal.reason})`);
      checkRoutesCover(proposal, day.visits, "Daily proposal");
      check(proposal.skippedTechnicianDays.length === 0 && proposal.unresolvedAppointmentIds.length === 0, "Daily proposal: something was skipped or unresolved");
      const replay = await propose(context, body, "Daily proposal replay");
      check(isDeepStrictEqual(replay, proposal), "Daily proposal replay: the same requestId answered differently");

      const commitPath = `/api/v1/daily/proposals/${encodeURIComponent(proposal.proposalId)}/commit`;
      const commit = { requestId: randomUUID(), technicianDays: versions(day.snapshot) };
      const receipt = expectBody(await context.api.call("POST", commitPath, commit), 200, Api.CommitReceipt, "Commit");
      const assigned = receipt.assignments.map(item => item.appointmentId).sort();
      check(isDeepStrictEqual(assigned, [...day.visits].sort()), `Commit: assignments ${assigned.join(", ")} do not cover the day`);
      for (const route of proposal.routes)
        for (const stop of route.stops) {
          const written = receipt.assignments.find(item => item.appointmentId === stop.appointmentId);
          check(written !== undefined && written.technicianId === route.technicianId && written.sequence === stop.sequence && sameInstant(written.plannedStart, stop.plannedStart),
            `Commit: ${stop.appointmentId} is not assigned as proposed`);
        }
      check(receipt.technicianDays.length === day.snapshot.technicianDays.length && receipt.technicianDays.every(item => sameInstant(item.lastModified, context.lastModified)),
        "Commit: the receipt's technician-day timestamps are not the ones sent");
      const replayed = expectBody(await context.api.call("POST", commitPath, commit), 200, Api.CommitReceipt, "Commit replay");
      check(isDeepStrictEqual(replayed, receipt), "Commit replay: the same requestId answered differently");
      expectProblem(await context.api.call("POST", commitPath, { ...commit, requestId: randomUUID() }), 409, "NOT_COMMITTABLE", "A second commit");
    },
  },
  {
    group: "Daily", name: "unimproved limit", proves:
      "A policy's optional unimprovedSearchSeconds ends a settled day's search early (spec 1.6.0), with TABU_KOPT_SIZE_3, and 0 or more than the cost search time is refused.",
    run: async context => {
      const date = addDays(context.today, 2);
      const day = sharedVisit(context, "unimproved", date);
      const policy = { ...day.snapshot.policy, dailySearch: "TABU_KOPT_SIZE_3" as const, costSearchSeconds: 30, fairnessSearchSeconds: 0, unimprovedSearchSeconds: 2 };
      const started = Date.now();
      const proposal = await propose(context, { requestId: randomUUID(), serviceDate: date, snapshot: { ...day.snapshot, policy } },
        "Daily proposal with a 2 second unimproved limit");
      const elapsed = Date.now() - started;
      check(proposal.decision === "IMPROVED", `Daily proposal with an unimproved limit: ${proposal.decision}: ${proposal.reason}`);
      checkRoutesCover(proposal, day.visits, "Daily proposal with an unimproved limit");
      check(elapsed < 20_000, `Daily proposal with a 2 second unimproved limit on a two-visit day took ${elapsed} ms of its 30 second search`);
      for (const [unimprovedSearchSeconds, label] of [[0, "0"], [31, "more than the cost search time"]] as const)
        expectProblem(await context.api.call("POST", "/api/v1/daily/proposals",
          { requestId: randomUUID(), serviceDate: date, snapshot: { ...day.snapshot, policy: { ...policy, unimprovedSearchSeconds } } },
          { timeoutMs: PROPOSAL_TIMEOUT_MS }), 400, "INVALID_REQUEST", `A daily proposal with an unimproved limit of ${label}`);
    },
  },
  {
    group: "Daily", name: "stale and incomplete commits", proves:
      "A commit missing a technician-day is a 400; one whose lastModified changed is 409 STALE listing the change, and nothing is committed.",
    run: async context => {
      const date = addDays(context.today, 3);
      const day = sharedVisit(context, "stale", date);
      const proposal = await propose(context, { requestId: randomUUID(), serviceDate: date, snapshot: day.snapshot }, "Daily proposal");
      check(proposal.decision === "IMPROVED", `Daily proposal: expected IMPROVED, got ${proposal.decision}: ${proposal.reason}`);
      const commitPath = `/api/v1/daily/proposals/${encodeURIComponent(proposal.proposalId)}/commit`;
      const current = versions(day.snapshot);
      expectProblem(await context.api.call("POST", commitPath, { requestId: randomUUID(), technicianDays: current.slice(1) }), 400, "INVALID_REQUEST",
        "A commit missing a technician-day");
      const moved = new Date(Date.parse(context.lastModified) + 1000).toISOString();
      const changed = current.map(item => item.technicianId === day.b ? { ...item, lastModified: moved } : item);
      const listed = expectBody(await context.api.call("POST", commitPath, { requestId: randomUUID(), technicianDays: changed }), 409, Api.StaleProblem,
        "A stale commit").changed;
      check(listed.length === 1 && listed[0]?.technicianId === day.b && sameInstant(listed[0].lastModified, context.lastModified),
        "A stale commit: changed does not list the moved technician-day with the recorded timestamp");
      expectProblem(await context.api.call("POST", "/api/v1/daily/proposals/" + encodeURIComponent(`${context.run}-no-such-proposal`) + "/commit",
        { requestId: randomUUID(), technicianDays: current }), 404, "NOT_FOUND", "A commit of an unknown proposal");
    },
  },
  {
    group: "Daily", name: "queued proposal", proves:
      "With Prefer: respond-async a proposal is queued (202 with Location), its status is polled to the same answer the call would give, and the requestId then replays it.",
    run: async context => {
      const date = addDays(context.today, 6);
      const day = sharedVisit(context, "queued", date);
      const body = { requestId: randomUUID(), serviceDate: date, snapshot: day.snapshot };
      const accepted = await context.api.call("POST", "/api/v1/daily/proposals", body, { headers: RESPOND_ASYNC });
      check(accepted.ms < 5_000, `The queued proposal took ${accepted.ms} ms to be accepted`);
      expectQueued(accepted, body.requestId, "Queueing a daily proposal");
      const again = await context.api.call("POST", "/api/v1/daily/proposals", body, { headers: RESPOND_ASYNC });
      if (again.status === 202) expectQueued(again, body.requestId, "Queueing the same request again");
      else expectBody(again, 201, Api.DailyProposal, "Queueing the same request again after it finished");
      const done = await awaitCompleted(context, body.requestId, "Polling the queued proposal");
      check(done.operation === "DAILY_PROPOSAL" && done.queuedAt !== undefined, "Polling the queued proposal: operation or queuedAt missing");
      const result = done.result ?? fail("Polling the queued proposal: no result");
      check(result.status === 201, `Polling the queued proposal: expected 201, got ${result.status}: ${JSON.stringify(result.body)}`);
      const proposal = Api.DailyProposal.parse(result.body);
      check(proposal.decision === "IMPROVED", `The queued proposal: expected IMPROVED, got ${proposal.decision}: ${proposal.reason}`);
      checkRoutesCover(proposal, day.visits, "The queued proposal");
      const replay = await propose(context, body, "The same requestId sent without respond-async");
      check(isDeepStrictEqual(replay, proposal), "The same requestId sent without respond-async answered differently");
      expectProblem(await context.api.call("GET", `/api/v1/requests/${randomUUID()}`), 404, "NOT_FOUND", "The status of an unknown requestId");
    },
  },
  {
    group: "Daily", name: "frozen date", proves: "A daily proposal for a date past its 6 a.m. cutoff is refused as INCOMPLETE_FACTS.",
    run: async context => {
      const yesterday = addDays(context.today, -1);
      const reply = await context.api.call("POST", "/api/v1/daily/proposals",
        { requestId: randomUUID(), serviceDate: yesterday, snapshot: sharedVisit(context, "frozen-daily", yesterday).snapshot }, { timeoutMs: PROPOSAL_TIMEOUT_MS });
      expectProblem(reply, 422, "INCOMPLETE_FACTS", "A daily proposal for yesterday");
    },
  },
  {
    group: "Repair", name: "absence repair", proves: "A day-long absence is covered by moving the absent technician's visit to another technician, without overtime.",
    run: async context => {
      const date = addDays(context.today, 4);
      const day = sharedVisit(context, "repair", date);
      const absence = { technicianId: day.a, serviceDate: date, window: { start: day.snapshot.technicianDays[0]?.shift.start ?? fail("No shift"),
        end: day.snapshot.technicianDays[0]?.shift.end ?? fail("No shift") } };
      const reply = await context.api.call("POST", "/api/v1/repairs/proposals", { requestId: randomUUID(), absence, snapshot: day.snapshot },
        { timeoutMs: PROPOSAL_TIMEOUT_MS });
      const proposal = expectBody(reply, 201, Api.DailyProposal, "Repair proposal");
      check(proposal.decision === "IMPROVED", `Repair proposal: expected IMPROVED, got ${proposal.decision}: ${proposal.reason}`);
      checkRoutesCover(proposal, day.visits, "Repair proposal");
      check(proposal.routes.every(route => route.technicianId !== day.a || route.stops.length === 0), "Repair proposal: the absent technician still has a visit");
    },
  },
  {
    group: "Cancellation", name: "unknown request", proves: "Cancelling a requestId that is not running is a 404, and a malformed one a 400.",
    run: async context => {
      expectProblem(await context.api.call("DELETE", `/api/v1/requests/${randomUUID()}`), 404, "NOT_FOUND", "Cancelling an unknown requestId");
      expectProblem(await context.api.call("DELETE", "/api/v1/requests/not-a-uuid"), 400, "INVALID_REQUEST", "Cancelling a malformed requestId");
    },
  },
  {
    group: "Cancellation", name: "cancel a running proposal", proves:
      "A running daily proposal is cancelled with 202, answers 409 CANCELLED, and its requestId runs again on retry.",
    run: async context => {
      const date = addDays(context.today, 5);
      const body = { requestId: randomUUID(), serviceDate: date, snapshot: sharedVisit(context, "cancel", date).snapshot };
      const running = context.api.call("POST", "/api/v1/daily/proposals", body, { timeoutMs: PROPOSAL_TIMEOUT_MS });
      let settled = false;
      void running.then(() => { settled = true; }, () => { settled = true; });
      let accepted: Reply | undefined;
      while (!settled && accepted === undefined) {
        const reply = await context.api.call("DELETE", `/api/v1/requests/${body.requestId}`);
        if (reply.status === 202) accepted = reply;
        else if (reply.status !== 404) fail(`Cancelling the running proposal: expected HTTP 202 or 404 before it starts, got ${describe(reply)}`);
        else await new Promise(resolve => setTimeout(resolve, 50));
      }
      check(accepted !== undefined, `The proposal finished before it could be cancelled: ${describe(await running)}`);
      check(accepted.text === "", "Cancelling: the 202 has a body");
      expectProblem(await running, 409, "CANCELLED", "The cancelled proposal");
      expectProblem(await context.api.call("DELETE", `/api/v1/requests/${body.requestId}`), 404, "NOT_FOUND", "Cancelling a finished request");
      const retried = await propose(context, body, "Retrying the cancelled requestId");
      check(retried.decision === "IMPROVED", `Retrying the cancelled requestId: expected IMPROVED, got ${retried.decision}`);
    },
  },
  {
    group: "Cancellation", name: "cancel a queued proposal", proves:
      "A queued daily proposal is cancelled with 202 from any replica, its status reports 409 CANCELLED, and the same requestId queued again runs.",
    run: async context => {
      const date = addDays(context.today, 7);
      const body = { requestId: randomUUID(), serviceDate: date, snapshot: sharedVisit(context, "cancel-queued", date).snapshot };
      expectQueued(await context.api.call("POST", "/api/v1/daily/proposals", body, { headers: RESPOND_ASYNC }), body.requestId, "Queueing a daily proposal");
      const cancel = await context.api.call("DELETE", `/api/v1/requests/${body.requestId}`);
      check(cancel.status === 202 && cancel.text === "", `Cancelling the queued proposal: expected HTTP 202 without a body, got ${describe(cancel)}`);
      const cancelled = await awaitCompleted(context, body.requestId, "Polling the cancelled proposal");
      const result = cancelled.result ?? fail("Polling the cancelled proposal: no result");
      check(result.status === 409, `The cancelled proposal: expected 409, got ${result.status}: ${JSON.stringify(result.body)}`);
      const problem = Api.Problem.parse(result.body);
      check(problem.error === "CANCELLED", `The cancelled proposal: expected CANCELLED, got ${problem.error}`);
      expectQueued(await context.api.call("POST", "/api/v1/daily/proposals", body, { headers: RESPOND_ASYNC }), body.requestId,
        "Queueing the cancelled requestId again");
      const done = await awaitCompleted(context, body.requestId, "Polling the requestId queued again");
      check(done.result?.status === 201, `The requestId queued again: expected 201, got ${JSON.stringify(done.result)}`);
    },
  },
];
