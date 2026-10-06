package dev.waterflex.scheduler.optimizer;

import org.jspecify.annotations.Nullable;
import dev.waterflex.scheduler.Required;
import dev.waterflex.scheduler.SavedJson;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.waterflex.scheduler.RoadClient;
import dev.waterflex.scheduler.RouteEndpoints;
import dev.waterflex.scheduler.ScheduleCutoff;
import dev.waterflex.scheduler.WeeklyAvailability;
import dev.waterflex.scheduler.SearchAdmission;
import dev.waterflex.scheduler.SearchDeadline;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;

@Service
public class OptimizationService {
    private static final ZoneId CHICAGO = Required.value(ZoneId.of("America/Chicago"));
    private final JdbcTemplate jdbc;
    private final RoadClient roads;
    private final DailySolver solver;
    private final SearchAdmission admission;
    private final org.springframework.transaction.support.TransactionTemplate previewTransactions;
    private final ObjectMapper mapper = new ObjectMapper();

    public record Request(String metro_id, String date, @Nullable String request_key) {
        public Request { metro_id = dev.waterflex.scheduler.RequestChecks.text(metro_id, "metro_id"); date = dev.waterflex.scheduler.RequestChecks.date(date); }
        public Request(String metro_id, String date) { this(metro_id, date, null); }
    }
    public record SegmentSummary(String departure, String returned_at, List<String> appointment_ids) { }
    public record RouteSummary(String technician_id, int stop_count, long route_minutes, long drive_minutes,
            long waiting_minutes, long distance_meters, long modeled_cost_cents, long workload_minutes,
            long overtime_minutes, List<String> appointment_ids, @Nullable List<SegmentSummary> segments,
            @Nullable TravelBreakdown travel_breakdown) { }
    private record Assignment(String appointmentId, String technicianId, int sequence, String plannedStart, String plannedEnd, String windowStart, String windowEnd, double locationLat, double locationLng) { }
    private record ExistingPreview(String id, String metroId, LocalDate day) { }
    private record SavedRun(String metroId, Instant day, String versions, String assignments, String weights, String status) { }
    private record RunResponse(String metroId, Instant day, String status, @Nullable String reason, String solverStatus, int solveMs, long improvement, String before, String after, Instant created, @Nullable Instant applied, String weights) { }
    private record TechData(String id, RouteEndpoints endpoints, TechRoute route) { }
    private record TechBase(String id, RouteEndpoints endpoints, int maxDaily, int maxOvertime) { }
    private record VisitData(PlanVisit visit, RoadClient.Point point, String technicianId, int sequence,
                             Instant windowStart, Instant windowEnd) { }
    private record Problem(DayPlan plan, Map<String, Integer> versions, List<VisitData> visits,
                           String configurationVersion, String routingIdentity, Map<String, RouteEndpoints> endpoints, SchedulingPolicy.Rules policy) {
        Problem withPlan(DayPlan replacement) {
            Map<String, PlanVisit> canonical = new HashMap<>();
            replacement.getVisits().forEach(visit -> canonical.put(visit.getId(), visit));
            List<VisitData> updated = new ArrayList<>();
            for (VisitData visit : visits) updated.add(new VisitData(Required.value(canonical.get(visit.visit().getId()), "replacement visit"), visit.point(), visit.technicianId(), visit.sequence(), visit.windowStart(), visit.windowEnd()));
            return new Problem(replacement, versions, updated, configurationVersion, routingIdentity, endpoints, policy);
        }
    }

    public OptimizationService(JdbcTemplate jdbc, RoadClient roads, DailySolver solver, SearchAdmission admission,
                               org.springframework.transaction.PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc; this.roads = roads; this.solver = solver; this.admission = admission;
        this.previewTransactions = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
    }

    public Map<String, Object> preview(Request request) {
        try (var lease = admission.acquire(SearchAdmission.Kind.BACKGROUND, new SearchDeadline(Required.value(Duration.ofSeconds(20))))) {
            org.slf4j.LoggerFactory.getLogger(OptimizationService.class).debug("Optimization queue time {} ms", lease.queueMillis());
            return Required.value(previewTransactions.execute(_ -> requestedPreview(request)), "optimization preview");
        }
    }

    private Map<String, Object> requestedPreview(Request request) {
        String requestKey = request.request_key();
        if (requestKey == null) return createPreview(request);
        if (requestKey.isBlank() || requestKey.length() > 160)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid preview request key");
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", "preview:" + request.request_key());
        var existing = jdbc.query("SELECT id, \"metroId\", \"serviceDate\" FROM optimization_run WHERE \"requestKey\"=?",
                (rs, _) -> new ExistingPreview(Required.string(rs, 1), Required.string(rs, 2), Required.value(Required.timestamp(rs, 3).toLocalDateTime().toLocalDate())), requestKey);
        if (!existing.isEmpty()) {
            var row = existing.getFirst();
            if (!request.metro_id().equals(row.metroId()) ||
                    !parseDay(request.date()).equals(row.day()))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Preview key belongs to another day");
            return response(row.id());
        }
        var result = createPreview(request);
        jdbc.update("UPDATE optimization_run SET \"requestKey\"=? WHERE id=?", request.request_key(), result.get("run_id"));
        return result;
    }

    private Map<String, Object> createPreview(Request request) {
        LocalDate day = parseDay(request.date());
        if (ScheduleCutoff.frozen(day, Required.value(Instant.now())))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Route is frozen after 6 a.m. local time");
        Problem baseline = build(request.metro_id(), day);
        if (baseline.visits().isEmpty()) return persist(request.metro_id(), day, baseline, baseline.plan(),
                new DayScoreCalculator.Evaluation(0, 0, Required.value(Map.of()), 0, 0, 0, 0, 0),
                new DayScoreCalculator.Evaluation(0, 0, Required.value(Map.of()), 0, 0, 0, 0, 0), 0, "SKIPPED", "No appointments");
        if (hasHolds(Required.value(baseline.versions().keySet()), day)) return skipped(request.metro_id(), day, baseline, "Active hold");
        var before = DayScoreCalculator.evaluate(baseline.plan());
        var validatedBefore = RouteEvaluator.evaluate(baseline.plan());
        if (before.hardPenalty() != 0 || !validatedBefore.feasible() || before.costCents() != validatedBefore.costCents()
                || !before.arrivals().equals(validatedBefore.arrivals()))
            return skipped(request.metro_id(), day, baseline, "Baseline infeasible or scoring mismatch");
        long started = System.nanoTime();
        var referenceSearch = solver.solve(baseline.plan(), Required.value(Duration.ofSeconds(10)));
        List<DailySolver.Phase> phases = new ArrayList<>(); phases.add(new DailySolver.Phase("REFERENCE", referenceSearch.statistics()));
        DayPlan solved = referenceSearch.plan();
        int solveMs = (int) Duration.ofNanos(System.nanoTime() - started).toMillis();
        var after = DayScoreCalculator.evaluate(Required.value(solved));
        var validatedAfter = RouteEvaluator.evaluate(Required.value(solved));
        var baselinePolicy = SchedulingPolicy.measure(baseline.plan());
        boolean candidateValid = after.hardPenalty() == 0 && validatedAfter.feasible()
                && after.costCents() == validatedAfter.costCents() && after.arrivals().equals(validatedAfter.arrivals());
        var candidatePolicy = candidateValid ? SchedulingPolicy.measure(Required.value(solved)) : baselinePolicy;
        var reference = candidatePolicy.overtimeMinutes() < baselinePolicy.overtimeMinutes()
                || (candidatePolicy.overtimeMinutes() == baselinePolicy.overtimeMinutes()
                && candidatePolicy.costCents() < baselinePolicy.costCents()) ? candidatePolicy : baselinePolicy;
        DayPlan referencePlan = reference == candidatePolicy ? Required.value(solved) : baseline.plan();
        if (candidateValid) {
            DayPlan fairnessSeed = PlanCopies.copy(referencePlan);
            fairnessSeed.setScoringFacts(fairnessSeed.getScoringFacts().withTarget(referencePlan, baseline.policy().costCeiling(reference.costCents())));
            long remaining = (Duration.ofSeconds(15).toNanos() - (System.nanoTime() - started)) / 1_000_000;
            if (remaining > 0) {
                var fairnessSearch = solver.solve(fairnessSeed, Required.value(Duration.ofMillis(remaining)));
                phases.add(new DailySolver.Phase("FAIRNESS", fairnessSearch.statistics()));
                DayPlan fair = fairnessSearch.plan();
                var validation = RouteEvaluator.evaluate(fair);
                var fairScore = DayScoreCalculator.evaluate(fair);
                if (validation.feasible() && fairScore.hardPenalty() == 0 && fairScore.costCents() == validation.costCents()
                        && fairScore.arrivals().equals(validation.arrivals())) {
                    var fairMetrics = SchedulingPolicy.measure(fair);
                    if (fairMetrics.overtimeMinutes() == reference.overtimeMinutes()
                            && fairMetrics.costCents() <= baseline.policy().costCeiling(reference.costCents())
                            && (fairMetrics.fairness().variance().compareTo(reference.fairness().variance()) < 0
                            || (fairMetrics.fairness().variance().compareTo(reference.fairness().variance()) == 0
                            && (fairMetrics.costCents() < reference.costCents()
                            || fairMetrics.costCents() == reference.costCents() && SchedulingPolicy.compareArrangements(fair, referencePlan) < 0)))) {
                        solved = fair;
                        candidatePolicy = fairMetrics;
                        after = DayScoreCalculator.evaluate(fair);
                    } else {
                        solved = referencePlan;
                        candidatePolicy = reference;
                        after = DayScoreCalculator.evaluate(referencePlan);
                    }
                }
            }
        }
        solveMs = (int) Duration.ofNanos(System.nanoTime() - started).toMillis();
        var decision = SchedulingPolicy.compare(baselinePolicy, candidatePolicy, reference, baseline.policy());
        boolean acceptable = candidateValid && decision.accepted();
        String status = acceptable ? "PREVIEW" : "SKIPPED";
        return persist(request.metro_id(), day, baseline, acceptable ? Required.value(solved) : baseline.plan(),
                before, acceptable ? after : before, solveMs, status,
                acceptable ? decision.reason() : "No independently validated policy improvement", referencePlan, solver.diagnostics(phases));
    }

    public Map<String, Object> previewRepair(String metroId, LocalDate day, String absentTechnicianId, int startMin, int endMin) {
        try (var lease = admission.acquire(SearchAdmission.Kind.BACKGROUND, new SearchDeadline(Required.value(Duration.ofSeconds(20))))) {
            org.slf4j.LoggerFactory.getLogger(OptimizationService.class).debug("Repair queue time {} ms", lease.queueMillis());
            return createRepair(metroId, day, absentTechnicianId, startMin, endMin);
        }
    }

    private Map<String, Object> createRepair(String metroId, LocalDate day, String absentTechnicianId, int startMin, int endMin) {
        if (ScheduleCutoff.frozen(day, Required.value(Instant.now()))) throw new ResponseStatusException(HttpStatus.CONFLICT, "Frozen date requires CSR coordination");
        if (hasHolds(Required.value(Set.<String>of(absentTechnicianId)), day))
            return Required.value(Map.<String, Object>of("serviceDate", day.toString(), "status", "SKIPPED", "reason", "ACTIVE_RESERVATIONS"));
        if (WeeklyAvailability.resolve(jdbc, absentTechnicianId, day) == null) {
            int appointments = Required.query(jdbc, "SELECT count(*) FROM appointment WHERE \"technicianId\"=? AND \"serviceDate\"=? AND \"cancelledAt\" IS NULL", Integer.class,
                    absentTechnicianId, dayStamp(day));
            return Required.value(Map.<String, Object>of("serviceDate", day.toString(), "status", appointments == 0 ? "NO_SHIFT" : "SKIPPED",
                    "reason", appointments == 0 ? "No technician shift on this date" : "Appointments remain on a date without a technician shift"));
        }
        Problem baseline = build(metroId, day);
        if (hasHolds(Required.value(baseline.versions().keySet()), day)) return skipped(metroId, day, baseline, "ACTIVE_RESERVATIONS");
        var before = DayScoreCalculator.evaluate(baseline.plan());
        if (baseline.plan().getRoutes().stream().noneMatch(route -> route.getId().equals(absentTechnicianId)))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Technician not in metro");
        baseline = baseline.withPlan(PlanCopies.withAbsence(baseline.plan(), absentTechnicianId,
                new TechRoute.Unavailable(localInstant(day, startMin, false), localInstant(day, endMin, true))));
        long started = System.nanoTime();
        var repairSearch = solver.solve(baseline.plan(), Required.value(Duration.ofSeconds(15)));
        DayPlan solved = repairSearch.plan();
        int solveMs = (int) Duration.ofNanos(System.nanoTime() - started).toMillis();
        var after = DayScoreCalculator.evaluate(Required.value(solved));
        var repairValidation = RouteEvaluator.evaluate(Required.value(solved));
        String status = after.hardPenalty() == 0 && repairValidation.feasible() && repairValidation.overtimeMinutes() == 0 ? "REPAIR_PREVIEW" : "SKIPPED";
        String reason = null;
        if (!status.equals("REPAIR_PREVIEW"))
            reason = after.hardPenalty() == 0 ? "VALIDATED_CONSTRAINT_CONFLICT"
                    : individuallyImpossible(baseline.plan()) ? "VALIDATED_CONSTRAINT_CONFLICT" : "SEARCH_BUDGET_EXHAUSTED";
        if (!status.equals("REPAIR_PREVIEW")) {
            DailyOutcome outcome = repairSearch.outcome();
            String diagnosticReason = outcome.complete() ? Required.value(reason) : "UNRESOLVED_DEMAND";
            return Required.value(Map.<String, Object>of("serviceDate", day.toString(), "status", "SKIPPED", "reason", diagnosticReason,
                    "score_model_version", DailyDataset.SCORE_MODEL, "calculation_outcome", outcome,
                    "solver_analysis", solver.diagnostics(Required.value(List.of(new DailySolver.Phase("REPAIR", repairSearch.statistics()))))));
        }
        return persist(metroId, day, baseline, Required.value(solved), before, after, solveMs, status, null, null,
                solver.diagnostics(Required.value(List.<DailySolver.Phase>of(new DailySolver.Phase("REPAIR", repairSearch.statistics())))));
    }

    private boolean individuallyImpossible(DayPlan plan) {
        for (PlanVisit visit : plan.getVisits()) {
            boolean possible = false;
            for (TechRoute route : plan.getRoutes()) {
                if (!route.getQualifiedServiceIds().contains(visit.getServiceId())) continue;
                TechRoute single = new TechRoute(route.getId(), route.getShiftStart(), route.getShiftEnd(),
                        route.getMaxDailyMinutes(), route.getMaxOvertimeMinutes(), route.getQualifiedServiceIds());
                single.setUnavailable(new ArrayList<>(route.getUnavailable()));
                single.getVisits().add(visit);
                DayPlan trial = new DayPlan(Required.value(List.of(single)), Required.value(List.of(visit)), plan.getMatrix(), plan.getRegularHourly(),
                        plan.getOvertimeHourly(), plan.getMileagePerMile(), plan.getTravelBufferPct(), plan.getTravelBufferMinutes());
                if (DayScoreCalculator.evaluate(trial).hardPenalty() == 0) { possible = true; break; }
            }
            if (!possible) return true;
        }
        return false;
    }

    private Map<String, Object> skipped(String metroId, LocalDate day, Problem baseline, String reason) {
        var metrics = DayScoreCalculator.evaluate(baseline.plan());
        return persist(metroId, day, baseline, baseline.plan(), metrics, metrics, 0, "SKIPPED", reason);
    }

    private Map<String, Object> persist(String metroId, LocalDate day, Problem baseline, DayPlan proposal,
                                        DayScoreCalculator.Evaluation before, DayScoreCalculator.Evaluation after,
                                        int solveMs, String status, @Nullable String reason) {
        return persist(metroId, day, baseline, proposal, before, after, solveMs, status, reason, null, null);
    }

    private Map<String, Object> persist(String metroId, LocalDate day, Problem baseline, DayPlan proposal,
                                        DayScoreCalculator.Evaluation before, DayScoreCalculator.Evaluation after,
                                        int solveMs, String status, @Nullable String reason, @Nullable DayPlan referencePlan,
                                        DailySolver.@Nullable Diagnostics diagnostics) {
        String id = UUID.randomUUID().toString();
        List<Assignment> assignments = new ArrayList<>();
        Map<String, VisitData> original = new HashMap<>();
        baseline.visits().forEach(visit -> original.put(visit.visit().getId(), visit));
        for (TechRoute route : proposal.getRoutes()) {
            for (int i = 0; i < route.getVisits().size(); i++) {
                PlanVisit visit = route.getVisits().get(i);
                Instant arrival = Required.value(after.arrivals().get(visit.getId()), "arrival for " + visit.getId());
                VisitData source = original.get(visit.getId());
                VisitData originalVisit = Required.value(source, "original appointment " + visit.getId());
                assignments.add(new Assignment(visit.getId(), route.getId(), i, Required.value(arrival.toString()),
                        Required.value(arrival.plusSeconds(visit.getDurationMinutes() * 60L).toString()),
                        Required.value(visit.getWindowStart().toString()), Required.value(visit.getWindowEnd().toString()),
                        originalVisit.point().lat(), originalVisit.point().lng()));
            }
        }
        long improvement = Math.subtractExact(before.costCents(), after.costCents());
        List<Map<String, Object>> originalAssignments = baseline.visits().stream().map(visit -> Map.<String, Object>of(
                "appointmentId", visit.visit().getId(), "technicianId", visit.technicianId(),
                "sequence", visit.sequence(), "plannedStart", baselineArrival(visit, before).toString(),
                "plannedEnd", baselineArrival(visit, before).plusSeconds(visit.visit().getDurationMinutes() * 60L).toString(),
                "windowStart", visit.windowStart().toString(), "windowEnd", visit.windowEnd().toString(),
                "locationLat", visit.point().lat(), "locationLng", visit.point().lng())).toList();
        try {
            Map<String, Object> provenance = new LinkedHashMap<>();
            provenance.put("mapVersion", baseline.routingIdentity()); provenance.put("configVersion", baseline.configurationVersion());
            provenance.put("policyVersion", SchedulingPolicy.VERSION);
            provenance.put("costModelVersion", dev.waterflex.scheduler.Monetary.COST_MODEL);
            provenance.put("scoreModelVersion", DailyDataset.SCORE_MODEL);
            provenance.put("calculationOutcome", DailyOutcome.assess(proposal));
            provenance.put("fleetCostBeforeCents", before.costCents()); provenance.put("fleetCostAfterCents", after.costCents());
            provenance.put("monetaryRates", Map.of("regularHourly", dev.waterflex.scheduler.Monetary.canonical(baseline.plan().getRegularHourly()),
                    "overtimeHourly", dev.waterflex.scheduler.Monetary.canonical(baseline.plan().getOvertimeHourly()),
                    "mileagePerMile", dev.waterflex.scheduler.Monetary.canonical(baseline.plan().getMileagePerMile())));
            if (diagnostics != null) provenance.put("solverAnalysis", diagnostics);
            jdbc.update("INSERT INTO optimization_run (id, \"metroId\", \"serviceDate\", \"scheduleVersions\", weights, \"solverStatus\", \"solveMs\", \"routeSummaryBefore\", \"routeSummaryAfter\", warnings, \"proposedAssignments\", \"baselineAssignments\", \"endpointSnapshots\", \"objectiveImprovement\", \"churnCost\", status, reason) VALUES (?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?::jsonb, ?::jsonb, ?::jsonb, ?, 0, ?, ?)",
                    id, metroId, dayStamp(day), mapper.writeValueAsString(baseline.versions()),
                    mapper.writeValueAsString(provenance), status, solveMs,
                    mapper.writeValueAsString(summary(baseline.plan(), before)), mapper.writeValueAsString(summary(proposal, after)),
                    "[]", mapper.writeValueAsString(assignments), mapper.writeValueAsString(originalAssignments), mapper.writeValueAsString(baseline.endpoints()), improvement, status, reason);
            if (RouteEvaluator.evaluate(baseline.plan()).feasible() && RouteEvaluator.evaluate(proposal).feasible()) {
                var baselineMetrics = SchedulingPolicy.measure(baseline.plan());
                var proposalMetrics = SchedulingPolicy.measure(proposal);
                var reference = referencePlan != null ? SchedulingPolicy.measure(referencePlan) : proposalMetrics.overtimeMinutes() < baselineMetrics.overtimeMinutes()
                        || (proposalMetrics.overtimeMinutes() == baselineMetrics.overtimeMinutes()
                        && proposalMetrics.costCents() < baselineMetrics.costCents()) ? proposalMetrics : baselineMetrics;
                var decision = SchedulingPolicy.compare(baselineMetrics, proposalMetrics, reference, baseline.policy());
                DayPlan recordedReference = referencePlan != null ? referencePlan : reference == proposalMetrics ? proposal : baseline.plan();
                Map<String, List<String>> referenceRoutes = new LinkedHashMap<>();
                recordedReference.getRoutes().forEach(route -> referenceRoutes.put(route.getId(),
                        route.getVisits().stream().<String>map((PlanVisit visit) -> visit.getId()).toList()));
                jdbc.update("UPDATE optimization_run SET \"policyAnalysis\"=?::jsonb WHERE id=?",
                        mapper.writeValueAsString(Map.of("version", SchedulingPolicy.VERSION, "before", baselineMetrics,
                                "after", proposalMetrics, "decision", decision, "rules", baseline.policy(), "referenceRoutes", referenceRoutes,
                                "costChangeCents", Math.subtractExact(after.costCents(), before.costCents()))), id);
            }
            for (Assignment assignment : assignments) {
                String appointmentId = assignment.appointmentId();
                VisitData source = Required.value(original.get(appointmentId), "original assignment");
                String toTech = assignment.technicianId();
                int toSequence = assignment.sequence();
                Instant planned = Instant.parse(assignment.plannedStart());
                if (source.technicianId().equals(toTech) && source.sequence() == toSequence
                        && Required.value(source.visit().getOriginalPlannedStart(), "saved appointment start").equals(planned)) continue;
                jdbc.update("INSERT INTO optimization_change (id, \"runId\", \"appointmentId\", \"fromTechnicianId\", \"toTechnicianId\", \"fromSequence\", \"toSequence\", \"fromPlannedArrivalMin\", \"toPlannedArrivalMin\") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                        UUID.randomUUID().toString(), id, appointmentId, source.technicianId(), toTech,
                        source.sequence(), toSequence, localMinute(Required.value(source.visit().getOriginalPlannedStart(), "saved appointment start")), localMinute(Required.value(planned)));
            }
        } catch (Exception e) { throw new IllegalStateException("Could not save optimization preview", e); }
        return response(Required.value(id));
    }

    @Transactional
    public Map<String, Object> apply(String runId) {
        return applyInternal(runId, null);
    }

    @Transactional
    public Map<String, Object> applyRepair(String runId, String absentTechnicianId, LocalDate day, int startMin, int endMin, boolean allowAdditionalOvertime) {
        return applyInternal(runId, absentTechnicianId, day, startMin, endMin, allowAdditionalOvertime);
    }

    private Map<String, Object> applyInternal(String runId, @Nullable String absentTechnicianId) {
        return applyInternal(runId, absentTechnicianId, null, 0, 0, false);
    }

    private Map<String, Object> applyInternal(String runId, @Nullable String absentTechnicianId, @Nullable LocalDate repairDay, int startMin, int endMin, boolean allowAdditionalOvertime) {
        var runRows = jdbc.query("SELECT \"metroId\", \"serviceDate\", \"scheduleVersions\"::text, \"proposedAssignments\"::text, weights::text, status FROM optimization_run WHERE id=? FOR UPDATE",
                (rs, _) -> new SavedRun(Required.string(rs, 1), Required.value(Required.timestamp(rs, 2).toInstant()), Required.string(rs, 3), Required.string(rs, 4), Required.string(rs, 5), Required.string(rs, 6)), runId);
        if (runRows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Preview not found");
        SavedRun run = runRows.getFirst();
        if (!run.status().equals(absentTechnicianId == null ? "PREVIEW" : "REPAIR_PREVIEW")) throw new ResponseStatusException(HttpStatus.CONFLICT, "Preview cannot be applied");
        LocalDate day = run.day().atZone(ZoneOffset.UTC).toLocalDate();
        if (ScheduleCutoff.frozen(Required.value(day), Required.value(Instant.now()))) throw new ResponseStatusException(HttpStatus.CONFLICT, "Route is frozen after 6 a.m. local time");
        try {
            SavedJson.currentCostModel(Required.value(mapper.readTree(run.weights())));
            SavedJson.currentScoreModel(Required.value(mapper.readTree(run.weights())));
            JsonNode versionNode = SavedJson.versions(Required.value(mapper.readTree(run.versions())));
            List<String> techIds = new ArrayList<>();
            versionNode.fieldNames().forEachRemaining(techIds::add);
            Collections.sort(techIds);
            for (String techId : techIds) {
                lockDay(Required.value(techId), Required.value(day));
                int current = Required.query(jdbc, "SELECT version FROM schedule_day WHERE \"technicianId\"=? AND \"serviceDate\"=?", Integer.class, techId, dayStamp(Required.value(day)));
                if (current != versionNode.path(techId).asInt()) throw new ResponseStatusException(HttpStatus.CONFLICT, "Schedule changed");
            }
            if (ScheduleCutoff.frozen(Required.value(day), Required.value(Instant.now()))) throw new ResponseStatusException(HttpStatus.CONFLICT, "Route is frozen after 6 a.m. local time");
            if (hasHolds(new HashSet<>(techIds), Required.value(day))) throw new ResponseStatusException(HttpStatus.CONFLICT, "Active hold");
            Problem current = build(run.metroId(), Required.value(day));
            if (!Objects.equals(SavedJson.provenance(Required.value(mapper.readTree(run.weights()))).path("mapVersion").asText(), roads.currentVersion()))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Routing map changed");
            if (!Objects.equals(SavedJson.provenance(Required.value(mapper.readTree(run.weights()))).path("configVersion").asText(), configurationVersion(run.metroId(), Required.value(day))))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Scheduling configuration changed");
            if (absentTechnicianId != null) current = current.withPlan(PlanCopies.withAbsence(current.plan(), absentTechnicianId,
                    new TechRoute.Unavailable(localInstant(Required.value(repairDay, "repair day"), startMin, false), localInstant(Required.value(repairDay, "repair day"), endMin, true))));
            JsonNode assignments = SavedJson.assignments(Required.value(mapper.readTree(run.assignments())));
            Map<String, JsonNode> proposed = new HashMap<>();
            for (JsonNode node : assignments) proposed.put(node.path("appointmentId").asText(), node);
            if (!new HashSet<>(techIds).equals(current.versions().keySet())) throw SavedJson.invalid();
            if (proposed.size() != current.visits().size()) throw new ResponseStatusException(HttpStatus.CONFLICT, "Appointments changed");
            for (TechRoute route : current.plan().getRoutes()) route.getVisits().clear();
            Map<String, TechRoute> routes = new HashMap<>();
            for (TechRoute route : current.plan().getRoutes()) routes.put(route.getId(), route);
            for (VisitData visit : current.visits()) {
                JsonNode target = proposed.get(visit.visit().getId());
                if (target == null || !target.path("windowStart").asText().equals(visit.windowStart().toString())
                        || !target.path("windowEnd").asText().equals(visit.windowEnd().toString()))
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "Promised window changed");
                TechRoute route = routes.get(target.path("technicianId").asText());
                if (route == null) throw new ResponseStatusException(HttpStatus.CONFLICT, "Technician unavailable");
                route.getVisits().add(visit.visit());
            }
            for (TechRoute route : current.plan().getRoutes()) route.getVisits().sort(Comparator.comparingInt(v -> proposed.get(v.getId()).path("sequence").asInt()));
            // A separate evaluator checks fresh road legs after the locked version check.
            var evaluated = RouteEvaluator.evaluate(current.plan());
            if (!evaluated.feasible() || evaluated.overtimeMinutes() != 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "Proposal must be feasible with zero overtime");
            Problem baseline = build(run.metroId(), Required.value(day));
            var baselineMetrics = RouteEvaluator.evaluate(baseline.plan());
            if (absentTechnicianId != null) {
                if (!baselineMetrics.feasible()) throw new ResponseStatusException(HttpStatus.CONFLICT, "Baseline infeasible; a fresh repair is required");
                requireRepairOvertimeApproval(baselineMetrics.overtimeMinutes(), evaluated.overtimeMinutes(), allowAdditionalOvertime);
            }
            if (absentTechnicianId == null) {
                JsonNode provenance = SavedJson.provenance(Required.value(mapper.readTree(run.weights())));
                if (!SchedulingPolicy.VERSION.equals(provenance.path("policyVersion").asText()))
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "A fresh policy preview is required");
                if (!baselineMetrics.feasible()) throw new ResponseStatusException(HttpStatus.CONFLICT, "Baseline infeasible");
                var beforePolicy = SchedulingPolicy.measure(baseline.plan());
                var afterPolicy = SchedulingPolicy.measure(current.plan());
                JsonNode savedPolicy = SavedJson.policyAnalysis(Required.value(mapper.readTree(Required.query(jdbc,
                        "SELECT \"policyAnalysis\"::text FROM optimization_run WHERE id=?", String.class, runId))));
                DayPlan referencePlan = restoreReference(baseline.plan(), Required.value(savedPolicy.path("referenceRoutes")));
                var reference = SchedulingPolicy.measure(referencePlan);
                JsonNode recordedDecision = Required.value(savedPolicy.path("decision"));
                if (reference.costCents() != SavedJson.integer(recordedDecision, "referenceCostCents")
                        || reference.overtimeMinutes() != SavedJson.integer(recordedDecision, "overtimeTargetMinutes")
                        || current.policy().costCeiling(reference.costCents()) != SavedJson.integer(recordedDecision, "costCeilingCents"))
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "Policy reference changed; generate a fresh preview");
                if (!SchedulingPolicy.compare(beforePolicy, afterPolicy, reference, current.policy()).accepted())
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "No policy improvement");
            }
            for (TechRoute route : current.plan().getRoutes()) {
                for (int index = 0; index < route.getVisits().size(); index++) {
                    PlanVisit visit = route.getVisits().get(index);
                    Instant arrival = Required.value(evaluated.arrivals().get(visit.getId()), "arrival for " + visit.getId());
                    jdbc.update("UPDATE appointment SET \"technicianId\"=?, sequence=?, \"plannedStart\"=?, \"plannedEnd\"=?, \"updatedAt\"=CURRENT_TIMESTAMP WHERE id=?",
                            route.getId(), index, stamp(Required.value(arrival)), stamp(Required.value(arrival.plus(Duration.ofMinutes(visit.getDurationMinutes())))), visit.getId());
                }
            }
            for (String techId : techIds) {
                jdbc.update("UPDATE schedule_day SET version=version+1 WHERE \"technicianId\"=? AND \"serviceDate\"=?", techId, dayStamp(Required.value(day)));
                dev.waterflex.scheduler.ScheduleSegments.save(jdbc, Required.value(techId), Required.value(day),
                        Required.value(evaluated.segments().get(techId), "applied working segments"), current.routingIdentity());
            }
            jdbc.update("UPDATE optimization_run SET status='APPLIED', \"appliedAt\"=CURRENT_TIMESTAMP WHERE id=?", runId);
            return response(runId);
        } catch (ResponseStatusException e) { throw e; }
          catch (Exception e) { throw new ResponseStatusException(HttpStatus.CONFLICT, "Invalid saved proposal", e); }
    }

    private static Instant baselineArrival(VisitData visit, DayScoreCalculator.Evaluation before) {
        Instant modeled = before.arrivals().get(visit.visit().getId());
        if (modeled != null) return modeled;
        // An infeasible repair baseline can lack a modeled placement. Preserve its actual saved
        // appointment time for historical display, without claiming that the route is feasible.
        if (before.hardPenalty() > 0) return Required.value(visit.visit().getOriginalPlannedStart(), "saved appointment start");
        throw new IllegalStateException("Feasible baseline is missing an appointment arrival");
    }

    static void requireRepairOvertimeApproval(long before, long after, boolean approved) {
        if (before < 0 || after < 0) throw new IllegalArgumentException("Invalid repair overtime metrics");
        if (after > before && !approved) throw new RepairOvertimeApprovalRequired(after - before);
    }

    private static DayPlan restoreReference(DayPlan baseline, JsonNode savedRoutes) {
        SavedJson.object(savedRoutes);
        DayPlan plan = PlanCopies.copy(baseline);
        Map<String, PlanVisit> visits = new HashMap<>();
        plan.getVisits().forEach(visit -> visits.put(visit.getId(), visit));
        if (savedRoutes.size() != plan.getRoutes().size()) throw SavedJson.invalid();
        for (TechRoute route : plan.getRoutes()) {
            route.getVisits().clear();
            for (JsonNode id : SavedJson.array(Required.value(savedRoutes.path(route.getId())))) {
                if (!id.isTextual() || id.asText().isBlank()) throw SavedJson.invalid();
                PlanVisit visit = Required.value(visits.remove(id.asText()), "reference appointment");
                route.getVisits().add(visit);
                visit.setTechnician(route);
            }
        }
        if (!visits.isEmpty()) throw SavedJson.invalid();
        return plan;
    }

    public Map<String, Object> response(String id) {
        var rows = jdbc.query("SELECT \"metroId\", \"serviceDate\", status, reason, \"solverStatus\", \"solveMs\", \"objectiveImprovement\", \"routeSummaryBefore\"::text, \"routeSummaryAfter\"::text, \"createdAt\", \"appliedAt\", weights::text FROM optimization_run WHERE id=?",
                (rs, _) -> new RunResponse(Required.string(rs, 1), Required.value(Required.timestamp(rs, 2).toInstant()), Required.string(rs, 3), rs.getString(4), Required.string(rs, 5), Required.integer(rs, 6), Required.longValue(rs, 7), Required.string(rs, 8), Required.string(rs, 9), Required.value(Required.timestamp(rs, 10).toInstant()), rs.getTimestamp(11) == null ? null : Required.timestamp(rs, 11).toInstant(), Required.string(rs, 12)), id);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Preview not found");
        RunResponse row = rows.getFirst();
        try {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("run_id", id); value.put("metro_id", row.metroId()); value.put("service_date", row.day().toString().substring(0, 10));
            value.put("status", row.status()); value.put("reason", row.reason()); value.put("solver_status", row.solverStatus()); value.put("solve_ms", row.solveMs());
            value.put("objective_improvement", row.improvement()); value.put("churn_penalty_minutes", 0);
            var changes = jdbc.query("SELECT \"appointmentId\", \"fromTechnicianId\", \"toTechnicianId\", \"fromSequence\", \"toSequence\", \"fromPlannedArrivalMin\", \"toPlannedArrivalMin\" FROM optimization_change WHERE \"runId\"=?",
                    (rs, _) -> Map.of("appointment_id", (Object) Required.string(rs, 1), "from_technician_id", Required.string(rs, 2),
                            "to_technician_id", Required.string(rs, 3), "from_sequence", Required.integer(rs, 4), "to_sequence", Required.integer(rs, 5),
                            "from_planned_arrival_min", Required.integer(rs, 6), "to_planned_arrival_min", Required.integer(rs, 7)), id);
            value.put("optimized", row.status().equals("APPLIED")); value.put("appointments_moved", changes.size());
            value.put("route_summary_before", mapper.treeToValue(SavedJson.summary(Required.value(mapper.readTree(row.before()))), RouteSummary[].class)); value.put("route_summary_after", mapper.treeToValue(SavedJson.summary(Required.value(mapper.readTree(row.after()))), RouteSummary[].class));
            value.put("changes", changes); value.put("warnings", List.of());
            value.put("created_at", row.created()); value.put("applied_at", row.applied());
            JsonNode provenance = SavedJson.provenance(Required.value(mapper.readTree(row.weights())));
            value.put("routing_identity", provenance.path("mapVersion").asText(""));
            value.put("configuration_version", provenance.path("configVersion").asText(""));
            value.put("cost_model_version", provenance.hasNonNull("costModelVersion") ? SavedJson.text(provenance, "costModelVersion") : null);
            value.put("score_model_version", provenance.hasNonNull("scoreModelVersion") ? SavedJson.text(provenance, "scoreModelVersion") : null);
            value.put("calculation_outcome", provenance.hasNonNull("calculationOutcome")
                    ? mapper.treeToValue(SavedJson.dailyOutcome(Required.value(provenance.path("calculationOutcome"))), DailyOutcome.class) : null);
            value.put("fleet_cost_before_cents", provenance.hasNonNull("fleetCostBeforeCents") ? SavedJson.moneyCents(provenance, "fleetCostBeforeCents") : null);
            value.put("fleet_cost_after_cents", provenance.hasNonNull("fleetCostAfterCents") ? SavedJson.moneyCents(provenance, "fleetCostAfterCents") : null);
            value.put("solver_analysis", provenance.hasNonNull("solverAnalysis")
                    ? mapper.treeToValue(SavedJson.solverAnalysis(Required.value(provenance.path("solverAnalysis"))), DailySolver.Diagnostics.class) : null);
            var analysis = jdbc.query("SELECT \"policyAnalysis\"::text FROM optimization_run WHERE id=?",
                    (rs, _) -> rs.getString(1), id);
            String policyJson = analysis.isEmpty() ? null : analysis.getFirst();
            value.put("policy_analysis", policyJson == null ? null : mapper.treeToValue(SavedJson.policyAnalysis(Required.value(mapper.readTree(policyJson))), Object.class));
            return value;
        } catch (ResponseStatusException e) { throw e; }
          catch (Exception e) { throw new ResponseStatusException(HttpStatus.CONFLICT, "Invalid saved preview", e); }
    }

    public Map<String, Object> history(String metroId, String date) {
        LocalDate day = parseDay(date);
        List<Map<String, Object>> runs = jdbc.query("SELECT id FROM optimization_run WHERE \"metroId\"=? AND \"serviceDate\"=? ORDER BY \"createdAt\" DESC LIMIT 20",
                (rs, _) -> response(Required.string(rs, 1)), metroId, dayStamp(day));
        return Required.value(Map.of("runs", runs));
    }

    @Scheduled(cron = "${scheduler.optimizer.cron:0 0 2 * * *}", zone = "America/Chicago")
    public void overnight() {
        var metros = jdbc.query("SELECT id FROM metro", (rs, _) -> Required.string(rs, 1));
        for (String metro : metros) for (LocalDate day : overnightDates(Required.value(Instant.now()))) {
            try { preview(new Request(Required.value(metro), Required.value(day.toString()))); }
            catch (Exception ignored) { /* A failed day remains unchanged and can be retried by dispatch. */ }
        }
    }

    static List<LocalDate> overnightDates(Instant now) {
        List<LocalDate> days = new ArrayList<>();
        LocalDate day = now.atZone(CHICAGO).toLocalDate();
        int weekdays = 0;
        while (weekdays < 10) {
            if (!ScheduleCutoff.frozen(Required.value(day), now)) {
                days.add(Required.value(day));
                if (day.getDayOfWeek().getValue() <= 5) weekdays++;
            }
            day = day.plusDays(1);
        }
        return days;
    }

    private Problem build(String metroId, LocalDate day) {
        List<TechBase> base = jdbc.query("SELECT t.id," + RouteEndpoints.COLUMNS + ",t.\"maxDailyMinutes\",t.\"maxOvertimeMinutes\" FROM technician t" + RouteEndpoints.JOINS + " WHERE p.\"metroId\"=? AND t.active=true ORDER BY t.id",
                (rs, _) -> new TechBase(Required.string(rs, 1), RouteEndpoints.from(rs, 2), Required.integer(rs, 8), Required.integer(rs, 9)), dayStamp(day), dayStamp(day), metroId);
        List<TechData> techs = new ArrayList<>();
        for (TechBase technician : base) {
            WeeklyAvailability.Shift shift = WeeklyAvailability.resolve(jdbc, technician.id(), day);
            if (shift == null) continue;
            Set<String> qualifications = new HashSet<String>(Required.value(jdbc.query("SELECT \"serviceId\" FROM technician_qualification WHERE \"technicianId\"=?", (r, _) -> Required.string(r, 1), technician.id())));
            Instant start = ScheduleCutoff.localMinute(day, shift.start(), false);
            Instant end = ScheduleCutoff.localMinute(day, shift.end(), true);
            techs.add(new TechData(technician.id(), technician.endpoints(),
                    new TechRoute(technician.id(), start, end, technician.maxDaily(), technician.maxOvertime(), qualifications)));
        }
        Map<String, TechData> byId = new HashMap<>();
        for (TechData tech : techs) byId.put(tech.id(), tech);
        jdbc.query("SELECT r.\"technicianId\", i.\"startMin\", i.\"endMin\" FROM time_off_request r JOIN time_off_interval i ON i.\"requestId\"=r.id WHERE r.status='APPROVED' AND i.\"serviceDate\"=?",
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
                    TechData tech = byId.get(Required.string(rs, 1));
                    if (tech != null) tech.route().getUnavailable().add(new TechRoute.Unavailable(localInstant(day, Required.integer(rs, 2), false), localInstant(day, Required.integer(rs, 3), true)));
                }, dayStamp(day));
        List<VisitData> visits = jdbc.query("SELECT a.id, a.\"technicianId\",a.sequence,a.\"windowStart\",a.\"windowEnd\",a.\"plannedStart\",j.\"serviceId\",j.\"durationMin\",ad.lat,ad.lng FROM appointment a JOIN job j ON j.id=a.\"jobId\" JOIN address ad ON ad.id=j.\"addressId\" JOIN technician t ON t.id=a.\"technicianId\" JOIN technician_depot_assignment assignment ON assignment.\"technicianId\"=t.id AND assignment.\"effectiveDate\"=(SELECT max(x.\"effectiveDate\") FROM technician_depot_assignment x WHERE x.\"technicianId\"=t.id AND x.\"effectiveDate\"<=a.\"serviceDate\") JOIN depot p ON p.id=assignment.\"depotId\" WHERE p.\"metroId\"=? AND a.\"serviceDate\"=? AND a.\"cancelledAt\" IS NULL ORDER BY a.\"technicianId\",a.sequence",
                (rs, _) -> {
                    String id = Required.string(rs, 1), techId = Required.string(rs, 2);
                    Instant start = Required.timestamp(rs, 4).toInstant(), end = Required.timestamp(rs, 5).toInstant();
                    return new VisitData(new PlanVisit(id, Required.string(rs, 7), Required.value(start), Required.value(end), Required.integer(rs, 8), techId, Required.value(Required.timestamp(rs, 6).toInstant())),
                            Required.location(rs, 9, 10, HttpStatus.CONFLICT), techId, Required.integer(rs, 3), Required.value(start), Required.value(end));
                }, metroId, dayStamp(day));
        for (VisitData visit : visits) {
            TechData tech = byId.get(visit.technicianId());
            if (tech == null) throw new ResponseStatusException(HttpStatus.CONFLICT, "An assigned technician is unavailable");
            tech.route().getVisits().add(visit.visit());
        }
        Map<String, RoadClient.Point> points = new LinkedHashMap<>();
        techs.forEach(tech -> {
            points.put(tech.id(), tech.endpoints().departure());
            points.put(tech.id() + ":return", tech.endpoints().returnTo());
        });
        visits.forEach(visit -> points.put(visit.visit().getId(), visit.point()));
        Map<String, DayPlan.RoadLeg> matrix = new HashMap<>();
        roads.matrix(points).forEach((pair, leg) -> matrix.put(pair, new DayPlan.RoadLeg(leg.seconds(), leg.meters())));
        // RoadClient.matrix completed every requested pair; its omitted pairs are explicitly unroutable.
        Set<String> unreachable = new HashSet<>();
        for (String from : points.keySet()) for (String to : points.keySet())
            if (!from.equals(to) && !matrix.containsKey(from + ">" + to)) unreachable.add(from + ">" + to);
        Map<String, java.math.BigDecimal> settings = new HashMap<>();
        jdbc.query("SELECT key,value FROM omaha_setting", (org.springframework.jdbc.core.RowCallbackHandler) rs -> settings.put(Required.string(rs, 1), Required.decimal(rs, 2)));
        var rates = dev.waterflex.scheduler.BookingSnapshot.Rates.read(settings);
        DayPlan plan = new DayPlan(Required.value(techs.stream().<TechRoute>map((TechData tech) -> tech.route()).toList()), Required.value(visits.stream().<PlanVisit>map((VisitData visit) -> visit.visit()).toList()), matrix, unreachable,
                rates.regularHourly(), rates.overtimeHourly(), rates.mileagePerMile(), rates.travelBufferPct(), rates.travelBufferMinutes());
        Map<String, Integer> versions = new LinkedHashMap<>();
        for (TechData tech : techs) {
            jdbc.update("INSERT INTO schedule_day (id, \"technicianId\", \"serviceDate\", version) VALUES (?, ?, ?, 0) ON CONFLICT (\"technicianId\", \"serviceDate\") DO NOTHING",
                    UUID.randomUUID().toString(), tech.id(), dayStamp(day));
            versions.put(tech.id(), Required.query(jdbc, "SELECT version FROM schedule_day WHERE \"technicianId\"=? AND \"serviceDate\"=?", Integer.class, tech.id(), dayStamp(day)));
        }
        Map<String, RouteEndpoints> endpoints = new LinkedHashMap<>();
        techs.forEach(tech -> endpoints.put(tech.id(), tech.endpoints()));
        return new Problem(plan, versions, visits, configurationVersion(metroId, day), points.isEmpty() ? roads.activeIdentity() : roads.currentVersion(), endpoints, PolicySettings.read(settings));
    }

    private List<Map<String, Object>> summary(DayPlan plan, DayScoreCalculator.Evaluation metrics) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (TechRoute route : plan.getRoutes()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("technician_id", route.getId()); item.put("stop_count", route.getVisits().size());
            item.put("appointment_ids", route.getVisits().stream().<String>map((PlanVisit visit) -> visit.getId()).toList());
            var routeMetrics = DayScoreCalculator.evaluate(new DayPlan(Required.value(List.of(route)), route.getVisits(), plan.getMatrix(),
                    plan.getRegularHourly(), plan.getOvertimeHourly(), plan.getMileagePerMile(),
                    plan.getTravelBufferPct(), plan.getTravelBufferMinutes()));
            item.put("route_minutes", routeMetrics.paidMinutes()); item.put("drive_minutes", routeMetrics.driveMinutes());
            item.put("waiting_minutes", routeMetrics.waitingMinutes()); item.put("distance_meters", routeMetrics.meters());
            item.put("modeled_cost_cents", routeMetrics.costCents());
            item.put("workload_minutes", routeMetrics.paidMinutes()); item.put("overtime_minutes", routeMetrics.overtimeMinutes());
            var independent = RouteEvaluator.evaluate(new DayPlan(Required.value(List.of(route)), route.getVisits(), plan.getMatrix(),
                    plan.getRegularHourly(), plan.getOvertimeHourly(), plan.getMileagePerMile(), plan.getTravelBufferPct(), plan.getTravelBufferMinutes()));
            if (independent.feasible()) item.put("travel_breakdown", TravelBreakdown.forRoute(plan, route,
                    Required.value(independent.segments().get(route.getId()), "reported working segments")));
            item.put("segments", Required.value(independent.segments().get(route.getId()), "working segments").stream().map(segment ->
                    new SegmentSummary(Required.value(segment.departure().toString()), Required.value(segment.returnedAt().toString()), segment.visitIds())).toList());
            result.add(item);
        }
        return result;
    }
    private boolean hasHolds(Set<String> techIds, LocalDate day) {
        if (techIds.isEmpty()) return false;
        String placeholders = String.join(",", Collections.nCopies(techIds.size(), "?"));
        List<Object> args = new ArrayList<>();
        args.add(dayStamp(day)); args.addAll(techIds);
        return Required.query(jdbc, "SELECT count(*) FROM reservation_obligation WHERE \"serviceDate\"=? AND \"technicianId\" IN (" + placeholders + ") AND \"releasedAt\" IS NULL AND \"expiresAt\">CURRENT_TIMESTAMP",
                Integer.class, Required.value(args.toArray(new @Nullable Object[0]))) > 0;
    }
    private String configurationVersion(String metroId, LocalDate day) {
        StringBuilder raw = new StringBuilder(dev.waterflex.scheduler.Monetary.COST_MODEL).append(';').append(DailyDataset.SCORE_MODEL).append(';');
        jdbc.query("SELECT key, value, \"updatedAt\" FROM omaha_setting ORDER BY key",
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> raw.append(Required.string(rs, 1)).append(':').append(Required.string(rs, 2)).append(':').append(Required.string(rs, 3)).append(';'));
        jdbc.query("SELECT t.id, t.active, t.\"homeLat\", t.\"homeLng\", t.\"shiftStartMin\", t.\"shiftEndMin\", t.\"maxDailyMinutes\", t.\"maxOvertimeMinutes\", p.\"dealershipId\", ep.departure, ep.\"returnTo\", p.id, p.lat, p.lng FROM technician t" + RouteEndpoints.JOINS + " WHERE p.\"metroId\"=? ORDER BY t.id",
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
                    for (int i = 1; i <= 14; i++) raw.append(rs.getString(i)).append(':');
                    raw.append(';');
                }, dayStamp(day), dayStamp(day), metroId);
        jdbc.query("SELECT q.\"technicianId\", q.\"serviceId\" FROM technician_qualification q JOIN technician t ON t.id=q.\"technicianId\" JOIN technician_depot_assignment a ON a.\"technicianId\"=t.id AND a.\"effectiveDate\"=(SELECT max(x.\"effectiveDate\") FROM technician_depot_assignment x WHERE x.\"technicianId\"=t.id AND x.\"effectiveDate\"<=?) JOIN depot p ON p.id=a.\"depotId\" WHERE p.\"metroId\"=? ORDER BY q.\"technicianId\", q.\"serviceId\"",
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> raw.append(Required.string(rs, 1)).append(':').append(Required.string(rs, 2)).append(';'), dayStamp(day), metroId);
        jdbc.query("SELECT o.\"technicianId\", o.available, o.\"shiftStartMin\", o.\"shiftEndMin\" FROM technician_shift_override o JOIN technician t ON t.id=o.\"technicianId\" JOIN technician_depot_assignment a ON a.\"technicianId\"=t.id AND a.\"effectiveDate\"=(SELECT max(x.\"effectiveDate\") FROM technician_depot_assignment x WHERE x.\"technicianId\"=t.id AND x.\"effectiveDate\"<=o.\"serviceDate\") JOIN depot p ON p.id=a.\"depotId\" WHERE p.\"metroId\"=? AND o.\"serviceDate\"=? ORDER BY o.\"technicianId\"",
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> raw.append(Required.string(rs, 1)).append(':').append(Required.string(rs, 2)).append(':').append(rs.getString(3)).append(':').append(rs.getString(4)).append(';'), metroId, dayStamp(day));
        jdbc.query("SELECT t.id, v.\"effectiveDate\", d.\"dayOfWeek\", d.available, d.\"shiftStartMin\", d.\"shiftEndMin\" FROM technician t JOIN technician_depot_assignment a ON a.\"technicianId\"=t.id AND a.\"effectiveDate\"=(SELECT max(x.\"effectiveDate\") FROM technician_depot_assignment x WHERE x.\"technicianId\"=t.id AND x.\"effectiveDate\"<=?) JOIN depot p ON p.id=a.\"depotId\" LEFT JOIN LATERAL (SELECT id, \"effectiveDate\" FROM technician_availability_version WHERE \"technicianId\"=t.id AND \"effectiveDate\"<=? ORDER BY \"effectiveDate\" DESC LIMIT 1) v ON true LEFT JOIN technician_availability_day d ON d.\"versionId\"=v.id AND d.\"dayOfWeek\"=? WHERE p.\"metroId\"=? ORDER BY t.id",
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
                    raw.append(Required.string(rs, 1));
                    for (int i = 2; i <= 6; i++) raw.append(':').append(rs.getString(i));
                    raw.append(';');
                }, dayStamp(day), dayStamp(day), day.getDayOfWeek().getValue() % 7, metroId);
        jdbc.query("SELECT r.\"technicianId\", i.\"startMin\", i.\"endMin\" FROM time_off_request r JOIN time_off_interval i ON i.\"requestId\"=r.id JOIN technician t ON t.id=r.\"technicianId\" JOIN technician_depot_assignment a ON a.\"technicianId\"=t.id AND a.\"effectiveDate\"=(SELECT max(x.\"effectiveDate\") FROM technician_depot_assignment x WHERE x.\"technicianId\"=t.id AND x.\"effectiveDate\"<=i.\"serviceDate\") JOIN depot p ON p.id=a.\"depotId\" WHERE p.\"metroId\"=? AND r.status='APPROVED' AND i.\"serviceDate\"=? ORDER BY r.\"technicianId\", i.\"startMin\"",
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> raw.append(Required.string(rs, 1)).append(':').append(Required.string(rs, 2)).append(':').append(Required.string(rs, 3)).append(';'), metroId, dayStamp(day));
        try { return Required.value(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw.toString().getBytes(StandardCharsets.UTF_8)))); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    private void lockDay(String techId, LocalDate day) {
        Required.query(jdbc, "SELECT version FROM schedule_day WHERE \"technicianId\"=? AND \"serviceDate\"=? FOR UPDATE", Integer.class, techId, dayStamp(day));
    }
    private static LocalDate parseDay(String text) {
        try { return Required.value(LocalDate.parse(text)); }
        catch (Exception e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid date"); }
    }
    private static Timestamp stamp(Instant time) { return Required.value(Timestamp.from(time)); }
    private static int localMinute(Instant time) { LocalTime local = time.atZone(CHICAGO).toLocalTime(); return local.getHour() * 60 + local.getMinute(); }
    private static Timestamp dayStamp(LocalDate day) { return stamp(Required.value(day.atStartOfDay(ZoneOffset.UTC).toInstant())); }
    private static Instant localInstant(LocalDate day, int minute, boolean endBoundary) { return ScheduleCutoff.localMinute(day, minute, endBoundary); }
}
