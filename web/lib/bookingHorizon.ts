import { bookingHorizon } from "./apiBookingCore";
import { dynamicHorizon, lastBookedDate, scanWindow, type HorizonDecision } from "./bookingHorizonCore";
import { storedHorizon, type StoredSolverSettings } from "./clientSettings";
import { buildClientSnapshot } from "./clientSnapshot";

/** The client's booking horizon in a metro from `today`: fixed, or chosen from a snapshot of the cap window. */
export type ClientHorizon = { mode: "FIXED"; dates: string[] } | ({ mode: "DYNAMIC"; lastBookedDate: string | null } & HorizonDecision);

export async function clientHorizon(clientId: string, metroId: string, today: string, settings: StoredSolverSettings): Promise<ClientHorizon> {
  const { mode, rule } = storedHorizon(settings);
  if (mode === "FIXED") return { mode, dates: bookingHorizon(today, rule.capWeekdays) };
  const scan = await buildClientSnapshot(clientId, metroId, scanWindow(today, rule));
  return { mode, ...dynamicHorizon(today, scan, rule), lastBookedDate: lastBookedDate(scan) };
}

/** One line per search, so a study can read how long each horizon was and why. */
export function describeHorizon(horizon: ClientHorizon): Record<string, string | number | boolean | null> {
  const first = horizon.dates[0] ?? null, last = horizon.dates.at(-1) ?? null;
  return horizon.mode === "FIXED" ? { mode: horizon.mode, dates: horizon.dates.length, first, last }
    : { mode: horizon.mode, dates: horizon.dates.length, first, last, lastFullDate: horizon.lastFullDate, capped: horizon.capped };
}
