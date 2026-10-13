// The response shapes of docs/api/openapi-v1.yaml, strict: an unknown field, a missing required field or a value out of
// range is a conformance failure, exactly as the scheduler treats a host's request. test/spec.test.ts keeps every
// object here equal to its schema in the spec (property names and required fields).
import { z } from "zod";

export const Id = z.string().min(1).max(128).regex(/^\S(.*\S)?$/);
export const Instant = z.iso.datetime({ offset: true });
export const ServiceDate = z.iso.date();
export const TenantId = z.string().regex(/^[a-z0-9][a-z0-9-]{0,62}$/);

export const ERROR_CODES = ["INVALID_REQUEST", "NOT_FOUND", "STALE", "NOT_COMMITTABLE", "HOLD_UNAVAILABLE", "INCOMPLETE_FACTS", "BUSY",
  "ROUTING_UNAVAILABLE", "CALCULATION_UNAVAILABLE", "CANCELLED"] as const;
export type ErrorCode = (typeof ERROR_CODES)[number];

export const Problem = z.strictObject({ error: z.enum(ERROR_CODES), message: z.string().min(1) });

export const TechnicianDayVersion = z.strictObject({ technicianId: Id, serviceDate: ServiceDate, lastModified: Instant });

export const StaleProblem = z.strictObject({ error: z.literal("STALE"), message: z.string().min(1), changed: z.array(TechnicianDayVersion).min(1) });

export const SkippedTechnicianDay = z.strictObject({ technicianId: Id, serviceDate: ServiceDate, reason: z.enum(["LOCATION_UNRESOLVED"]),
  message: z.string().min(1) });

export const PlannedStop = z.strictObject({ appointmentId: Id, sequence: z.int().min(0), plannedStart: Instant, plannedEnd: Instant });

export const PlannedRoute = z.strictObject({ technicianId: Id, serviceDate: ServiceDate, stops: z.array(PlannedStop) });

export const DailyProposal = z.strictObject({
  proposalId: Id,
  inputRevision: z.string().regex(/^[0-9a-f]{64}$/),
  decision: z.enum(["IMPROVED", "NO_IMPROVEMENT", "REJECTED_BY_POLICY"]),
  reason: z.string().min(1),
  routes: z.array(PlannedRoute),
  unresolvedAppointmentIds: z.array(Id),
  skippedTechnicianDays: z.array(SkippedTechnicianDay),
  costCents: z.int().min(0),
  overtimeMinutes: z.literal(0),
});

export const RouteEvaluation = z.strictObject({
  feasible: z.boolean(),
  routes: z.array(PlannedRoute),
  skippedTechnicianDays: z.array(SkippedTechnicianDay),
  costCents: z.int().min(0),
  overtimeMinutes: z.int().min(0).max(1440),
});

export const LocatedStop = z.strictObject({ appointmentId: Id, sequence: z.int().min(0), lat: z.number().min(-90).max(90),
  lng: z.number().min(-180).max(180), source: z.enum(["HOST", "NOMINATIM"]) });

export const GeometryLeg = z.strictObject({ segment: z.int().min(0), legIndex: z.int().min(0), seconds: z.int().min(0), meters: z.int().min(0),
  coordinates: z.array(z.tuple([z.number(), z.number()])).min(2) });

export const GeometryRoute = z.strictObject({ technicianId: Id, serviceDate: ServiceDate, stops: z.array(LocatedStop), legs: z.array(GeometryLeg) });

export const RouteGeometry = z.strictObject({ routingIdentity: z.string().min(1), routes: z.array(GeometryRoute),
  skippedTechnicianDays: z.array(SkippedTechnicianDay) });

export const Window = z.strictObject({ start: Instant, end: Instant });

export const Offer = z.strictObject({ offerId: Id, serviceDate: ServiceDate, window: Window });

export const OfferSet = z.strictObject({ offerSetId: Id, expiresAt: Instant, offers: z.array(Offer).max(4), searchComplete: z.boolean(),
  skippedTechnicianDays: z.array(SkippedTechnicianDay) });

export const Hold = z.strictObject({ holdId: Id, offerId: Id, expiresAt: Instant });

export const Released = z.strictObject({ released: z.literal(true) });

export const Assignment = z.strictObject({ appointmentId: Id, technicianId: Id, serviceDate: ServiceDate, sequence: z.int().min(0),
  plannedStart: Instant, plannedEnd: Instant });

export const CommitReceipt = z.strictObject({ receiptId: Id, assignments: z.array(Assignment), technicianDays: z.array(TechnicianDayVersion) });

export const WhoAmI = z.strictObject({ tenantId: TenantId });

/** Every object above under its name in the spec's components.schemas, for the drift test. */
export const SPEC_SCHEMAS = { Problem, TechnicianDayVersion, StaleProblem, SkippedTechnicianDay, PlannedStop, PlannedRoute, DailyProposal,
  RouteEvaluation, LocatedStop, GeometryLeg, GeometryRoute, RouteGeometry, Window, Offer, OfferSet, Hold, Released, Assignment, CommitReceipt,
  WhoAmI } as const;

export type DailyProposal = z.infer<typeof DailyProposal>;
export type OfferSet = z.infer<typeof OfferSet>;
export type CommitReceipt = z.infer<typeof CommitReceipt>;
export type TechnicianDayVersion = z.infer<typeof TechnicianDayVersion>;
