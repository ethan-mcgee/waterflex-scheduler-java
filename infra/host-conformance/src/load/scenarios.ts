// Load scenarios. A booking loop is one host booking desk: search, then either release the offers or select one,
// confirm it against fresh records and write the receipt with a compare-and-set, as docs/integration-guide.md says.
// A daily loop asks for a proposal of one metro day again and again. Loops run until the run's end time; a 429 BUSY is
// retried with the same requestId after Retry-After, as a host should, and its wait counts in the call's time. A 409
// STALE (the snapshot was older than records the scheduler already used) is sent again with current records and a
// new requestId, as the integration guide says. With --daily-mode async a daily loop sends Prefer: respond-async and
// polls GET /api/v1/requests/{requestId} as Retry-After asks, as a host using the queue would.
import { execFile } from "node:child_process";
import { randomUUID } from "node:crypto";
import { promisify } from "node:util";
import type { z } from "zod";
import * as Api from "../api.ts";
import type { Reply, SchedulerApi } from "../client.ts";
import type { Point } from "../points.ts";
import type { HostStore, Job } from "./host.ts";
import { scrape, type Sample } from "./metrics.ts";
import type { CallRecord } from "./stats.ts";

export interface BookingSettings {
  readonly store: HostStore;
  /** The dates of the next search: a fixed run, or chosen from the store's current records before each search. */
  readonly horizon: () => readonly string[];
  readonly offerLimit: number;
  readonly windowHours: number;
  readonly jobMinutes: number;
  /** Share of searches the customer books; the rest release their offers. */
  readonly confirmRate: number;
  readonly jobLocation: () => Point;
}

export interface DailySettings {
  readonly store: HostStore;
  readonly date: string;
  /** sync calculates within the call; async queues with Prefer: respond-async and polls the request's status. */
  readonly mode: "sync" | "async";
}

export interface BookingTally {
  searches: number; searchIncomplete: number; offers: number; noOffers: number; selected: number; holdUnavailable: number;
  confirmed: number; hostWriteConflicts: number; released: number; failedSearches: number;
  /** Searches and confirms answered 409 STALE and sent again with current records. */
  stale: number;
  /** Answered searches by how their refinement pass ended; `ABSENT` counts a replayed offer set from before spec 1.2.0. */
  refinements: Record<string, number>;
  /** SHORTENED refinements by what stopped them (CAP or DEADLINE, spec 1.5.0). */
  refinementStops: Record<string, number>;
  /** Answered searches by the first pass's distinct regular window count (the number the threshold is compared with), keyed by the count. */
  windowsFound: Record<string, number>;
  /** Searches by the number of dates their horizon had. */
  byHorizon: Record<string, HorizonTally>;
}

export interface HorizonTally {
  searches: number; failed: number; searchIncomplete: number;
  /** Dates named in incompleteDates, summed over answered searches; `ABSENT` offer sets add none. */
  incompleteDates: number;
  offers: number; noOffers: number; refinements: Record<string, number>; refinementStops: Record<string, number>;
  /** Caller milliseconds of each answered search, STALE resends included. */
  offersMs: number[];
}

export interface DailyTally {
  proposals: number;
  decisions: Record<string, number>;
  /** Queued proposals only: milliseconds from queuing to the start of the calculation, from the request's status. */
  queueWaitsMs: number[];
}

export interface Run {
  readonly api: SchedulerApi;
  readonly prefix: string;
  readonly serviceId: string;
  /** Epoch milliseconds after which no loop starts another sequence. */
  readonly endsAt: number;
  readonly random: () => number;
  readonly records: CallRecord[];
  readonly booking: BookingTally;
  readonly daily: DailyTally;
  /** Responses that did not match the spec: each is a defect to report, never a load effect. */
  readonly contractBreaches: string[];
}

export function newTallies(): { booking: BookingTally; daily: DailyTally } {
  return {
    booking: { searches: 0, searchIncomplete: 0, offers: 0, noOffers: 0, selected: 0, holdUnavailable: 0, confirmed: 0, hostWriteConflicts: 0,
      released: 0, failedSearches: 0, stale: 0, refinements: {}, refinementStops: {}, windowsFound: {}, byHorizon: {} },
    daily: { proposals: 0, decisions: {}, queueWaitsMs: [] },
  };
}

/** A small seeded generator (mulberry32), so a run's choices repeat with its seed. */
export function seeded(seed: number): () => number {
  let state = seed >>> 0;
  return () => {
    state = (state + 0x6d2b79f5) >>> 0;
    let t = state;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

const sleep = (ms: number) => new Promise(resolve => setTimeout(resolve, ms));

/** How long a host waits before repeating a 429: Retry-After when sent, else one second. */
export function retryAfterMs(reply: Reply): number {
  const header = reply.headers.get("retry-after");
  const seconds = header === null ? NaN : Number(header);
  // A daily proposal refused because the calculations ahead would not finish in time names their estimated wait, up to 300 s.
  return Number.isInteger(seconds) && seconds >= 1 && seconds <= 300 ? seconds * 1000 : 1000;
}

function problem(reply: Pick<Reply, "status" | "json">): { error: string | null; message: string | null } {
  if (reply.status < 400) return { error: null, message: null };
  const parsed = Api.Problem.safeParse(reply.json);
  if (parsed.success) return { error: parsed.data.error, message: parsed.data.message };
  const stale = Api.StaleProblem.safeParse(reply.json);
  return stale.success ? { error: stale.data.error, message: stale.data.message } : { error: `HTTP_${reply.status}`, message: null };
}

const isStale = (reply: Reply | null): boolean => reply?.status === 409 && Api.StaleProblem.safeParse(reply.json).success;

/**
 * Calls with a body built from the host's current records, and builds it again with a new requestId after each 409
 * STALE, until another answer or one minute past the run's end.
 */
async function current(run: Run, operation: string, path: string, body: () => object, timeoutMs: number): Promise<Reply | null> {
  for (;;) {
    const reply = await call(run, operation, "POST", path, body(), timeoutMs);
    if (!isStale(reply) || Date.now() >= run.endsAt + 60_000) return reply;
    run.booking.stale++;
  }
}

/** How many attempts each answered call took, BUSY retries included. */
const attemptsOf = new WeakMap<Reply, number>();

/** Calls once and repeats a 429 with the same body until another answer, or until one minute past the run's end. */
async function call(run: Run, operation: string, method: "POST" | "DELETE", path: string, body: object, timeoutMs: number,
                    headers?: Readonly<Record<string, string>>): Promise<Reply | null> {
  const started = performance.now();
  let attempts = 0;
  for (;;) {
    attempts++;
    let reply: Reply;
    try {
      reply = await run.api.call(method, path, body, { timeoutMs, ...(headers === undefined ? {} : { headers }) });
    } catch (error) {
      run.records.push({ operation, status: null, error: error instanceof Error ? error.name : "Error", message: null, ms: Math.round(performance.now() - started), attempts });
      return null;
    }
    if (reply.status === 429 && Date.now() < run.endsAt + 60_000) {
      await sleep(retryAfterMs(reply));
      continue;
    }
    run.records.push({ operation, status: reply.status, ...problem(reply), ms: Math.round(performance.now() - started), attempts });
    attemptsOf.set(reply, attempts);
    return reply;
  }
}

function parsed<T extends z.ZodType>(run: Run, reply: Reply, schema: T, what: string): z.infer<T> | null {
  const result = schema.safeParse(reply.json);
  if (!result.success) {
    run.contractBreaches.push(`${what}: ${result.error.issues.map(issue => `${issue.path.join(".")} ${issue.message}`).join("; ")}`);
    return null;
  }
  return result.data;
}

export async function bookingLoop(run: Run, settings: BookingSettings, desk: number, deadlineMs: number): Promise<void> {
  const timeoutMs = deadlineMs + 10_000;
  for (let n = 1; Date.now() < run.endsAt; n++) {
    const job: Job = { id: `${run.prefix}-job-${desk}-${n}`, serviceId: run.serviceId, durationMinutes: settings.jobMinutes, location: settings.jobLocation() };
    const dates = settings.horizon();
    const first = dates[0], last = dates[dates.length - 1];
    if (first === undefined || last === undefined) throw new Error("Empty booking horizon");
    run.booking.searches++;
    const bucket = run.booking.byHorizon[String(dates.length)] ??= { searches: 0, failed: 0, searchIncomplete: 0, incompleteDates: 0, offers: 0, noOffers: 0, refinements: {}, refinementStops: {}, offersMs: [] };
    bucket.searches++;
    const started = performance.now();
    const reply = await current(run, "booking offers", "/api/v1/booking/offers", () => ({ requestId: randomUUID(), job,
      horizon: { firstDate: first, lastDate: last }, offerLimit: settings.offerLimit, windowHours: settings.windowHours,
      snapshot: settings.store.snapshot(dates) }), timeoutMs);
    if (reply === null || reply.status !== 201) {
      run.booking.failedSearches++;
      bucket.failed++;
      await sleep(200);
      continue;
    }
    const set = parsed(run, reply, Api.OfferSet, "Booking offers");
    if (set === null) continue;
    bucket.offersMs.push(Math.round(performance.now() - started));
    if (!set.searchComplete) { run.booking.searchIncomplete++; bucket.searchIncomplete++; }
    bucket.incompleteDates += set.incompleteDates?.length ?? 0;
    const refinement = set.refinement ?? "ABSENT";
    run.booking.refinements[refinement] = (run.booking.refinements[refinement] ?? 0) + 1;
    bucket.refinements[refinement] = (bucket.refinements[refinement] ?? 0) + 1;
    if (set.refinementStop !== undefined) {
      run.booking.refinementStops[set.refinementStop] = (run.booking.refinementStops[set.refinementStop] ?? 0) + 1;
      bucket.refinementStops[set.refinementStop] = (bucket.refinementStops[set.refinementStop] ?? 0) + 1;
    }
    if (set.regularWindowsFound !== undefined) run.booking.windowsFound[String(set.regularWindowsFound)] = (run.booking.windowsFound[String(set.regularWindowsFound)] ?? 0) + 1;
    run.booking.offers += set.offers.length;
    bucket.offers += set.offers.length;
    const offer = set.offers[0];
    if (offer === undefined) {
      run.booking.noOffers++;
      bucket.noOffers++;
      continue;
    }
    const offerPath = (action: string) => `/api/v1/booking/offers/${encodeURIComponent(offer.offerId)}/${action}`;
    const release = async () => {
      const released = await call(run, "release", "POST", offerPath("release"), { requestId: randomUUID() }, 10_000);
      if (released?.status === 200) run.booking.released++;
    };
    if (run.random() >= settings.confirmRate) {
      await release();
      continue;
    }
    const selected = await call(run, "select", "POST", offerPath("select"), { requestId: randomUUID() }, 10_000);
    if (selected === null || selected.status !== 200) {
      if (selected?.status === 409) run.booking.holdUnavailable++;
      continue;
    }
    const hold = parsed(run, selected, Api.Hold, "Select");
    if (hold === null) continue;
    run.booking.selected++;
    const confirmed = await current(run, "confirm", `/api/v1/booking/holds/${encodeURIComponent(hold.holdId)}/confirm`,
      () => ({ requestId: randomUUID(), snapshot: settings.store.snapshot([offer.serviceDate]) }), timeoutMs);
    if (confirmed === null || confirmed.status !== 200) {
      if (confirmed?.status === 409) run.booking.holdUnavailable++;
      await release();
      continue;
    }
    const receipt = parsed(run, confirmed, Api.CommitReceipt, "Confirm");
    if (receipt === null) continue;
    if (settings.store.write(receipt, { job, window: offer.window }) === "CONFLICT") {
      run.booking.hostWriteConflicts++;
      await release();
    } else {
      run.booking.confirmed++;
    }
  }
}

export async function dailyLoop(run: Run, settings: DailySettings, timeoutMs: number): Promise<void> {
  while (Date.now() < run.endsAt) {
    run.daily.proposals++;
    const body = { requestId: randomUUID(), serviceDate: settings.date, snapshot: settings.store.snapshot([settings.date]) };
    const proposal = settings.mode === "sync" ? await synchronous(run, body, timeoutMs) : await queued(run, body);
    if (proposal === null) {
      await sleep(200);
      continue;
    }
    run.daily.decisions[proposal.decision] = (run.daily.decisions[proposal.decision] ?? 0) + 1;
  }
}

async function synchronous(run: Run, body: { requestId: string }, timeoutMs: number): Promise<Api.DailyProposal | null> {
  const reply = await call(run, "daily proposal", "POST", "/api/v1/daily/proposals", body, timeoutMs);
  return reply === null || reply.status !== 201 ? null : parsed(run, reply, Api.DailyProposal, "Daily proposal");
}

/**
 * Queues the proposal, then polls its status until it completes. "daily queue" is the call that queues it; "daily
 * proposal" is from queuing to the completed answer, with the answer's status, as the synchronous call would have had.
 */
async function queued(run: Run, body: { requestId: string }): Promise<Api.DailyProposal | null> {
  const started = performance.now();
  const accepted = await call(run, "daily queue", "POST", "/api/v1/daily/proposals", body, 30_000, { Prefer: "respond-async" });
  if (accepted === null || accepted.status !== 202) return null;
  if (parsed(run, accepted, Api.RequestStatus, "Queued daily proposal") === null) return null;
  // Polls are not BUSY retries: the proposal counts as retried only when queuing it was refused first.
  const queueAttempts = attemptsOf.get(accepted) ?? 1;
  let wait = retryAfterMs(accepted);
  for (;;) {
    await sleep(Math.min(wait, 5000));
    let reply: Reply;
    try {
      reply = await run.api.call("GET", `/api/v1/requests/${body.requestId}`, undefined, { timeoutMs: 30_000 });
    } catch (error) {
      run.records.push({ operation: "daily proposal", status: null, error: error instanceof Error ? error.name : "Error", message: null,
        ms: Math.round(performance.now() - started), attempts: queueAttempts });
      return null;
    }
    const status = reply.status === 200 ? parsed(run, reply, Api.RequestStatus, "Daily request status") : null;
    if (status === null) {
      run.records.push({ operation: "daily proposal", status: reply.status, ...problem(reply), ms: Math.round(performance.now() - started), attempts: queueAttempts });
      return null;
    }
    const result = status.result;
    if (status.state !== "COMPLETED" || result === undefined) {
      if (Date.now() > run.endsAt + 600_000) return null;
      wait = retryAfterMs(reply);
      continue;
    }
    run.records.push({ operation: "daily proposal", status: result.status, ...problem({ status: result.status, json: result.body }),
      ms: Math.round(performance.now() - started), attempts: queueAttempts });
    if (status.queuedAt !== undefined && status.startedAt !== undefined) run.daily.queueWaitsMs.push(Date.parse(status.startedAt) - Date.parse(status.queuedAt));
    return result.status === 201 ? Api.DailyProposal.parse(result.body) : null;
  }
}

/** Scrapes the scheduler's metrics every `everyMs` until stopped; failed scrapes are counted, not fatal. */
export function sampleMetrics(url: string, everyMs: number): { stop: () => Promise<{ samples: Sample[][]; failures: number }> } {
  const samples: Sample[][] = [];
  let failures = 0;
  let running = true;
  const loop = (async () => {
    while (running) {
      try { samples.push(await scrape(url)); } catch { failures++; }
      await sleep(everyMs);
    }
  })();
  return { stop: async () => { running = false; await loop; return { samples, failures }; } };
}

export interface ContainerUsage { readonly name: string; readonly cpuPercent: { readonly mean: number; readonly max: number }; readonly memoryMaxBytes: number }

const UNITS: Record<string, number> = { B: 1, KiB: 1024, MiB: 1024 ** 2, GiB: 1024 ** 3, TiB: 1024 ** 4, kB: 1000, MB: 1000 ** 2, GB: 1000 ** 3 };

/** Bytes of a docker stats size such as "1.25GiB". */
export function dockerBytes(text: string): number {
  const match = /^([\d.]+)\s*([A-Za-z]+)$/.exec(text.trim());
  const unit = match?.[2] === undefined ? undefined : UNITS[match[2]];
  if (match?.[1] === undefined || unit === undefined) throw new Error(`Unreadable docker size: ${text}`);
  return Number(match[1]) * unit;
}

/** Samples `docker stats` for the named containers every `everyMs` until stopped. */
export function sampleContainers(names: readonly string[], everyMs: number): { stop: () => Promise<ContainerUsage[]> } {
  const run = promisify(execFile);
  const seen = new Map<string, { cpu: number[]; memory: number[] }>(names.map(name => [name, { cpu: [], memory: [] }]));
  let running = true;
  const loop = (async () => {
    while (running) {
      try {
        const { stdout } = await run("docker", ["stats", "--no-stream", "--format", "{{.Name}}\t{{.CPUPerc}}\t{{.MemUsage}}", ...names]);
        for (const line of stdout.split("\n")) {
          const [name, cpu, memory] = line.trim().split("\t");
          const entry = name === undefined ? undefined : seen.get(name);
          if (entry === undefined || cpu === undefined || memory === undefined) continue;
          entry.cpu.push(Number(cpu.replace("%", "")));
          entry.memory.push(dockerBytes(memory.split("/")[0] ?? ""));
        }
      } catch { /* a container that is not running yields no sample */ }
      await sleep(everyMs);
    }
  })();
  return {
    stop: async () => {
      running = false;
      await loop;
      return [...seen].filter(([, entry]) => entry.cpu.length > 0).map(([name, entry]) => ({ name,
        cpuPercent: { mean: entry.cpu.reduce((sum, value) => sum + value, 0) / entry.cpu.length, max: Math.max(...entry.cpu) },
        memoryMaxBytes: Math.max(...entry.memory) }));
    },
  };
}
