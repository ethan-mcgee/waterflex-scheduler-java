// The window threshold and refinement cap studies' quality measures. Each case searches the same records and job once
// per variant (a threshold, and optionally a refinement cap), so the offer sets compare directly: how many offers, how
// soon, on how many days, what the best one adds to the fleet's modeled cost (its receipt applied to the day and timed
// by /api/v1/routes/evaluate, never written to the host), and how many existing visits that booking moves.
import type { z } from "zod";
import type * as Api from "../api.ts";
import type { Appointment, Snapshot, Window } from "../snapshot.ts";
import { percentile } from "./stats.ts";
import { datesBetween } from "../time.ts";

export type Receipt = z.infer<typeof Api.CommitReceipt>;

export interface OfferedWindow { readonly serviceDate: string; readonly start: string; readonly end: string }

/** The best offer's booking: its added cost and what it did to the existing visits. Null fields were not measured. */
export interface Priced {
  /** Modeled fleet cost of the offer's day before and after the booking, in cents, and the difference. */
  readonly beforeCostCents: number;
  readonly afterCostCents: number;
  readonly addedCostCents: number;
  /** Existing visits the booking put on another technician. */
  readonly reassignedVisits: number;
  /** Technician-days whose existing visits run in a different order after the booking. */
  readonly reorderedDays: number;
}

/** A search setting under comparison: the policy's regularWindowThreshold and, when set, its bookingRefinementMs. */
export interface Variant {
  /** `2`, or `2:500` with a refinement cap of 500 ms. */
  readonly name: string;
  readonly threshold: number;
  /** Null leaves bookingRefinementMs out, so the scheduler's configured cap applies. */
  readonly refinementMs: number | null;
}

/** Parses `2,2:500,1000`: distinct variants, the first the baseline. */
export function parseVariants(text: string): Variant[] {
  const variants = text.split(",").map(item => {
    const match = /^(\d{1,4})(?::(\d{1,4}))?$/.exec(item.trim());
    const threshold = Number(match?.[1]), cap = match?.[2];
    const refinementMs = cap === undefined ? null : Number(cap);
    if (match === null || threshold > 1000 || refinementMs !== null && refinementMs > 1000)
      throw new Error(`Variant ${JSON.stringify(item.trim())} is not threshold[:refinement ms], each from 0 to 1000`);
    return { name: refinementMs === null ? `${threshold}` : `${threshold}:${refinementMs}`, threshold, refinementMs };
  });
  if (new Set(variants.map(item => item.name)).size !== variants.length) throw new Error("Variants must be distinct");
  return variants;
}

export interface Searched {
  /** The variant's name; its threshold and cap follow. */
  readonly variant: string;
  readonly threshold: number;
  readonly refinementMs: number | null;
  /** The search's final HTTP status after BUSY retries; null when no answer came. */
  readonly status: number | null;
  /** Caller milliseconds of an answered search (201), BUSY waits included. */
  readonly ms: number | null;
  readonly refinement: string | null;
  readonly searchComplete: boolean | null;
  readonly incompleteDates: number | null;
  readonly offers: readonly OfferedWindow[];
  /** The best offer priced; null when there was no offer or pricing failed (`pricingFailure` says why). */
  readonly priced: Priced | null;
  readonly pricingFailure: string | null;
}

export interface QualityCase {
  readonly index: number;
  /** The horizon's first date and its number of dates. */
  readonly firstDate: string;
  readonly dates: number;
  /** In the order of the variants list; `order` is the order they were searched in. */
  readonly searched: readonly Searched[];
  readonly order: readonly string[];
}

/** The day as it would be after the receipt is written: the host's own write, applied to a copy. */
export function withReceipt(before: Snapshot, receipt: Receipt, job: { id: string; serviceId: string; durationMinutes: number; location: Appointment["location"] },
                            window: Window): Snapshot {
  const dates = new Set(before.technicianDays.map(day => day.serviceDate));
  const appointments = new Map(before.appointments.map(item => [item.id, structuredClone(item)]));
  for (const assignment of receipt.assignments) {
    if (!dates.has(assignment.serviceDate)) throw new Error(`Receipt ${receipt.receiptId} assigns ${assignment.appointmentId} to ${assignment.serviceDate}, outside the snapshot`);
    const existing = appointments.get(assignment.appointmentId);
    if (existing === undefined && assignment.appointmentId !== job.id) throw new Error(`Receipt ${receipt.receiptId} assigns unknown appointment ${assignment.appointmentId}`);
    const base: Appointment = existing ?? { id: job.id, technicianId: assignment.technicianId, serviceDate: assignment.serviceDate, serviceId: job.serviceId,
      durationMinutes: job.durationMinutes, window, location: job.location, sequence: assignment.sequence, plannedStart: assignment.plannedStart };
    appointments.set(base.id, { ...base, technicianId: assignment.technicianId, serviceDate: assignment.serviceDate,
      sequence: assignment.sequence, plannedStart: assignment.plannedStart });
  }
  if (!appointments.has(job.id)) throw new Error(`Receipt ${receipt.receiptId} does not place job ${job.id}`);
  return { ...before, technicianDays: before.technicianDays.map(day => structuredClone(day)),
    appointments: [...appointments.values()].sort((a, b) => a.technicianId.localeCompare(b.technicianId) || a.serviceDate.localeCompare(b.serviceDate) || a.sequence - b.sequence) };
}

/** What the booking did to the visits that were already there. */
export function disturbance(before: Snapshot, after: Snapshot, jobId: string): { reassignedVisits: number; reorderedDays: number } {
  const was = new Map(before.appointments.map(item => [item.id, item]));
  let reassignedVisits = 0;
  for (const item of after.appointments) {
    const previous = was.get(item.id);
    if (previous !== undefined && (previous.technicianId !== item.technicianId || previous.serviceDate !== item.serviceDate)) reassignedVisits++;
  }
  const order = (snapshot: Snapshot) => {
    const days = new Map<string, string[]>();
    for (const item of [...snapshot.appointments].sort((a, b) => a.sequence - b.sequence)) {
      if (item.id === jobId) continue;
      const key = `${item.technicianId}|${item.serviceDate}`;
      days.set(key, [...(days.get(key) ?? []), item.id]);
    }
    return days;
  };
  const afterDays = order(after), beforeDays = order(before);
  let reorderedDays = 0;
  for (const [key, ids] of afterDays) {
    const kept = (beforeDays.get(key) ?? []).filter(id => ids.includes(id));
    if (kept.join() !== ids.filter(id => kept.includes(id)).join()) reorderedDays++;
  }
  return { reassignedVisits, reorderedDays };
}

const signature = (offers: readonly OfferedWindow[]) => offers.map(offer => `${offer.serviceDate} ${offer.start} ${offer.end}`).sort().join("|");
const earliest = (offers: readonly OfferedWindow[]) => offers.reduce<string | null>((first, offer) => first === null || offer.serviceDate < first ? offer.serviceDate : first, null);
const mean = (values: readonly number[]) => values.length === 0 ? null : values.reduce((sum, value) => sum + value, 0) / values.length;

export interface VariantSummary {
  readonly variant: string;
  readonly searches: number; readonly failed: number; readonly searchIncomplete: number;
  readonly refinements: Record<string, number>;
  readonly offersPerSearch: number | null; readonly noOffer: number;
  /** Mean days from the horizon's first date to the earliest offer, over searches with an offer. */
  readonly earliestLeadDays: number | null;
  readonly distinctDatesPerSearch: number | null;
  readonly priced: number; readonly pricingFailures: number;
  readonly addedCostCents: { readonly mean: number; readonly p50: number } | null;
  readonly reassignedVisits: number; readonly reorderedDays: number;
  readonly ms: { readonly p50: number; readonly p95: number } | null;
}

/** Each variant against the baseline (the first in the list) on the same cases. */
export interface Paired {
  readonly variant: string;
  readonly cases: number;
  readonly sameOffers: number;
  readonly moreOffers: number; readonly fewerOffers: number;
  readonly earlierFirstOffer: number; readonly laterFirstOffer: number;
  /** Cases where both best offers were priced. */
  readonly costCompared: number;
  readonly cheaper: number; readonly dearer: number;
  readonly meanCostChangeCents: number | null;
}

export function summarizeVariant(cases: readonly QualityCase[], variant: string): VariantSummary {
  const searched = cases.flatMap(item => item.searched.filter(entry => entry.variant === variant).map(entry => ({ entry, firstDate: item.firstDate })));
  const answered = searched.filter(({ entry }) => entry.status === 201);
  const withOffers = answered.filter(({ entry }) => entry.offers.length > 0);
  const priced = answered.flatMap(({ entry }) => entry.priced === null ? [] : [entry.priced]);
  const costs = priced.map(item => item.addedCostCents).sort((a, b) => a - b);
  const ms = answered.flatMap(({ entry }) => entry.ms === null ? [] : [entry.ms]).sort((a, b) => a - b);
  const refinements: Record<string, number> = {};
  for (const { entry } of answered) refinements[entry.refinement ?? "ABSENT"] = (refinements[entry.refinement ?? "ABSENT"] ?? 0) + 1;
  return {
    variant, searches: searched.length, failed: searched.length - answered.length,
    searchIncomplete: answered.filter(({ entry }) => entry.searchComplete === false).length, refinements,
    offersPerSearch: mean(answered.map(({ entry }) => entry.offers.length)), noOffer: answered.length - withOffers.length,
    earliestLeadDays: mean(withOffers.map(({ entry, firstDate }) => datesBetween(firstDate, earliest(entry.offers) ?? firstDate) - 1)),
    distinctDatesPerSearch: mean(withOffers.map(({ entry }) => new Set(entry.offers.map(offer => offer.serviceDate)).size)),
    priced: priced.length, pricingFailures: answered.filter(({ entry }) => entry.pricingFailure !== null).length,
    addedCostCents: costs.length === 0 ? null : { mean: mean(costs) ?? 0, p50: percentile(costs, 0.5) },
    reassignedVisits: priced.reduce((sum, item) => sum + item.reassignedVisits, 0), reorderedDays: priced.reduce((sum, item) => sum + item.reorderedDays, 0),
    ms: ms.length === 0 ? null : { p50: percentile(ms, 0.5), p95: percentile(ms, 0.95) },
  };
}

export function pairedAgainst(cases: readonly QualityCase[], baseline: string, variant: string): Paired {
  let compared = 0, sameOffers = 0, moreOffers = 0, fewerOffers = 0, earlierFirstOffer = 0, laterFirstOffer = 0, cheaper = 0, dearer = 0;
  const changes: number[] = [];
  for (const item of cases) {
    const base = item.searched.find(entry => entry.variant === baseline), other = item.searched.find(entry => entry.variant === variant);
    if (base === undefined || other === undefined || base.status !== 201 || other.status !== 201) continue;
    compared++;
    if (signature(base.offers) === signature(other.offers)) sameOffers++;
    if (other.offers.length > base.offers.length) moreOffers++;
    if (other.offers.length < base.offers.length) fewerOffers++;
    const a = earliest(base.offers), b = earliest(other.offers);
    if (b !== null && (a === null || b < a)) earlierFirstOffer++;
    if (a !== null && (b === null || b > a)) laterFirstOffer++;
    if (base.priced !== null && other.priced !== null) {
      const change = other.priced.addedCostCents - base.priced.addedCostCents;
      changes.push(change);
      if (change < 0) cheaper++;
      if (change > 0) dearer++;
    }
  }
  return { variant, cases: compared, sameOffers, moreOffers, fewerOffers, earlierFirstOffer, laterFirstOffer,
    costCompared: changes.length, cheaper, dearer, meanCostChangeCents: mean(changes) };
}

const fixed = (value: number | null, digits: number) => value === null ? "-" : value.toFixed(digits);
const entries = (record: Record<string, number>) => Object.entries(record).map(([key, value]) => `${key} ${value}`).join(", ") || "none";

export function qualityLines(variants: readonly string[], cases: readonly QualityCase[]): string[] {
  const baseline = variants[0];
  if (baseline === undefined) throw new Error("No variants");
  const rows = variants.map(variant => summarizeVariant(cases, variant)).map(s =>
    `| ${s.variant} | ${s.searches} | ${s.failed} | ${s.searchIncomplete} | ${entries(s.refinements)} | ${fixed(s.offersPerSearch, 2)} | ${s.noOffer} | ` +
    `${fixed(s.earliestLeadDays, 2)} | ${fixed(s.distinctDatesPerSearch, 2)} | ${s.addedCostCents === null ? "-" : `${(s.addedCostCents.mean / 100).toFixed(2)} / ${(s.addedCostCents.p50 / 100).toFixed(2)}`} | ` +
    `${s.reassignedVisits} / ${s.reorderedDays} | ${s.ms === null ? "-" : `${s.ms.p50} / ${s.ms.p95}`} |`);
  const paired = variants.slice(1).map(variant => pairedAgainst(cases, baseline, variant)).map(p =>
    `| ${p.variant} | ${p.cases} | ${p.sameOffers} | ${p.moreOffers} / ${p.fewerOffers} | ${p.earlierFirstOffer} / ${p.laterFirstOffer} | ` +
    `${p.costCompared} | ${p.cheaper} / ${p.dearer} | ${p.meanCostChangeCents === null ? "-" : (p.meanCostChangeCents / 100).toFixed(2)} |`);
  return ["## Offers by variant (window threshold[:refinement cap ms])", "",
    "| Variant | Searches | Failed | searchComplete false | Refinement | Offers per search | No offer | Days to first offer | Days offered | Best offer adds, mean / p50 ($) | Visits reassigned / days reordered | Search p50 / p95 ms |",
    "| ---: | ---: | ---: | ---: | --- | ---: | ---: | ---: | ---: | --- | --- | --- |", ...rows,
    ...(paired.length === 0 ? [] : ["", `## Against ${baseline} on the same cases`, "",
      "| Variant | Cases | Same offers | More / fewer offers | Earlier / later first offer | Best offers priced | Cheaper / dearer | Mean cost change ($) |",
      "| ---: | ---: | ---: | --- | --- | ---: | --- | ---: |", ...paired])];
}
