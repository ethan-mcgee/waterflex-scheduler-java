// Drive-time load: dispatchers asking for a day's drawn routes (POST /api/v1/routes/geometry) and for where a job fits
// (POST /api/v1/routes/insertions), as a dispatch screen does around a drag and drop. A "session" is the sequence the
// integration guide prescribes: open the day (geometry), pick a job (insertions, with timed stops), drop it where the
// first fitting start says (geometry of the day with the job placed). Calls are made once and never retried: a 429 or
// 503 is an outcome to count, since a dispatcher waiting on Retry-After is the latency being measured.
//
// Callers run closed-loop (the next move starts when the last answered) or paced: each caller starts a move every
// paceMs on a fixed clock whether or not the last one answered, so a slow server shows as queueing and lateness instead
// of as fewer calls (open-loop). A caller with maxOutstanding moves still open skips the tick and counts it.
import { randomUUID } from "node:crypto";
import type { z } from "zod";
import * as Api from "../api.ts";
import type { Reply, SchedulerApi } from "../client.ts";
import type { Point } from "../points.ts";
import type { Appointment, Snapshot } from "../snapshot.ts";
import { HostStore, type Job, type MetroShape } from "./host.ts";
import type { CallRecord } from "./stats.ts";

type RouteInsertionResult = z.infer<typeof Api.RouteInsertionResult>;

export const ROUTE_MODES = ["geometry", "insertions", "session"] as const;
export type RouteMode = typeof ROUTE_MODES[number];

/**
 * Where each insertions call's legs come from: `fresh-day` builds a new day and job every call (nothing cached),
 * `fresh-job` keeps the day and moves the job (a dispatcher picking another job), `repeat` sends the same day and job
 * (every leg cached).
 */
export const PLACEMENTS = ["fresh-day", "fresh-job", "repeat"] as const;
export type Placement = typeof PLACEMENTS[number];

export interface RouteDay { readonly serviceDate: string; readonly snapshot: Snapshot }

const DATE = /^\d{4}-\d{2}-\d{2}$/;

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function located(value: unknown): value is Point {
  return isRecord(value) && typeof value.lat === "number" && typeof value.lng === "number" && Number.isFinite(value.lat) && Number.isFinite(value.lng);
}

/**
 * A recorded geometry request ({serviceDate, snapshot}), checked for what this tool relies on: one service date across
 * the day, located appointments with IDs, and at least one technician-day. The scheduler validates everything else.
 */
export function parseRouteDay(json: unknown, source: string): RouteDay {
  const fail = (why: string): never => { throw new Error(`${source}: ${why}`); };
  if (!isRecord(json)) return fail("not a JSON object");
  const serviceDate = json.serviceDate;
  if (typeof serviceDate !== "string" || !DATE.test(serviceDate)) return fail("serviceDate is not a calendar date");
  const snapshot = json.snapshot;
  if (!isRecord(snapshot)) return fail("no snapshot object");
  const days = snapshot.technicianDays, appointments = snapshot.appointments, technicians = snapshot.technicians;
  if (!Array.isArray(days) || days.length === 0) return fail("snapshot.technicianDays is empty or missing");
  if (!Array.isArray(appointments)) return fail("snapshot.appointments is missing");
  if (!Array.isArray(technicians) || technicians.length === 0) return fail("snapshot.technicians is empty or missing");
  for (const day of days) if (!isRecord(day) || day.serviceDate !== serviceDate) return fail("a technician-day is not on serviceDate");
  for (const item of appointments) {
    if (!isRecord(item) || typeof item.id !== "string" || typeof item.serviceId !== "string" || !located(item.location) || item.serviceDate !== serviceDate)
      return fail("an appointment has no ID, service, coordinates or is not on serviceDate");
  }
  if (typeof snapshot.metroId !== "string" || typeof snapshot.timeZone !== "string") return fail("snapshot has no metroId or timeZone");
  // Checked above field by field; the scheduler checks the rest of the shape and answers 400 for anything else.
  return { serviceDate, snapshot: snapshot as unknown as Snapshot };
}

/** The south, west, north and east edges of every located appointment and technician home of a day. */
export function bounds(day: RouteDay): { south: number; west: number; north: number; east: number } {
  const points: Point[] = [...day.snapshot.appointments.map(item => item.location), ...day.snapshot.technicianDays.flatMap(item => [item.start, item.end])].filter(located);
  const first = points[0];
  if (first === undefined) throw new Error("The day has no located point");
  return points.reduce((box, point) => ({ south: Math.min(box.south, point.lat), west: Math.min(box.west, point.lng),
    north: Math.max(box.north, point.lat), east: Math.max(box.east, point.lng) }), { south: first.lat, west: first.lng, north: first.lat, east: first.lng });
}

/** The days and jobs a run sends: one recorded day, or days generated in an area with a fixed shape. */
export class DaySource {
  readonly #point: () => Point;
  readonly #make: (() => RouteDay) | null;
  readonly #fixedDay: RouteDay;
  readonly #fixedJob: Job;
  readonly #serviceId: string;
  #jobs = 0;

  /** A recorded day: every call sends it (or it with a fresh job). `fresh-day` is refused, since there is one day. */
  static recorded(day: RouteDay, point: () => Point): DaySource {
    const serviceId = day.snapshot.appointments[0]?.serviceId;
    if (serviceId === undefined) throw new Error("The recorded day has no appointment, so no service for the job");
    return new DaySource(point, null, day, serviceId);
  }

  /** Days of `shape` on its one date, each from new points when `fresh-day` asks for one. */
  static generated(shape: MetroShape, point: () => Point): DaySource {
    const date = shape.dates[0];
    if (date === undefined || shape.dates.length !== 1) throw new Error("A generated drive-time day has exactly one date");
    let made = 0;
    const make = (): RouteDay => {
      made++;
      const store = new HostStore({ ...shape, prefix: `${shape.prefix}-d${made}` }, point, point);
      return { serviceDate: date, snapshot: store.snapshot([date]) };
    };
    return new DaySource(point, make, make(), shape.serviceId);
  }

  private constructor(point: () => Point, make: (() => RouteDay) | null, day: RouteDay, serviceId: string) {
    this.#point = point;
    this.#make = make;
    this.#fixedDay = day;
    this.#serviceId = serviceId;
    this.#fixedJob = this.#newJob();
  }

  get recorded(): boolean { return this.#make === null; }

  #newJob(): Job {
    this.#jobs++;
    return { id: `drag-${this.#jobs}-${randomUUID().slice(0, 8)}`, serviceId: this.#serviceId, durationMinutes: 60, location: this.#point() };
  }

  day(placement: Placement): RouteDay {
    if (placement !== "fresh-day") return this.#fixedDay;
    if (this.#make === null) throw new Error("A recorded day cannot be sent fresh; use fresh-job or repeat");
    return this.#make();
  }

  job(placement: Placement): Job {
    return placement === "repeat" ? this.#fixedJob : this.#newJob();
  }
}

/** The insertions body for a job over a day: two-hour windows on the half hour, as the integration guide's example. */
export function insertionsBody(day: RouteDay, job: Job, routes: boolean): object {
  return { snapshot: day.snapshot, job: { id: job.id, serviceId: job.serviceId, durationMinutes: job.durationMinutes, location: job.location },
    windowMinutes: 120, startStepMinutes: 30, routes };
}

/**
 * The day after a dispatcher drops the job at the first start the insertions answer offered: that technician-day's
 * appointments take the start's timed stops (sequence and plannedStart), and the job joins it with the start's window.
 * Null when no start was offered with stops, so there is nowhere to drop.
 */
export function droppedDay(day: RouteDay, job: Job, result: RouteInsertionResult): RouteDay | null {
  for (const technicianDay of result.technicianDays) {
    for (const gap of technicianDay.gaps) {
      const start = gap.starts.find(item => item.stops !== undefined);
      const stops = start?.stops;
      if (start === undefined || stops === undefined) continue;
      const byId = new Map(stops.map(stop => [stop.appointmentId, stop]));
      if (!byId.has(job.id)) continue;
      const own = (item: Appointment) => item.technicianId === technicianDay.technicianId && item.serviceDate === technicianDay.serviceDate;
      const appointments: Appointment[] = [];
      for (const item of day.snapshot.appointments) {
        if (!own(item)) { appointments.push(item); continue; }
        const stop = byId.get(item.id);
        // A stop list that leaves out one of the day's appointments cannot be dropped as given.
        if (stop === undefined) return null;
        appointments.push({ ...item, sequence: stop.sequence, plannedStart: stop.plannedStart });
      }
      const placed = byId.get(job.id);
      if (placed === undefined) return null;
      appointments.push({ id: job.id, technicianId: technicianDay.technicianId, serviceDate: technicianDay.serviceDate, serviceId: job.serviceId,
        durationMinutes: job.durationMinutes, window: start.window, location: job.location, sequence: placed.sequence, plannedStart: placed.plannedStart });
      return { serviceDate: day.serviceDate, snapshot: { ...day.snapshot, appointments } };
    }
  }
  return null;
}

export interface RouteRecord extends CallRecord {
  /** Epoch milliseconds the call was sent, so the server's log lines of the same seconds can be matched to it. */
  readonly startedAt: number;
  /** Milliseconds to the answer's status and headers; null without an answer. */
  readonly headersMs: number | null;
  /** The answer's body length in bytes; null without an answer. */
  readonly bytes: number | null;
  /** How late a paced move started against its tick; null for closed-loop callers and for later steps of a session. */
  readonly lagMs: number | null;
  /** Retry-After seconds on a 429 or 503; null otherwise. */
  readonly retryAfterS: number | null;
}

export interface RouteRun {
  readonly api: SchedulerApi;
  /** Epoch milliseconds after which no move starts. */
  readonly endsAt: number;
  /** The run stops starting moves once this many were started in all; null runs for the duration only. */
  readonly maxMoves: number | null;
  readonly timeoutMs: number;
  readonly records: RouteRecord[];
  /** Answers that did not match the spec, counted by what was wrong: each is a defect, never a load effect. */
  readonly contractBreaches: Record<string, number>;
  /** Answers that matched the spec but not the day sent (a skipped or missing technician-day), counted by kind. */
  readonly anomalies: Record<string, number>;
  moves: number;
  /** Paced ticks skipped because the caller already had maxOutstanding moves open. */
  skippedTicks: number;
  /** Sessions whose insertions answer offered no start with stops, so nothing was dropped. */
  noPlacement: number;
}

export function newRouteRun(api: SchedulerApi, endsAt: number, maxMoves: number | null, timeoutMs: number): RouteRun {
  return { api, endsAt, maxMoves, timeoutMs, records: [], contractBreaches: {}, anomalies: {}, moves: 0, skippedTicks: 0, noPlacement: 0 };
}

const tally = (counts: Record<string, number>, key: string) => { counts[key] = (counts[key] ?? 0) + 1; };

function problemOf(reply: Reply): { error: string | null; message: string | null } {
  if (reply.status < 400) return { error: null, message: null };
  const parsed = Api.Problem.safeParse(reply.json);
  return parsed.success ? { error: parsed.data.error, message: parsed.data.message } : { error: `HTTP_${reply.status}`, message: null };
}

function retryAfter(reply: Reply): number | null {
  if (reply.status !== 429 && reply.status !== 503) return null;
  const header = reply.headers.get("retry-after");
  const seconds = header === null ? NaN : Number(header);
  return Number.isFinite(seconds) ? seconds : null;
}

/** One call, never retried, recorded with its timings. */
async function send(run: RouteRun, operation: string, path: string, body: object, lagMs: number | null): Promise<Reply | null> {
  const startedAt = Date.now();
  const started = performance.now();
  try {
    const reply = await run.api.call("POST", path, body, { timeoutMs: run.timeoutMs });
    run.records.push({ operation, status: reply.status, ...problemOf(reply), ms: reply.ms, attempts: 1, startedAt, headersMs: reply.headersMs,
      bytes: Buffer.byteLength(reply.text), lagMs, retryAfterS: retryAfter(reply) });
    return reply;
  } catch (error) {
    run.records.push({ operation, status: null, error: error instanceof Error ? error.name : "Error", message: null, ms: Math.round(performance.now() - started),
      attempts: 1, startedAt, headersMs: null, bytes: null, lagMs, retryAfterS: null });
    return null;
  }
}

function checkGeometry(run: RouteRun, reply: Reply, day: RouteDay, what: string): boolean {
  const parsed = Api.RouteGeometry.safeParse(reply.json);
  if (!parsed.success) {
    tally(run.contractBreaches, `${what}: ${parsed.error.issues.slice(0, 3).map(issue => `${issue.path.join(".")} ${issue.message}`).join("; ")}`);
    return false;
  }
  if (parsed.data.skippedTechnicianDays.length > 0) tally(run.anomalies, `${what}: technician-days skipped`);
  const withVisits = new Set(day.snapshot.appointments.map(item => item.technicianId));
  const drawn = new Set(parsed.data.routes.filter(route => route.legs.length > 0).map(route => route.technicianId));
  for (const technician of withVisits) if (!drawn.has(technician)) { tally(run.anomalies, `${what}: a technician with visits was not drawn`); break; }
  return true;
}

function checkInsertions(run: RouteRun, reply: Reply, what: string): RouteInsertionResult | null {
  const parsed = Api.RouteInsertionResult.safeParse(reply.json);
  if (!parsed.success) {
    tally(run.contractBreaches, `${what}: ${parsed.error.issues.slice(0, 3).map(issue => `${issue.path.join(".")} ${issue.message}`).join("; ")}`);
    return null;
  }
  if (parsed.data.skippedTechnicianDays.length > 0) tally(run.anomalies, `${what}: technician-days skipped`);
  return parsed.data;
}

export interface MoveSettings { readonly mode: RouteMode; readonly placement: Placement; readonly source: DaySource }

/** One dispatcher move in the run's mode. */
export async function move(run: RouteRun, settings: MoveSettings, lagMs: number | null): Promise<void> {
  const { mode, placement, source } = settings;
  if (mode === "geometry") {
    const day = source.day(placement);
    const reply = await send(run, "geometry", "/api/v1/routes/geometry", { serviceDate: day.serviceDate, snapshot: day.snapshot }, lagMs);
    if (reply?.status === 200) checkGeometry(run, reply, day, "Geometry");
    return;
  }
  if (mode === "insertions") {
    const day = source.day(placement);
    const reply = await send(run, "insertions", "/api/v1/routes/insertions", insertionsBody(day, source.job(placement), false), lagMs);
    if (reply?.status === 200) checkInsertions(run, reply, "Insertions");
    return;
  }
  // A session: open, pick, drop. The session's own record is the three steps' sum, with the first failing step's answer.
  // A pick that offers no start (no gap can take the job) is its own operation, so "session" is always a whole drag.
  const startedAt = Date.now();
  const started = performance.now();
  const finish = (status: number | null, error: string | null, operation = "session"): void => { run.records.push({ operation, status, error, message: null,
    ms: Math.round(performance.now() - started), attempts: 1, startedAt, headersMs: null, bytes: null, lagMs, retryAfterS: null }); };
  const day = source.day(placement);
  const open = await send(run, "session open (geometry)", "/api/v1/routes/geometry", { serviceDate: day.serviceDate, snapshot: day.snapshot }, null);
  if (open?.status !== 200 || !checkGeometry(run, open, day, "Session open")) return finish(open?.status ?? null, "OPEN_FAILED");
  const job = source.job(placement);
  const pick = await send(run, "session pick (insertions)", "/api/v1/routes/insertions", insertionsBody(day, job, true), null);
  const offered = pick?.status === 200 ? checkInsertions(run, pick, "Session pick") : null;
  if (offered === null) return finish(pick?.status ?? null, "PICK_FAILED");
  const dropped = droppedDay(day, job, offered);
  if (dropped === null) { run.noPlacement++; return finish(200, null, "session, nowhere to drop"); }
  const drop = await send(run, "session drop (geometry)", "/api/v1/routes/geometry", { serviceDate: dropped.serviceDate, snapshot: dropped.snapshot }, null);
  if (drop?.status !== 200 || !checkGeometry(run, drop, dropped, "Session drop")) return finish(drop?.status ?? null, "DROP_FAILED");
  finish(200, null);
}

function claim(run: RouteRun): boolean {
  if (Date.now() >= run.endsAt || (run.maxMoves !== null && run.moves >= run.maxMoves)) return false;
  run.moves++;
  return true;
}

/** A dispatcher who moves again as soon as the last move answered. */
export async function closedLoop(run: RouteRun, settings: MoveSettings): Promise<void> {
  while (claim(run)) await move(run, settings, null);
}

const sleep = (ms: number) => new Promise(resolve => setTimeout(resolve, Math.max(0, ms)));

/** A dispatcher who moves every paceMs from `firstAt` on a fixed clock, with at most `maxOutstanding` moves open. */
export async function pacedLoop(run: RouteRun, settings: MoveSettings, paceMs: number, firstAt: number, maxOutstanding: number): Promise<void> {
  const open = new Set<Promise<void>>();
  for (let tick = 0; ; tick++) {
    const due = firstAt + tick * paceMs;
    await sleep(due - Date.now());
    if (open.size >= maxOutstanding) {
      if (Date.now() >= run.endsAt) break;
      run.skippedTicks++;
      continue;
    }
    if (!claim(run)) break;
    const pending = move(run, settings, Date.now() - due).finally(() => open.delete(pending));
    open.add(pending);
  }
  await Promise.all(open);
}
