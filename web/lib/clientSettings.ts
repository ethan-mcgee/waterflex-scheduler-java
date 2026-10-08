import { Prisma } from "@prisma/client";
import { z } from "zod";

/**
 * A client's booking and routing settings: what the portal sends as `rates`, `policy`, `offerLimit` and the booking
 * horizon with every public API calculation for that client. Money and fractions stay exact decimals end to end.
 * Percentages are what people edit; the API takes fractions ("20" percent is "0.2").
 */

/** Dollars with at most four decimal places, as the database stores them. */
const dollars = z.string().trim().regex(/^(0|[1-9][0-9]{0,9})(\.[0-9]{1,4})?$/, "Enter dollars, for example 30 or 0.67");
/** A percentage from 0 to 100 with at most two decimal places, so the fraction fits four places. */
const percent = z.string().trim().regex(/^(0|[1-9][0-9]{0,2})(\.[0-9]{1,2})?$/, "Enter a percentage, for example 20 or 2.5")
  .refine(value => new Prisma.Decimal(value).lte(100), "A percentage is at most 100");

export const solverSettingsInput = z.object({
  regularHourly: dollars,
  overtimeHourly: dollars,
  mileagePerMile: dollars,
  travelBufferPercent: percent,
  travelBufferMinutes: z.int().min(0).max(120),
  fairnessBudgetPercent: percent,
  offerLimit: z.int().min(1).max(4),
  bookingHorizonWeekdays: z.int().min(1).max(15),
}).strict();
export type SolverSettingsInput = z.infer<typeof solverSettingsInput>;

/** A save carries the version it was edited from; null means the client has no settings yet. */
export const saveSolverSettings = z.object({ expectedVersion: z.int().nonnegative().nullable(), settings: solverSettingsInput }).strict();

/** Settings as stored. */
export interface StoredSolverSettings {
  regularHourly: Prisma.Decimal; overtimeHourly: Prisma.Decimal; mileagePerMile: Prisma.Decimal;
  travelBufferPct: Prisma.Decimal; travelBufferMinutes: number; fairnessBudget: Prisma.Decimal;
  offerLimit: number; bookingHorizonWeekdays: number; version: number; updatedAt: Date;
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
    bookingHorizonWeekdays: input.bookingHorizonWeekdays,
  };
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
    bookingHorizonWeekdays: stored.bookingHorizonWeekdays,
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

/** The public API's `Snapshot.policy` for this client. */
export function publicPolicy(stored: StoredSolverSettings) {
  return { fairnessBudget: canonicalDecimal(stored.fairnessBudget) };
}
