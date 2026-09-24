import type { z } from "zod";
import type { appointmentSearch } from "./contracts";

/** Browser API duration includes transport; initial/pin calls also include address validation. No customer data. */
export function recordBookingApiDuration(start: number, flow: "initial" | "pin" | "refresh" | "selection") {
  const name = "waterflex.booking-api";
  if (performance.getEntriesByName(name, "measure").length >= 128) performance.clearMeasures(name);
  performance.measure(name, { start, end: performance.now(), detail: { flow, includesAddressValidation: flow === "initial" || flow === "pin" } });
}

export function appointmentSearchMessage(search: z.infer<typeof appointmentSearch>): string | null {
  switch (search.outcome) {
    case "AVAILABLE": return null;
    case "SEARCH_INCOMPLETE": return "The appointment search did not finish. Please retry to check available times.";
    case "NO_CANDIDATE_FOUND": return "No appointment was found in this search. You can retry or contact us for help.";
    case "ROUTING_UNAVAILABLE": return "Road routing is temporarily unavailable. Please retry your appointment search.";
    case "SCHEDULE_CONFLICT": return "The schedule changed during your search. Please retry to get current times.";
    case "SERVICE_BUSY": return "Appointment search is temporarily busy. Please retry.";
  }
}
