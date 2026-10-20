import { Prisma } from "@prisma/client";
import { z } from "zod";
import { HORIZON_MODES, MAX_FULL_PERCENT, MAX_LEAD_WEEKDAYS, MIN_FULL_PERCENT, MIN_LEAD_WEEKDAYS, type HorizonMode, type HorizonRule } from "./bookingHorizonCore";

/**
 * A client's booking and routing settings: what the portal sends as `rates`, `policy` (including the daily search method
 * and times, booking deadline and window threshold), `offerLimit`, `windowHours` and the booking horizon (fixed, or dynamic under a cap) with every public API calculation for that client. Money and fractions stay exact decimals end to end.
 * Percentages are what people edit; the API takes fractions ("20" percent is "0.2").
 */

/** Dollars with at most four decimal places, as the database stores them. */
const dollars = z.string().trim().regex(/^(0|[1-9][0-9]{0,9})(\.[0-9]{1,4})?$/, "Enter dollars, for example 30 or 0.67");
/** A percentage from 0 to 100 with at most two decimal places, so the fraction fits four places. */
const percent = z.string().trim().regex(/^(0|[1-9][0-9]{0,2})(\.[0-9]{1,2})?$/, "Enter a percentage, for example 20 or 2.5")
  .refine(value => new Prisma.Decimal(value).lte(100), "A percentage is at most 100");

/** The daily search methods a client may choose; the scheduler accepts exactly these. */
export const DAILY_SEARCHES = ["TABU_KOPT", "TABU", "TABU_KOPT_SIZE_3"] as const;
export const dailySearch = z.enum(DAILY_SEARCHES);
export type DailySearch = z.infer<typeof dailySearch>;
export const horizonMode = z.enum(HORIZON_MODES);
/** Cost and fairness search seconds together may be at most this. */
export const MAX_SEARCH_SECONDS = 240;
/** A booking search or confirm deadline at the scheduler, in milliseconds; the upper bound is the latency target. */
export const MIN_BOOKING_DEADLINE_MS = 1000;
export const MAX_BOOKING_DEADLINE_MS = 5000;
/** The scheduler's bound on the regular window threshold. */
export const MAX_REGULAR_WINDOW_THRESHOLD = 1000;

export const solverSettingsInput = z.object({
  regularHourly: dollars,
  overtimeHourly: dollars,
  mileagePerMile: dollars,
  travelBufferPercent: percent,
  travelBufferMinutes: z.int().min(0).max(120),
  fairnessBudgetPercent: percent,
  offerLimit: z.int().min(1).max(4),
  windowHours: z.int().min(1).max(8),
  bookingHorizonWeekdays: z.int().min(1).max(15),
  horizonMode,
  horizonLeadWeekdays: z.int().min(MIN_LEAD_WEEKDAYS).max(MAX_LEAD_WEEKDAYS),
  horizonFullPercent: z.int().min(MIN_FULL_PERCENT).max(MAX_FULL_PERCENT),
  dailySearch,
  costSearchSeconds: z.int().min(5).max(MAX_SEARCH_SECONDS),
  fairnessSearchSeconds: z.int().min(0).max(MAX_SEARCH_SECONDS),
  // Null is the client's choice that every search spends its whole time; the key is required so it is never assumed.
  unimprovedSearchSeconds: z.int().min(1).max(MAX_SEARCH_SECONDS).nullable(),
  bookingDeadlineMs: z.int().min(MIN_BOOKING_DEADLINE_MS).max(MAX_BOOKING_DEADLINE_MS),
  regularWindowThreshold: z.int().min(0).max(MAX_REGULAR_WINDOW_THRESHOLD),
}).strict().refine(settings => settings.costSearchSeconds + settings.fairnessSearchSeconds <= MAX_SEARCH_SECONDS,
  { message: `Cost and fairness search time together must be at most ${MAX_SEARCH_SECONDS} seconds`, path: ["fairnessSearchSeconds"] })
  .refine(settings => settings.unimprovedSearchSeconds === null || settings.unimprovedSearchSeconds <= settings.costSearchSeconds,
    { message: "Stop when unimproved must be at most the cost search time", path: ["unimprovedSearchSeconds"] });
export type SolverSettingsInput = z.infer<typeof solverSettingsInput>;

/** A save carries the version it was edited from; null means the client has no settings yet. */
export const saveSolverSettings = z.object({ expectedVersion: z.int().nonnegative().nullable(), settings: solverSettingsInput }).strict();

/** Settings as stored. */
export interface StoredSolverSettings {
  regularHourly: Prisma.Decimal; overtimeHourly: Prisma.Decimal; mileagePerMile: Prisma.Decimal;
  travelBufferPct: Prisma.Decimal; travelBufferMinutes: number; fairnessBudget: Prisma.Decimal;
  offerLimit: number; windowHours: number; bookingHorizonWeekdays: number;
  /** Stored as text under a CHECK constraint; validated with `horizonMode` before use. */
  horizonMode: string; horizonLeadWeekdays: number; horizonFullPercent: number;
  /** Stored as text under a CHECK constraint; validated with `dailySearch` before use. */
  dailySearch: string; costSearchSeconds: number; fairnessSearchSeconds: number;
  /** Null: every daily search spends its whole time. */
  unimprovedSearchSeconds: number | null;
  bookingDeadlineMs: number; regularWindowThreshold: number; version: number; updatedAt: Date;
}

export interface SolverSettingsView extends SolverSettingsInput { version: number; updatedAt: string }

/** The canonical decimal string the public API requires: no exponent, no trailing zeros, no trailing point. */
export function canonicalDecimal(value: Prisma.Decimal): string {
  if (!value.isFinite() || value.isNegative()) throw new Error(`Not a non-negative decimal: ${value.toString()}`);
  const fixed = value.toFixed();
  return fixed.includes(".") ? fixed.replace(/0+$/, "").replace(/\.$/, "") : fixed;
}

export function toStored(input: SolverSettingsInput) {
  return {
    regularHourly: new Prisma.Decimal(input.regularHourly),
    overtimeHourly: new Prisma.Decimal(input.overtimeHourly),
    mileagePerMile: new Prisma.Decimal(input.mileagePerMile),
    travelBufferPct: new Prisma.Decimal(input.travelBufferPercent).div(100),
    travelBufferMinutes: input.travelBufferMinutes,
    fairnessBudget: new Prisma.Decimal(input.fairnessBudgetPercent).div(100),
    offerLimit: input.offerLimit,
    windowHours: input.windowHours,
    bookingHorizonWeekdays: input.bookingHorizonWeekdays,
    horizonMode: input.horizonMode,
    horizonLeadWeekdays: input.horizonLeadWeekdays,
    horizonFullPercent: input.horizonFullPercent,
    dailySearch: input.dailySearch,
    costSearchSeconds: input.costSearchSeconds,
    fairnessSearchSeconds: input.fairnessSearchSeconds,
    unimprovedSearchSeconds: input.unimprovedSearchSeconds,
    bookingDeadlineMs: input.bookingDeadlineMs,
    regularWindowThreshold: input.regularWindowThreshold,
  };
}

/** The stored search method, checked: a value outside the allowed set fails instead of being sent or shown. */
function storedSearch(stored: StoredSolverSettings): DailySearch {
  return dailySearch.parse(stored.dailySearch);
}

/** How the client's booking horizon is chosen, checked: a stored mode outside the allowed set fails instead of being used. */
export function storedHorizon(stored: StoredSolverSettings): { mode: HorizonMode; rule: HorizonRule } {
  return { mode: horizonMode.parse(stored.horizonMode),
    rule: { capWeekdays: stored.bookingHorizonWeekdays, leadWeekdays: stored.horizonLeadWeekdays, fullPercent: stored.horizonFullPercent } };
}

export function toView(stored: StoredSolverSettings): SolverSettingsView {
  return {
    regularHourly: canonicalDecimal(stored.regularHourly),
    overtimeHourly: canonicalDecimal(stored.overtimeHourly),
    mileagePerMile: canonicalDecimal(stored.mileagePerMile),
    travelBufferPercent: canonicalDecimal(stored.travelBufferPct.mul(100)),
    travelBufferMinutes: stored.travelBufferMinutes,
    fairnessBudgetPercent: canonicalDecimal(stored.fairnessBudget.mul(100)),
    offerLimit: stored.offerLimit,
    windowHours: stored.windowHours,
    bookingHorizonWeekdays: stored.bookingHorizonWeekdays,
    horizonMode: horizonMode.parse(stored.horizonMode),
    horizonLeadWeekdays: stored.horizonLeadWeekdays,
    horizonFullPercent: stored.horizonFullPercent,
    dailySearch: storedSearch(stored),
    costSearchSeconds: stored.costSearchSeconds,
    fairnessSearchSeconds: stored.fairnessSearchSeconds,
    unimprovedSearchSeconds: stored.unimprovedSearchSeconds,
    bookingDeadlineMs: stored.bookingDeadlineMs,
    regularWindowThreshold: stored.regularWindowThreshold,
    version: stored.version,
    updatedAt: stored.updatedAt.toISOString(),
  };
}

/** The public API's `Snapshot.rates` for this client. */
export function publicRates(stored: StoredSolverSettings) {
  return {
    regularHourly: canonicalDecimal(stored.regularHourly),
    overtimeHourly: canonicalDecimal(stored.overtimeHourly),
    mileagePerMile: canonicalDecimal(stored.mileagePerMile),
    travelBufferPct: canonicalDecimal(stored.travelBufferPct),
    travelBufferMinutes: stored.travelBufferMinutes,
  };
}

/** The public API's `Snapshot.policy` for this client: every policy rule, since the scheduler holds none. */
export function publicPolicy(stored: StoredSolverSettings) {
  return {
    fairnessBudget: canonicalDecimal(stored.fairnessBudget),
    dailySearch: storedSearch(stored),
    costSearchSeconds: stored.costSearchSeconds,
    fairnessSearchSeconds: stored.fairnessSearchSeconds,
    // Optional in the API: sent only when the client set a limit, so its absence means the whole time.
    ...(stored.unimprovedSearchSeconds === null ? {} : { unimprovedSearchSeconds: stored.unimprovedSearchSeconds }),
    bookingDeadlineMs: stored.bookingDeadlineMs,
    regularWindowThreshold: stored.regularWindowThreshold,
  };
}
