import { z } from "zod";
import { isValidPhone } from "./phone";

// Browser-safe decoders. Keep network and database values unknown until parsed.
export const text = z.string().trim().min(1);
export const date = z.iso.date();
export const instant = z.iso.datetime({ offset: true });
export const finite = z.number().finite();
export const costModelVersion = z.enum(["legacy-double-v1", "exact-fleet-half-up-v2"]);
// NUMERIC(65,30); canonical plain strings keep monetary values out of JS number arithmetic.
export const monetaryDecimal = z.string().regex(/^(?:0|[1-9][0-9]{0,34})(?:\.[0-9]{0,29}[1-9])?$/);
export const operatingRates = z.object({ regularHourly: monetaryDecimal, overtimeHourly: monetaryDecimal,
  mileagePerMile: monetaryDecimal, travelBufferPct: finite.nonnegative(), travelBufferMinutes: z.int().nonnegative() });
export const minute = z.int().min(0).max(1440);
export const point = z.object({ lat: finite.min(-90).max(90), lng: finite.min(-180).max(180) });
export const dealershipPolicy = z.object({ departure: z.enum(["HOME", "DEPOT"]), returnTo: z.enum(["HOME", "DEPOT"]) }).strict();
export const depotSetup = dealershipPolicy.extend({ dealershipId: text, metroId: text, name: text.max(120),
  address: z.object({ line1: text, city: text, state: text, postalCode: text }).strict(), confirmedPin: point }).strict();
export const depotPolicyResult = z.object({ success: z.literal(true), effectiveDate: date });
export const dealershipSetup = z.object({ name: text.max(120) }).strict();
export const dealershipDetails = dealershipSetup;
export const depotDetails = z.object({ name: text.max(120), address: depotSetup.shape.address.optional(),
  confirmedPin: point.optional() }).strict().superRefine((value, context) => {
  if ((value.address === undefined) !== (value.confirmedPin === undefined))
    context.addIssue({ code: "custom", message: "Address and confirmed pin must be supplied together" });
});
export const technicianDepotAssignment = z.object({ depotId: text, effectiveDate: date }).strict();
export const offer = z.object({ offerId: text, date, windowStart: instant, windowEnd: instant, expiresAt: instant });
export const appointmentSearch = z.object({
  outcome: z.enum(["AVAILABLE", "SEARCH_INCOMPLETE", "NO_CANDIDATE_FOUND", "ROUTING_UNAVAILABLE", "SCHEDULE_CONFLICT", "SERVICE_BUSY"]),
  prescribedSearchCompleted: z.boolean(), elapsedMs: z.int().nonnegative(), retryable: z.boolean(),
  apiElapsedMs: z.int().nonnegative().optional(),
  queueMs: z.int().nonnegative().optional(),
  measurements: z.object({ databaseExecutions: z.int().nonnegative(), databaseNanos: z.int().nonnegative(), lockStatementNanos: z.int().nonnegative(),
    routingPairs: z.int().nonnegative(), memoryHits: z.int().nonnegative(), persistentHits: z.int().nonnegative(), sharedRoutingPairs: z.int().nonnegative(),
    foregroundCpuNanos: z.int().nonnegative().nullable(), }).nullish(),
}).superRefine((value, context) => {
  if (value.outcome === "NO_CANDIDATE_FOUND" && !value.prescribedSearchCompleted)
    context.addIssue({ code: "custom", message: "An incomplete search cannot establish no candidate found" });
  if (["SEARCH_INCOMPLETE", "ROUTING_UNAVAILABLE", "SCHEDULE_CONFLICT", "SERVICE_BUSY"].includes(value.outcome)
      && (!value.retryable || value.prescribedSearchCompleted))
    context.addIssue({ code: "custom", message: "Failed searches must remain incomplete and retryable" });
});
export const offersResponse = z.object({ jobId: text, offers: z.array(offer), search: appointmentSearch }).superRefine((value, context) => {
  if ((value.search.outcome === "AVAILABLE") !== (value.offers.length > 0))
    context.addIssue({ code: "custom", message: "Search outcome does not match committed offers" });
});
export const durableSearchStart = z.object({ jobId: text, requestId: z.uuid(), refresh: z.boolean() });
export const durableSearchStatus = z.object({ id: z.uuid(), jobId: text,
  state: z.enum(["QUEUED", "RUNNING", "AVAILABLE", "NO_CANDIDATE", "INCOMPLETE", "FAILED", "CANCELLED"]),
  phase: text, elapsedMs: z.int().nonnegative(), queueMs: z.int().nonnegative().nullable(),
  completedWork: z.int().nonnegative(), bestCostDeltaCents: z.int().nullable().optional(), stopReason: text.nullable(), offers: z.array(offer).max(1),
}).refine(v => (v.state === "AVAILABLE") === (v.offers.length > 0), "Invalid search result");
export const savedBookingSearch = z.object({ id: z.uuid(), jobId: text });
export const confirmation = z.object({ appointmentId: text, windowStart: instant, windowEnd: instant });
export const selection = confirmation.extend({ holdId: text, expiresAt: instant });
export const success = z.object({ success: z.boolean() });
export const timeOffResult = z.object({ requestId: text, status: text });
export const routabilityResponse = z.object({ results: z.array(z.object({ id: text, routable: z.boolean() })) });
export const travelBreakdown = z.object({ road_seconds: z.int().nonnegative(), configured_buffer_seconds: finite.nonnegative(),
  rounding_seconds: finite.nonnegative(), modeled_travel_minutes: z.int().nonnegative(), leg_count: z.int().nonnegative(),
}).refine(value => Math.abs(value.road_seconds + value.configured_buffer_seconds + value.rounding_seconds - value.modeled_travel_minutes * 60) < 0.000001,
  "Travel components must equal modeled travel");
const routeSummary = z.object({
  technician_id: text, stop_count: z.int().nonnegative(), route_minutes: finite, drive_minutes: finite,
  waiting_minutes: finite, distance_meters: finite, modeled_cost_cents: z.int().nonnegative(), workload_minutes: finite,
  overtime_minutes: finite, appointment_ids: z.array(text),
  segments: z.array(z.object({ departure: instant, returned_at: instant, appointment_ids: z.array(text) })).nullish(),
  travel_breakdown: travelBreakdown.nullish(),
}).refine(value => value.travel_breakdown == null || value.travel_breakdown.modeled_travel_minutes === value.drive_minutes,
  "Travel breakdown must match reported driving");
const policyMetrics = z.object({ overtimeMinutes: z.int().nonnegative(), costCents: z.int().nonnegative(),
  fairness: z.object({ variance: finite.nonnegative(), maximumUtilization: finite.nonnegative(),
    workloads: z.array(z.object({ technicianId: text, paidMinutes: z.int().nonnegative(),
      regularCapacityMinutes: z.int().positive(), utilization: finite.nonnegative() })) }) });
export const policyAnalysis = z.object({ version: z.enum(["overtime-fairness-v1", "zero-overtime-four-hour-v2"]), before: policyMetrics, after: policyMetrics,
  decision: z.object({ accepted: z.boolean(), reason: text, referenceCostCents: z.int().nonnegative(),
    overtimeTargetMinutes: z.int().nonnegative(), costCeilingCents: z.int().nonnegative() }),
  rules: z.object({ regularWindowThreshold: z.int().nonnegative(), utilizationThreshold: finite.min(0).max(1),
    fairnessAllowance: finite.min(0).max(1), bookingDeadlineMs: z.int().min(1000).max(5000) }), costChangeCents: z.int() });
const legacySolverStatistics = z.object({ variant: text, seed: z.int(), configurationFingerprint: z.string().regex(/^[a-f0-9]{64}$/),
  termination: z.enum(["TERMINATED_EARLY", "STEP_AND_TIME_LIMIT", "STEP_LIMIT", "TIME_LIMIT", "PHASE_COMPLETED"]),
  budgetMs: z.int().positive(), stepLimit: z.int().positive().nullable(), steps: z.int().nonnegative(),
  moveEvaluations: z.int().nonnegative(), scoreCalculations: z.int().nonnegative(), solveMs: z.int().nonnegative(), timeToBestMs: z.int().nonnegative().nullable(),
  format: z.undefined(),
});
const engineProvenance = z.object({ artifact: z.literal("timefold-solver-core"), version: text.nullable(),
  sha256: z.string().regex(/^[a-f0-9]{64}$/).nullable(), unavailableReason: text.nullable(),
}).refine(v => (v.unavailableReason != null) === (v.version == null) && (v.unavailableReason != null) === (v.sha256 == null), "Missing artifact provenance needs a reason");
const solverStatisticsV2 = legacySolverStatistics.extend({ format: z.literal(2),
  termination: z.enum(["TERMINATED_EARLY", "STEP_AND_TIME_LIMIT", "STEP_LIMIT", "TIME_LIMIT", "PHASE_COMPLETED", "SOLVE_RETURNED", "NOT_RUN"]),
  steps: z.int().nonnegative().nullable(), moveEvaluations: z.int().nonnegative().nullable(), scoreCalculations: z.int().nonnegative().nullable(),
  diagnosticsUnavailableReason: text.nullable(), timeToBestUnavailableReason: text.nullable(), terminationBasis: z.enum(["OBSERVED", "INFERRED"]),
  environmentMode: z.enum(["NO_ASSERT", "FULL_ASSERT", "NON_INTRUSIVE_FULL_ASSERT", "PHASE_ASSERT"]),
  instrumentation: z.enum(["NONE", "TIMEFOLD_INTERNAL_2_6_0"]), provenance: engineProvenance,
}).refine(v => [v.steps, v.moveEvaluations, v.scoreCalculations].every(n => (n == null) === (v.diagnosticsUnavailableReason != null)), "Unavailable counters need a reason")
  .refine(v => (v.timeToBestMs == null) === (v.timeToBestUnavailableReason != null), "Unavailable time to best needs a reason")
  .refine(v => (["SOLVE_RETURNED", "TERMINATED_EARLY", "NOT_RUN"].includes(v.termination) ? v.terminationBasis === "OBSERVED"
    : v.terminationBasis === "INFERRED" && v.diagnosticsUnavailableReason == null), "Stopping cause must distinguish inference from observation");
export const solverAnalysis = z.object({ engine: text, configurationXml: text, constructionConfigurationXml: text.nullish(), policy: z.lazy(() => dailyPolicyDiagnostics).nullish(), phases: z.array(z.object({
  name: text, statistics: z.union([solverStatisticsV2, legacySolverStatistics]),
})).min(1) });
export const dailyOutcome = z.object({
  mode: z.enum(["ASSIGNED", "COLD", "PARTIAL", "REPAIR"]), scoreModelVersion: z.literal("bendable-decimal-repair-v2"),
  assignedVisitIds: z.array(text), unassignedVisitIds: z.array(text), complete: z.boolean(),
  assignedWorkFeasible: z.boolean(), scoringMatchesValidation: z.boolean(), policyEligible: z.boolean(),
}).refine(value => new Set([...value.assignedVisitIds, ...value.unassignedVisitIds]).size
  === value.assignedVisitIds.length + value.unassignedVisitIds.length, "Demand identities must occur once")
  .refine(value => value.complete === (value.unassignedVisitIds.length === 0), "Completeness requires coverage")
  .refine(value => !value.scoringMatchesValidation || value.assignedWorkFeasible, "Validated scoring requires feasible served work")
  .refine(value => !value.policyEligible || (value.complete && value.scoringMatchesValidation), "Partial or invalid work is ineligible");
export const dailyPolicyDiagnostics = z.object({ schemaVersion: z.literal(1),
  fairness: z.enum(["SEARCHED", "SKIPPED_REFERENCE_OVERTIME", "SKIPPED_INVALID_REFERENCE", "SKIPPED_NO_SEARCH_ALLOWANCE", "NOT_APPLICABLE_REPAIR"]),
  decision: z.enum(["ACCEPTED", "REJECTED_OVERTIME", "REJECTED_UNRESOLVED_DEMAND", "REJECTED_INFEASIBLE_DEMAND", "REJECTED_POLICY"]),
  referenceOvertimeMinutes: z.int().nonnegative().nullable(), candidate: dailyOutcome, candidateOvertimeMinutes: z.int().nonnegative().nullable(),
}).refine(value => value.fairness !== "SKIPPED_REFERENCE_OVERTIME" || (value.referenceOvertimeMinutes != null && value.referenceOvertimeMinutes > 0), "Overtime skip requires a nonzero reference")
  .refine(value => value.decision !== "ACCEPTED" || (value.candidate.policyEligible && value.candidateOvertimeMinutes === 0), "Accepted diagnostic requires an eligible candidate")
  .refine(value => value.decision !== "REJECTED_OVERTIME" || (value.candidateOvertimeMinutes != null && value.candidateOvertimeMinutes > 0), "Overtime rejection needs measured overtime")
  .refine(value => value.decision !== "REJECTED_UNRESOLVED_DEMAND" || !value.candidate.complete, "Unresolved rejection needs uncovered demand");
export const optimization = z.object({
  run_id: text, metro_id: text, service_date: date, status: text, reason: z.string().nullable(),
  solver_status: text, solve_ms: finite.nonnegative(), routing_identity: text, configuration_version: text,
  cost_model_version: costModelVersion.nullish(),
  score_model_version: z.enum(["hard-medium-soft-decimal-v1", "bendable-decimal-repair-v2"]).nullish(),
  calculation_outcome: dailyOutcome.nullish(),
  fleet_cost_before_cents: z.int().nonnegative().nullish(), fleet_cost_after_cents: z.int().nonnegative().nullish(),
  objective_improvement: z.int(), churn_penalty_minutes: finite, optimized: z.boolean(),
  appointments_moved: z.int().nonnegative(), created_at: instant, applied_at: instant.nullable(), warnings: z.array(z.string()),
  route_summary_before: z.array(routeSummary), route_summary_after: z.array(routeSummary),
  policy_analysis: policyAnalysis.nullish(),
  solver_analysis: solverAnalysis.nullish(),
  changes: z.array(z.object({ appointment_id: text, from_technician_id: text, to_technician_id: text,
    from_sequence: z.int(), to_sequence: z.int(), from_planned_arrival_min: finite, to_planned_arrival_min: finite })),
}).refine(value => value.cost_model_version !== "exact-fleet-half-up-v2"
  || (value.fleet_cost_before_cents != null && value.fleet_cost_after_cents != null), "Current cost model requires recorded fleet costs")
  .refine(value => value.score_model_version !== "bendable-decimal-repair-v2" || value.calculation_outcome != null,
    "Current score model requires calculation coverage and validation");
export const optimizationRuns = z.object({ runs: z.array(optimization) });
export const bookingRequest = z.object({ requestId: text.min(16), firstName: text, lastName: text, email: text,
  phone: text, line1: text, line2: z.string().optional(), city: text, state: text, postalCode: text, serviceCode: text,
  confirmedPin: point.extend({ manuallyConfirmed: z.boolean().optional() }).optional(), followUp: z.boolean().optional(), backgroundSearch: z.boolean().optional() })
  .refine(v => !v.followUp || !v.confirmedPin, "Follow-up cannot include a confirmed pin");
export const bookingAddress = z.object({ line1: text, city: text, state: text, postalCode: text });
export const locationBounds = z.object({ south: finite.min(-90).max(90), north: finite.min(-90).max(90),
  west: finite.min(-180).max(180), east: finite.min(-180).max(180) }).refine(b => b.south <= b.north && b.west <= b.east);
export const serviceAreaCircle = point.extend({ radiusMi: finite.positive() });
export const bookingLocationResponse = z.object({ status: z.enum(["MATCHED", "NEEDS_PLACEMENT", "NO_MATCH"]),
  candidates: z.array(point.extend({ precision: z.enum(["ROOFTOP", "APPROXIMATE"]), bounds: locationBounds.optional() })),
  serviceArea: z.object({ circles: z.array(serviceAreaCircle).min(1), bounds: locationBounds }) });
export const locationValidation = z.object({ status: z.enum(["VALID", "OUTSIDE_COVERAGE", "UNROUTABLE", "ROUTING_UNAVAILABLE"]) });
export const jobRequest = z.object({ jobId: text });
export const refreshRequest = jobRequest.extend({ deadlineEpochMs: z.int().positive().optional() });
export const selectRequest = jobRequest.extend({ offerId: text });
export const confirmRequest = z.object({ holdId: text });
export const previewRequest = z.object({ metroId: text, date });
export const applyRequest = z.object({ runId: text });
export const qualificationRequest = z.object({ technicianId: text, serviceId: text, qualified: z.boolean() });
export const availabilityRequest = z.object({ technicianId: text, date, available: z.boolean(),
  shiftStartMin: minute.nullish(), shiftEndMin: minute.nullish() }).refine(v => !v.available ||
    (v.shiftStartMin != null && v.shiftEndMin != null && v.shiftStartMin < v.shiftEndMin), "Invalid shift hours");
export const deleteAvailabilityRequest = z.object({ technicianId: text, date });
export const technicianColorValue = z.string().regex(/^#[0-9a-fA-F]{6}$/);
export const standardDay = z.object({ dayOfWeek: z.int().min(0).max(6), available: z.boolean(),
  shiftStartMin: minute.nullable(), shiftEndMin: minute.nullable() }).strict().refine(v => v.available
    ? v.shiftStartMin != null && v.shiftEndMin != null && v.shiftStartMin < v.shiftEndMin
    : v.shiftStartMin === null && v.shiftEndMin === null, "Invalid daily hours");
export const standardWeek = z.array(standardDay).length(7).refine(days =>
  new Set(days.map(day => day.dayOfWeek)).size === 7 && days.some(day => day.available), "Choose at least one available day and include each weekday once");
export const technicianPhone = z.string().trim().min(7).max(40).refine(isValidPhone, "Enter a phone number with at least 7 digits");
export const homeAddress = z.object({ line1: text, city: text, state: text, postalCode: text }).strict();
const technicianProfileFields = z.object({ name: text.max(120), email: z.email().nullable().optional(),
  phone: technicianPhone.nullable().optional(), bio: z.string().trim().max(2000).nullable().optional(),
  color: technicianColorValue }).strict();
// Address, pin and confirmation are all supplied together (an address change) or all omitted.
export const technicianProfileRequest = technicianProfileFields.extend({ address: homeAddress.optional(),
  confirmedPin: point.optional(), manuallyConfirmed: z.boolean().optional() }).strict().superRefine((value, context) => {
  const supplied = [value.address, value.confirmedPin, value.manuallyConfirmed].filter(item => item !== undefined).length;
  if (supplied !== 0 && supplied !== 3)
    context.addIssue({ code: "custom", message: "Address, confirmed pin and manual confirmation must be supplied together" });
});
export const createTechnicianRequest = technicianProfileFields.extend({ depotId: text,
  email: z.email(), phone: technicianPhone,
  address: homeAddress,
  confirmedPin: point, manuallyConfirmed: z.boolean(), days: standardWeek, qualifications: z.array(text).min(1).refine(ids => new Set(ids).size === ids.length),
}).strict();
export const updateStandardWeekRequest = z.object({ days: standardWeek }).strict();
export const timeOffCategories = [
  "Vacation / personal travel", "Medical appointment", "Illness", "Family emergency",
  "Bereavement", "Jury duty / civic obligation", "Other",
] as const;
export type TimeOffCategory = (typeof timeOffCategories)[number];
export const timeOffRequest = z.object({ technicianId: text, firstDate: date, lastDate: date,
  startMin: minute, endMin: minute, category: z.enum(timeOffCategories), reason: text }).refine(v => v.firstDate <= v.lastDate && v.startMin < v.endMin, "Invalid absence interval");
export const testRadiusMi = z.union([z.literal(10), z.literal(20), z.literal(30), z.literal(45), z.literal(65)]);
const testConfigFields = { count: z.int().min(1).max(100), policy: z.enum(["earliest", "first", "random"]),
  weights: z.array(finite.min(0).max(100)).length(4).refine(v => v.some(w => w > 0)), radiusMi: testRadiusMi } as const;
// Runs saved before radius selection existed used the full 65-mile metro service area.
export const testConfig = z.object({ ...testConfigFields, seed: z.int().min(0).max(0xffffffff), radiusMi: testRadiusMi.default(65) });
export const testConfigInput = z.object({ ...testConfigFields, seed: z.int().min(0).max(0xffffffff).nullish() });
export const testOperation = z.discriminatedUnion("action", [
  z.object({ id: text, action: z.literal("create"), config: testConfigInput }),
  z.object({ id: text, action: z.enum(["resume", "pause", "stop"]) }),
  z.object({ id: text, action: z.literal("purge") }),
  z.object({ id: text, action: z.literal("advance"), revision: z.int().nonnegative() }),
]);
export const testInput = z.object({ ordinal: z.int().nonnegative(), serviceCode: text, selectionUnit: finite.min(0).lt(1),
  location: point.extend({ slug: text, neighborhood: text, line2: z.string().optional(), line1: text, city: text, state: text, postalCode: text }) });
export function errorMessage(value: unknown, fallback = "Request failed"): string {
  if (value instanceof Error) return value.message;
  const parsed = z.object({ error: text.optional(), detail: text.optional() }).safeParse(value);
  return parsed.success ? parsed.data.error ?? parsed.data.detail ?? fallback : fallback;
}
export async function readBody<T>(request: { json(): Promise<unknown> }, schema: z.ZodType<T>) {
  const value: unknown = await request.json().catch(() => undefined);
  return schema.safeParse(value);
}
export function required<T>(value: T | undefined | null, label = "Required value"): T {
  if (value == null) throw new Error(`${label} is missing`);
  return value;
}

export const bookingResponse = z.object({ jobId: text, offers: z.array(offer).optional(), pendingReference: text.optional(),
  search: appointmentSearch.optional(), searchRequestId: z.uuid().optional(),
  pinRequired: z.boolean().optional(), candidates: z.array(point.extend({ precision: z.enum(["ROOFTOP", "APPROXIMATE"]) })).optional()
}).refine(v => v.pinRequired ? !!v.candidates?.length : v.pendingReference !== undefined || v.searchRequestId !== undefined || offersResponse.safeParse(v).success, "Incomplete booking response");
export const bookingFailure = z.object({ error: text.optional(), pendingReference: text.optional(), offers: z.array(offer).optional(), search: appointmentSearch.optional() });
export const testAttempt = z.object({ at: instant, horizon: z.array(date), elapsedMs: finite.nonnegative(), offers: z.array(offer),
  selected: offer.nullable(), outcome: text, error: z.string().nullable(), search: appointmentSearch.nullish() });
export const testGeneration = z.object({ acceptedCount: z.int().nonnegative(), targetCount: z.int().positive(), candidatesTried: z.int().nonnegative(),
  batches: z.int().nonnegative(), elapsedMs: z.int().nonnegative(), completedAt: instant.nullable(), consecutiveNoProgressBatches: z.int().nonnegative() });
export const runSummary = z.object({ id: text, status: text, createdAt: instant, config: testConfig,
  purgedAt: instant.nullable(), purgedCount: z.int().nonnegative().nullable(), generation: testGeneration.nullable() });
export const testRun = runSummary.extend({ revision: z.int().nonnegative(), error: z.string().nullable(), horizon: z.array(date), currentHorizon: z.array(date),
  requests: z.array(z.object({ id: text, ordinal: z.int().nonnegative(), input: testInput, status: text, offers: z.array(offer),
    selected: offer.nullable(), elapsedMs: finite.nonnegative(), serviceDate: date.nullable(), startedAt: instant.nullable(),
    error: z.string().nullable(), attempts: z.array(testAttempt) })),
  previews: z.array(z.object({ id: text, serviceDate: date, optimizationId: text.nullable(), result: optimization.nullable(), error: z.string().nullable() })),
  applied: z.array(z.object({ id: text, status: text, appliedAt: instant.nullable() })),
});
export const testHistory = z.object({ runs: z.array(runSummary), horizon: z.array(date) });
export async function readResponse<T>(response: Response, schema: z.ZodType<T>): Promise<T> {
  let raw: unknown;
  try {
    raw = await response.json();
  } catch {
    throw new Error(response.ok ? "Invalid response. Reload before continuing." : `Request failed (${response.status})`);
  }
  if (!response.ok) throw new Error(errorMessage(raw, `Request failed (${response.status})`));
  const parsed = schema.safeParse(raw);
  if (!parsed.success) throw new Error("Invalid response. Reload before continuing.");
  return parsed.data;
}
