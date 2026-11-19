/**
 * Reason codes and the time a work order is allotted. A work order carries one to three reason codes (WaterFlex's
 * complaint codes); each has minutes and a confidence buffer, and the codes' total is the suggested time. The dispatcher
 * can allot a different time, which is what the scheduler plans with.
 */

/** The scheduler plans a job of 1 to 720 minutes (the public contract's `durationMinutes`). */
export const MIN_DURATION_MIN = 1;
export const MAX_DURATION_MIN = 720;
export const MAX_REASON_CODES = 3;

export interface ReasonCodeTime {
  estDurationMin: number;
  /** 0.15 is +15%. */
  bufferPct: number;
}

/**
 * The suggested time for a set of codes: each code's minutes plus its buffer, summed and rounded once, so a single code
 * suggests what one service did before reason codes. Null when there are no codes or a code's time is not usable.
 */
export function suggestedMinutes(codes: readonly ReasonCodeTime[]): number | null {
  if (codes.length === 0) return null;
  let total = 0;
  for (const code of codes) {
    if (!Number.isSafeInteger(code.estDurationMin) || code.estDurationMin <= 0) return null;
    if (!Number.isFinite(code.bufferPct) || code.bufferPct < 0) return null;
    total += code.estDurationMin * (1 + code.bufferPct);
  }
  const rounded = Math.round(total);
  return Number.isSafeInteger(rounded) && rounded > 0 ? rounded : null;
}

export type Allotment =
  | { durationMin: number; suggestedMin: number; overridden: boolean }
  | { refusal: { status: number; error: string } };

/**
 * The time a work order is allotted: the dispatcher's time when given, otherwise the suggestion. A suggestion the
 * scheduler cannot plan (over 12 hours) needs the dispatcher's own time; a missing suggestion is never replaced.
 */
export function allotTime(suggested: number | null, override: number | undefined): Allotment {
  if (suggested === null) return { refusal: { status: 503, error: "The reason codes' minutes are unavailable." } };
  if (override !== undefined) {
    if (!Number.isSafeInteger(override) || override < MIN_DURATION_MIN || override > MAX_DURATION_MIN)
      return { refusal: { status: 400, error: `Time allotted must be ${MIN_DURATION_MIN} to ${MAX_DURATION_MIN} minutes.` } };
    return { durationMin: override, suggestedMin: suggested, overridden: override !== suggested };
  }
  if (suggested > MAX_DURATION_MIN)
    return { refusal: { status: 422, error: `These reason codes add up to ${suggested} minutes, more than a day can hold. Enter a shorter time allotted.` } };
  return { durationMin: suggested, suggestedMin: suggested, overridden: false };
}

/** The codes as a card names them: the first one's label, and how many more there are. */
export function reasonCodesLabel(names: readonly string[]): string | null {
  const [first, ...rest] = names;
  if (first === undefined) return null;
  return rest.length === 0 ? first : `${first} +${rest.length} more`;
}
