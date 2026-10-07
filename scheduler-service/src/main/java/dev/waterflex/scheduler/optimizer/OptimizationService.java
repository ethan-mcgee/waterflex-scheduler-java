package dev.waterflex.scheduler.optimizer;

import org.jspecify.annotations.Nullable;
import dev.waterflex.scheduler.Required;
import dev.waterflex.scheduler.SavedJson;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.waterflex.scheduler.RoadClient;
import dev.waterflex.scheduler.RoadPoint;
import dev.waterflex.scheduler.RouteEndpoints;
import dev.waterflex.scheduler.ScheduleCutoff;
import dev.waterflex.scheduler.WeeklyAvailability;
import dev.waterflex.scheduler.SearchAdmission;
import dev.waterflex.scheduler.SearchDeadline;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
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
    private dev.waterflex.scheduler.CalculationTransport transport = new dev.waterflex.scheduler.CalculationTransport("EMBEDDED", "http://127.0.0.1:8002", "");
    @org.springframework.beans.factory.annotation.Autowired
    void transport(dev.waterflex.scheduler.CalculationTransport value) { transport = value; }
    private final SearchAdmission admission;
    private final Clock clock;
    private final org.springframework.transaction.support.TransactionTemplate previewTransactions;
    private final ObjectMapper mapper = jsonMapper();
    private final DailyAttempts attempts;
    private final org.springframework.transaction.support.TransactionTemplate snapshotTransactions;
    private final org.springframework.transaction.support.TransactionTemplate cleanupTransactions;
    private record PreparedResult(String revision, String routing, java.util.function.Supplier<Map<String, Object>> save) { }
    private record Write(String sql, @Nullable Object[] args) { }
    private static final class Stale extends ResponseStatusException {
        private static final long serialVersionUID = 1L;
        Stale() { super(HttpStatus.CONFLICT, "Daily scheduling or routing inputs changed; request a fresh preview with a new key"); }
    }
    // SHARE conflicts with every input writer's ROW EXCLUSIVE lock, including Prisma writes.
    // Keep this fence brief. No routing, search, or proposal preparation happens while it is held.
    private static final List<String> INPUT_TABLES = Required.value(List.of("address", "appointment", "depot", "depot_endpoint_policy",
            "job", "metro", "omaha_setting", "reservation_arrangement", "reservation_dependency",
            "schedule_day", "service_catalog", "slot_hold", "technician", "technician_availability_day",
            "technician_availability_version", "technician_depot_assignment", "technician_qualification", "technician_shift_override",
            "time_off_interval", "time_off_request"));

    static ObjectMapper jsonMapper() {
        var mapper = new ObjectMapper();
        // Preserve the HTTP ISO instant contract when encoding before Spring's response writer runs.
        mapper.registerModule(new com.fasterxml.jackson.databind.module.SimpleModule().addSerializer(Instant.class,
                new com.fasterxml.jackson.databind.JsonSerializer<@org.jspecify.annotations.NonNull Instant>() {
                    @Override public void serialize(@Nullable Instant value, com.fasterxml.jackson.core.@Nullable JsonGenerator output,
                            com.fasterxml.jackson.databind.@Nullable SerializerProvider provider) throws java.io.IOException {
                        Required.value(output, "JSON generator").writeString(Required.value(value, "serialized instant").toString());
                    }
                }));
        return mapper;
    }

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
    private record VisitData(PlanVisit visit, RoadPoint point, String technicianId, int sequence,
                             Instant windowStart, Instant windowEnd) { }
    private record Problem(DayPlan plan, Map<String, Integer> versions, List<VisitData> visits,
                           String configurationVersion, String routingIdentity, Map<String, RouteEndpoints> endpoints, SchedulingPolicy.Rules policy, String revision, boolean held) {
        Problem withPlan(DayPlan replacement) {
            Map<String, PlanVisit> canonical = new HashMap<>();
            replacement.getVisits().forEach(visit -> canonical.put(visit.getId(), visit));
            List<VisitData> updated = new ArrayList<>();
            for (VisitData visit : visits) updated.add(new VisitData(Required.value(canonical.get(visit.visit().getId()), "replacement visit"), visit.point(), visit.technicianId(), visit.sequence(), visit.windowStart(), visit.windowEnd()));
            return new Problem(replacement, versions, updated, configurationVersion, routingIdentity, endpoints, policy, revision, held);
        }
    }

    @org.springframework.beans.factory.annotation.Autowired
    public OptimizationService(JdbcTemplate jdbc, RoadClient roads, DailySolver solver, SearchAdmission admission,
                               org.springframework.transaction.PlatformTransactionManager transactionManager) {
        this(jdbc, roads, solver, admission, transactionManager, Required.value(Clock.systemUTC()));
    }
    OptimizationService(JdbcTemplate jdbc, RoadClient roads, DailySolver solver, SearchAdmission admission,
                               org.springframework.transaction.PlatformTransactionManager transactionManager, Clock clock) {
        this.jdbc = jdbc; this.roads = roads; this.solver = solver; this.admission = admission;
        this.clock = clock;
        this.previewTransactions = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        this.previewTransactions.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.snapshotTransactions = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        this.snapshotTransactions.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.snapshotTransactions.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
        this.cleanupTransactions = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        this.cleanupTransactions.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.cleanupTransactions.setTimeout(2);
        this.attempts = new DailyAttempts(jdbc, mapper);
    }

    public Map<String, Object> preview(Request request) {
        return previewResponse(request, value -> Required.value(value, "daily response"));
    }

    public byte[] previewJson(Request request) {
        return previewResponse(request, value -> {
            try { return Required.value(mapper.writeValueAsBytes(value), "serialized daily response"); }
            catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
                throw new IllegalStateException("Could not serialize daily response", failure);
            }
        });
    }

    private <T> T previewResponse(Request request, java.util.function.Function<Map<String, Object>, T> encoder) {
        LocalDate day = parseDay(request.date());
        return operation(request.request_key(), canonical(Required.value(List.of("DAILY", request.metro_id(), Required.value(day.toString())))), day,
                claim -> createPreview(request, Required.value(claim)), encoder, key -> legacyPreview(request, Required.value(key)));
    }

    <T> T inPreviewTransaction(java.util.function.Supplier<T> work) {
        return inPreviewTransaction(work, () -> { });
    }
    private <T> T inPreviewTransaction(java.util.function.Supplier<T> work, Runnable commitGuard) {
        return Required.value(previewTransactions.execute(_ -> {
            dev.waterflex.scheduler.DatabaseDeadline.apply(jdbc);
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override public void beforeCommit(boolean readOnly) { SearchDeadline.beforeCommit(); commitGuard.run(); SearchDeadline.beforeCommit(); }
                    });
            T result = work.get();
            SearchDeadline.beforeCommit();
            return result;
        }), "optimization preview");
    }

    private String canonical(Object value) {
        try { return DailyAttempts.fingerprint(Required.value(mapper.writeValueAsString(value))); }
        catch (com.fasterxml.jackson.core.JsonProcessingException failure) { throw new IllegalStateException(failure); }
    }

    private <T> T operation(@Nullable String key, String fingerprint, LocalDate day,
            java.util.function.Function<DailyAttempts.Claim, PreparedResult> calculation, java.util.function.Function<Map<String, Object>, T> encoder,
            java.util.function.Function<String, @Nullable Map<String, Object>> legacy) {
        // The claim consumes the original operation allowance before capacity acquisition.
        return DailyOperation.executePrepared(admission, () -> {
            if (key == null && ScheduleCutoff.frozen(day, Required.value(clock.instant())))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Route is frozen after 6 a.m. local time");
            DailyAttempts.Claim claim = inPreviewTransaction(() -> attempts.claim(key, fingerprint, legacy,
                    !ScheduleCutoff.frozen(day, Required.value(clock.instant()))));
            String rejection = claim.rejection();
            if (rejection != null) throw new ResponseStatusException(HttpStatus.CONFLICT, rejection);
            Map<String, Object> reused = claim.reused();
            if (reused != null) return new DailyOperation.Preparation<>(() -> encoder.apply(reused), false, _ -> { });
            return new DailyOperation.Preparation<>(() -> {
                PreparedResult prepared = calculation.apply(claim);
                if (!prepared.routing().equals(roads.activeIdentity())) throw new Stale();
                SearchDeadline.beginCommit();
                return inPreviewTransaction(() -> {
                    fence();
                    validateRevision(day, prepared.revision());
                    attempts.bind(claim, prepared.revision(), prepared.routing());
                    Map<String, Object> result = prepared.save().get();
                    Object run = result.get("run_id");
                    if (run != null && key != null) jdbc.update("UPDATE optimization_run SET \"requestKey\"=? WHERE id=?", key, run);
                    T encoded = encoder.apply(result);
                    attempts.complete(claim, result);
                    return encoded;
                }, () -> {
                    if (ScheduleCutoff.frozen(day, Required.value(clock.instant()))) throw new Stale();
                    dev.waterflex.scheduler.DatabaseDeadline.apply(jdbc); attempts.verifyCommit(claim);
                });
            }, true, failure -> cleanup(claim, Required.value(failure)));
        });
    }

    private void cleanup(DailyAttempts.Claim claim, Throwable failure) {
        // Expired callers cannot prevent retaining the worker's failure. This is cleanup only, never another solve.
        try {
            SearchDeadline.withoutRequestClock(() -> cleanupTransactions.execute(_ -> {
                jdbc.queryForList("SELECT set_config('statement_timeout','2000',true), set_config('lock_timeout','1000',true)");
                attempts.failed(claim, failure instanceof Stale ? "STALE" : failure instanceof SearchDeadline.Expired ? "CANCELLED" : "FAILED",
                        failure instanceof Stale ? "INPUT_REVISION_CHANGED" : failure instanceof SearchDeadline.Expired ? "DEADLINE_OR_CANCELLATION" : "CALCULATION_OR_PERSISTENCE_FAILED");
                return Boolean.TRUE;
            }));
        } catch (RuntimeException cleanupFailure) {
            org.slf4j.LoggerFactory.getLogger(OptimizationService.class).warn("Daily attempt {} failure cleanup failed; expiry remains authoritative", claim.id(), cleanupFailure);
        }
    }

    private @Nullable Map<String, Object> legacyPreview(Request request, String key) {
        var existing = jdbc.query("SELECT id, \"metroId\", \"serviceDate\" FROM optimization_run WHERE \"requestKey\"=?",
                (rs, _) -> new ExistingPreview(dev.waterflex.scheduler.DatabaseFacts.string(rs, 1), dev.waterflex.scheduler.DatabaseFacts.string(rs, 2), Required.value(dev.waterflex.scheduler.DatabaseFacts.timestamp(rs, 3).toLocalDateTime().toLocalDate())), key);
        if (existing.isEmpty()) return null;
        var row = existing.getFirst();
        if (!request.metro_id().equals(row.metroId()) || !parseDay(request.date()).equals(row.day()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Preview key belongs to another day");
        return response(row.id());
    }

    private void fence() {
        dev.waterflex.scheduler.DatabaseDeadline.apply(jdbc);
        jdbc.execute("LOCK TABLE " + String.join(",", INPUT_TABLES) + " IN SHARE MODE");
    }
    private void validateRevision(LocalDate day, String expected) {
        dev.waterflex.scheduler.DatabaseDeadline.apply(jdbc);
        if (ScheduleCutoff.frozen(day, Required.value(clock.instant())) || !expected.equals(inputRevision(day))) throw new Stale();
    }
    private String inputRevision(LocalDate day) {
        // Structured JSON retains nulls and avoids delimiter ambiguity. Day-scoped mutable rows plus global configuration.
        Map<String, String> values = new LinkedHashMap<>();
        for (String table : INPUT_TABLES) {
            dev.waterflex.scheduler.DatabaseDeadline.apply(jdbc);
            String filter = switch (table) {
                case "appointment", "schedule_day", "reservation_arrangement", "reservation_dependency", "reservation_obligation", "slot_hold", "time_off_interval", "technician_shift_override" -> " WHERE x.\"serviceDate\"=?";
                case "job" -> " WHERE x.id IN (SELECT \"jobId\" FROM appointment WHERE \"serviceDate\"=?)";
                case "address" -> " WHERE x.id IN (SELECT j.\"addressId\" FROM job j JOIN appointment a ON a.\"jobId\"=j.id WHERE a.\"serviceDate\"=?)";
                default -> "";
            };
            String sql = "SELECT COALESCE(jsonb_agg(to_jsonb(x) ORDER BY to_jsonb(x)::text),'[]'::jsonb)::text FROM " + table + " x" + filter;
            values.put(table, filter.isEmpty() ? dev.waterflex.scheduler.DatabaseFacts.query(jdbc, sql, String.class) : dev.waterflex.scheduler.DatabaseFacts.query(jdbc, sql, String.class, dayStamp(day)));
        }
        // The read-only UNION view includes services belonging to pending held jobs, not just booked jobs.
        values.put("reservation_obligation", dev.waterflex.scheduler.DatabaseFacts.query(jdbc, "SELECT COALESCE(jsonb_agg(to_jsonb(x) ORDER BY to_jsonb(x)::text),'[]'::jsonb)::text FROM reservation_obligation x WHERE x.\"serviceDate\"=?", String.class, dayStamp(day)));
        return canonical(values);
    }

    private <T> T snapshot(java.util.function.Supplier<T> capture) {
        return Required.value(snapshotTransactions.execute(_ -> {
            dev.waterflex.scheduler.DatabaseDeadline.apply(jdbc);
            T result = capture.get(); SearchDeadline.beforeCommit(); return result;
        }), "daily snapshot");
    }

    private PreparedResult createPreview(Request request, DailyAttempts.Claim claim) {
        SearchDeadline.checkpoint();
        LocalDate day = parseDay(request.date());
        if (ScheduleCutoff.frozen(day, Required.value(clock.instant())))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Route is frozen after 6 a.m. local time");
        Problem baseline = build(request.metro_id(), day, claim);
        if (baseline.visits().isEmpty()) return persist(request.metro_id(), day, baseline, baseline.plan(),
                new DayScoreCalculator.Evaluation(0, 0, Required.value(Map.of()), 0, 0, 0, 0, 0),
                new DayScoreCalculator.Evaluation(0, 0, Required.value(Map.of()), 0, 0, 0, 0, 0), 0, "SKIPPED", "No appointments");
        if (baseline.held()) return skipped(request.metro_id(), day, baseline, "Active hold");
        var before = DayScoreCalculator.evaluate(baseline.plan());
        SearchDeadline.checkpoint();
        var validatedBefore = RouteEvaluator.evaluate(baseline.plan());
        if (before.hardPenalty() != 0 || !validatedBefore.feasible() || before.costCents() != validatedBefore.costCents()
                || !before.arrivals().equals(validatedBefore.arrivals()))
            return skipped(request.metro_id(), day, baseline, "Baseline infeasible or scoring mismatch");
        var calculation = calculate(baseline, claim.id());
        SearchDeadline.checkpoint();
        DayPlan solved = calculation.plan();
        var after = DayScoreCalculator.evaluate(solved);
        boolean acceptable = calculation.accepted();
        return persist(request.metro_id(), day, baseline, acceptable ? solved : baseline.plan(),
                before, acceptable ? after : before, calculation.solveMs(), acceptable ? "PREVIEW" : "SKIPPED",
                calculation.reason(), calculation.reference(), calculation.diagnostics());
    }

    private DailyCalculation.Result calculate(Problem baseline, String snapshotId) {
        Map<String, dev.waterflex.scheduler.RoadPoint> points = new TreeMap<>();
        baseline.endpoints().forEach((id, endpoint) -> { points.put(id,endpoint.departure()); points.put(id+":return",endpoint.returnTo()); });
        baseline.visits().forEach(visit -> points.put(visit.visit().getId(),visit.point()));
        return transport.daily(baseline.plan(),baseline.policy(),solver,points,snapshotId,baseline.revision(),
                baseline.routingIdentity(),baseline.configurationVersion(),baseline.versions());
    }

    public Map<String, Object> previewRepair(String metroId, LocalDate day, String absentTechnicianId, int startMin, int endMin) {
        return previewRepair(metroId, day, absentTechnicianId, startMin, endMin, null);
    }

    public Map<String, Object> previewRepair(String metroId, LocalDate day, String absentTechnicianId, int startMin, int endMin, @Nullable String requestKey) {
        dev.waterflex.scheduler.RequestChecks.text(metroId, "metro_id");
        dev.waterflex.scheduler.RequestChecks.text(absentTechnicianId, "technician_id");
        if (startMin < 0 || endMin > 1440 || startMin >= endMin) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid repair interval");
        return operation(requestKey, canonical(Required.value(List.of("REPAIR", metroId, day.toString(), absentTechnicianId, startMin, endMin))), day,
                claim -> createRepair(metroId, day, absentTechnicianId, startMin, endMin, Required.value(claim)), value -> Required.value(value), _ -> null);
    }
    private record RepairPrelude(boolean held, boolean shift, int appointments, String revision, @Nullable RawProblem raw) { }
    private PreparedResult immediate(String revision, Map<String, Object> result, DailyAttempts.Claim claim) {
        String routing = roads.activeIdentity();
        snapshot(() -> { validateRevision(parseDay(Required.value((String) result.get("serviceDate"))), revision); attempts.bind(claim, revision, routing); return Boolean.TRUE; });
        return new PreparedResult(revision, routing, () -> result);
    }
    private PreparedResult createRepair(String metroId, LocalDate day, String absentTechnicianId, int startMin, int endMin, DailyAttempts.Claim claim) {
        SearchDeadline.checkpoint();
        if (ScheduleCutoff.frozen(day, Required.value(clock.instant()))) throw new ResponseStatusException(HttpStatus.CONFLICT, "Frozen date requires CSR coordination");
        RepairPrelude prelude = snapshot(() -> {
            boolean held = hasHolds(Required.value(Set.<String>of(absentTechnicianId)), day);
            boolean shift = WeeklyAvailability.resolve(jdbc, absentTechnicianId, day) != null;
            int appointments = dev.waterflex.scheduler.DatabaseFacts.query(jdbc, "SELECT count(*) FROM appointment WHERE \"technicianId\"=? AND \"serviceDate\"=? AND \"cancelledAt\" IS NULL", Integer.class, absentTechnicianId, dayStamp(day));
            // One snapshot includes initialization of missing version rows. It cannot invalidate itself.
            RawProblem raw = held || !shift ? null : capture(metroId, day);
            return new RepairPrelude(held, shift, appointments, raw == null ? inputRevision(day) : raw.revision(), raw);
        });
        if (prelude.held()) return immediate(prelude.revision(), Required.value(Map.of("serviceDate", day.toString(), "status", "SKIPPED", "reason", "ACTIVE_RESERVATIONS")), claim);
        if (!prelude.shift()) return immediate(prelude.revision(), Required.value(Map.of("serviceDate", day.toString(), "status", prelude.appointments() == 0 ? "NO_SHIFT" : "SKIPPED",
                "reason", prelude.appointments() == 0 ? "No technician shift on this date" : "Appointments remain on a date without a technician shift")), claim);
        Problem baseline = hydrate(Required.value(prelude.raw(), "repair scheduling snapshot"), day, claim, true);
        if (baseline.held()) return skipped(metroId, day, baseline, "ACTIVE_RESERVATIONS");
        var before = DayScoreCalculator.evaluate(baseline.plan());
        if (baseline.plan().getRoutes().stream().noneMatch(route -> route.getId().equals(absentTechnicianId)))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Technician not in metro");
        baseline = baseline.withPlan(PlanCopies.withAbsence(baseline.plan(), absentTechnicianId,
                new TechRoute.Unavailable(localInstant(day, startMin, false), localInstant(day, endMin, true))));
        var repairSearch = calculate(baseline,claim.id());
        SearchDeadline.checkpoint();
        DayPlan solved = repairSearch.plan();
        int solveMs = repairSearch.solveMs();
        var after = DayScoreCalculator.evaluate(Required.value(solved));
        var repairValidation = RouteEvaluator.evaluate(Required.value(solved));
        String status = after.hardPenalty() == 0 && repairValidation.feasible() && repairValidation.overtimeMinutes() == 0 ? "REPAIR_PREVIEW" : "SKIPPED";
        String reason = null;
        if (!status.equals("REPAIR_PREVIEW"))
            reason = after.hardPenalty() == 0 ? "VALIDATED_CONSTRAINT_CONFLICT"
                    : individuallyImpossible(baseline.plan()) ? "VALIDATED_CONSTRAINT_CONFLICT" : "SEARCH_BUDGET_EXHAUSTED";
        if (!status.equals("REPAIR_PREVIEW")) {
            DailyOutcome outcome = DailyOutcome.assess(solved);
            String diagnosticReason = outcome.complete() ? Required.value(reason) : "UNRESOLVED_DEMAND";
            return immediate(baseline.revision(), Required.value(Map.<String, Object>of("serviceDate", day.toString(), "status", "SKIPPED", "reason", diagnosticReason,
                    "score_model_version", DailyDataset.SCORE_MODEL, "calculation_outcome", outcome,
                    "solver_analysis", repairSearch.diagnostics())), claim);
        }
        return persist(metroId, day, baseline, Required.value(solved), before, after, solveMs, status, null, null,
                repairSearch.diagnostics());
    }

    private boolean individuallyImpossible(DayPlan plan) {
        for (PlanVisit visit : plan.getVisits()) {
            SearchDeadline.checkpoint();
            boolean possible = false;
            for (TechRoute route : plan.getRoutes()) {
                SearchDeadline.checkpoint();
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

    private PreparedResult skipped(String metroId, LocalDate day, Problem baseline, String reason) {
        var metrics = DayScoreCalculator.evaluate(baseline.plan());
        return persist(metroId, day, baseline, baseline.plan(), metrics, metrics, 0, "SKIPPED", reason);
    }

    private PreparedResult persist(String metroId, LocalDate day, Problem baseline, DayPlan proposal,
                                        DayScoreCalculator.Evaluation before, DayScoreCalculator.Evaluation after,
                                        int solveMs, String status, @Nullable String reason) {
        return persist(metroId, day, baseline, proposal, before, after, solveMs, status, reason, null, null);
    }

    private PreparedResult persist(String metroId, LocalDate day, Problem baseline, DayPlan proposal,
                                        DayScoreCalculator.Evaluation before, DayScoreCalculator.Evaluation after,
                                        int solveMs, String status, @Nullable String reason, @Nullable DayPlan referencePlan,
                                        DailySolver.@Nullable Diagnostics diagnostics) {
        SearchDeadline.checkpoint();
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
        List<Write> writes = new ArrayList<>();
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
            writes.add(new Write("INSERT INTO optimization_run (id, \"metroId\", \"serviceDate\", \"scheduleVersions\", weights, \"solverStatus\", \"solveMs\", \"routeSummaryBefore\", \"routeSummaryAfter\", warnings, \"proposedAssignments\", \"baselineAssignments\", \"endpointSnapshots\", \"objectiveImprovement\", \"churnCost\", status, reason) VALUES (?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?::jsonb, ?::jsonb, ?::jsonb, ?, 0, ?, ?)", new @Nullable Object[] {
                    id, metroId, dayStamp(day), mapper.writeValueAsString(baseline.versions()),
                    mapper.writeValueAsString(provenance), status, solveMs,
                    mapper.writeValueAsString(summary(baseline.plan(), before)), mapper.writeValueAsString(summary(proposal, after)),
                    "[]", mapper.writeValueAsString(assignments), mapper.writeValueAsString(originalAssignments), mapper.writeValueAsString(baseline.endpoints()), improvement, status, reason}));
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
                writes.add(new Write("UPDATE optimization_run SET \"policyAnalysis\"=?::jsonb WHERE id=?", new @Nullable Object[] {
                        mapper.writeValueAsString(Map.of("version", SchedulingPolicy.VERSION, "before", baselineMetrics,
                                "after", proposalMetrics, "decision", decision, "rules", baseline.policy(), "referenceRoutes", referenceRoutes,
                                "costChangeCents", Math.subtractExact(after.costCents(), before.costCents()))), id}));
            }
            for (Assignment assignment : assignments) {
                String appointmentId = assignment.appointmentId();
                VisitData source = Required.value(original.get(appointmentId), "original assignment");
                String toTech = assignment.technicianId();
                int toSequence = assignment.sequence();
                Instant planned = Instant.parse(assignment.plannedStart());
                if (source.technicianId().equals(toTech) && source.sequence() == toSequence
                        && Required.value(source.visit().getOriginalPlannedStart(), "saved appointment start").equals(planned)) continue;
                writes.add(new Write("INSERT INTO optimization_change (id, \"runId\", \"appointmentId\", \"fromTechnicianId\", \"toTechnicianId\", \"fromSequence\", \"toSequence\", \"fromPlannedArrivalMin\", \"toPlannedArrivalMin\") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)", new @Nullable Object[] {
                        UUID.randomUUID().toString(), id, appointmentId, source.technicianId(), toTech,
                        source.sequence(), toSequence, localMinute(Required.value(source.visit().getOriginalPlannedStart(), "saved appointment start")), localMinute(Required.value(planned))}));
            }
        } catch (SearchDeadline.Expired expired) { throw expired; }
        catch (Exception e) { throw new IllegalStateException("Could not save optimization preview", e); }
        return new PreparedResult(baseline.revision(), baseline.routingIdentity(), () -> {
            for (Write write : writes) { dev.waterflex.scheduler.DatabaseDeadline.apply(jdbc); jdbc.update(write.sql(), write.args()); }
            return response(Required.value(id));
        });
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
                (rs, _) -> new SavedRun(dev.waterflex.scheduler.DatabaseFacts.string(rs, 1), Required.value(dev.waterflex.scheduler.DatabaseFacts.timestamp(rs, 2).toInstant()), dev.waterflex.scheduler.DatabaseFacts.string(rs, 3), dev.waterflex.scheduler.DatabaseFacts.string(rs, 4), dev.waterflex.scheduler.DatabaseFacts.string(rs, 5), dev.waterflex.scheduler.DatabaseFacts.string(rs, 6)), runId);
        if (runRows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Preview not found");
        SavedRun run = runRows.getFirst();
        if (!run.status().equals(absentTechnicianId == null ? "PREVIEW" : "REPAIR_PREVIEW")) throw new ResponseStatusException(HttpStatus.CONFLICT, "Preview cannot be applied");
        LocalDate day = run.day().atZone(ZoneOffset.UTC).toLocalDate();
        if (ScheduleCutoff.frozen(Required.value(day), Required.value(clock.instant()))) throw new ResponseStatusException(HttpStatus.CONFLICT, "Route is frozen after 6 a.m. local time");
        try {
            SavedJson.currentCostModel(Required.value(mapper.readTree(run.weights())));
            SavedJson.currentScoreModel(Required.value(mapper.readTree(run.weights())));
            JsonNode versionNode = SavedJson.versions(Required.value(mapper.readTree(run.versions())));
            List<String> techIds = new ArrayList<>();
            versionNode.fieldNames().forEachRemaining(techIds::add);
            Collections.sort(techIds);
            for (String techId : techIds) {
                lockDay(Required.value(techId), Required.value(day));
                int current = dev.waterflex.scheduler.DatabaseFacts.query(jdbc, "SELECT version FROM schedule_day WHERE \"technicianId\"=? AND \"serviceDate\"=?", Integer.class, techId, dayStamp(Required.value(day)));
                if (current != versionNode.path(techId).asInt()) throw new ResponseStatusException(HttpStatus.CONFLICT, "Schedule changed");
            }
            if (ScheduleCutoff.frozen(Required.value(day), Required.value(clock.instant()))) throw new ResponseStatusException(HttpStatus.CONFLICT, "Route is frozen after 6 a.m. local time");
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
                JsonNode savedPolicy = SavedJson.policyAnalysis(Required.value(mapper.readTree(dev.waterflex.scheduler.DatabaseFacts.query(jdbc,
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
                (rs, _) -> new RunResponse(dev.waterflex.scheduler.DatabaseFacts.string(rs, 1), Required.value(dev.waterflex.scheduler.DatabaseFacts.timestamp(rs, 2).toInstant()), dev.waterflex.scheduler.DatabaseFacts.string(rs, 3), rs.getString(4), dev.waterflex.scheduler.DatabaseFacts.string(rs, 5), dev.waterflex.scheduler.DatabaseFacts.integer(rs, 6), dev.waterflex.scheduler.DatabaseFacts.longValue(rs, 7), dev.waterflex.scheduler.DatabaseFacts.string(rs, 8), dev.waterflex.scheduler.DatabaseFacts.string(rs, 9), Required.value(dev.waterflex.scheduler.DatabaseFacts.timestamp(rs, 10).toInstant()), rs.getTimestamp(11) == null ? null : dev.waterflex.scheduler.DatabaseFacts.timestamp(rs, 11).toInstant(), dev.waterflex.scheduler.DatabaseFacts.string(rs, 12)), id);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Preview not found");
        RunResponse row = rows.getFirst();
        try {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("run_id", id); value.put("metro_id", row.metroId()); value.put("service_date", row.day().toString().substring(0, 10));
            value.put("status", row.status()); value.put("reason", row.reason()); value.put("solver_status", row.solverStatus()); value.put("solve_ms", row.solveMs());
            value.put("objective_improvement", row.improvement()); value.put("churn_penalty_minutes", 0);
            var changes = jdbc.query("SELECT \"appointmentId\", \"fromTechnicianId\", \"toTechnicianId\", \"fromSequence\", \"toSequence\", \"fromPlannedArrivalMin\", \"toPlannedArrivalMin\" FROM optimization_change WHERE \"runId\"=?",
                    (rs, _) -> Map.of("appointment_id", (Object) dev.waterflex.scheduler.DatabaseFacts.string(rs, 1), "from_technician_id", dev.waterflex.scheduler.DatabaseFacts.string(rs, 2),
                            "to_technician_id", dev.waterflex.scheduler.DatabaseFacts.string(rs, 3), "from_sequence", dev.waterflex.scheduler.DatabaseFacts.integer(rs, 4), "to_sequence", dev.waterflex.scheduler.DatabaseFacts.integer(rs, 5),
                            "from_planned_arrival_min", dev.waterflex.scheduler.DatabaseFacts.integer(rs, 6), "to_planned_arrival_min", dev.waterflex.scheduler.DatabaseFacts.integer(rs, 7)), id);
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
        } catch (SearchDeadline.Expired expired) { throw expired; }
          catch (ResponseStatusException e) { throw e; }
          catch (Exception e) { throw new ResponseStatusException(HttpStatus.CONFLICT, "Invalid saved preview", e); }
    }

    public Map<String, Object> history(String metroId, String date) {
        LocalDate day = parseDay(date);
        List<Map<String, Object>> runs = jdbc.query("SELECT id FROM optimization_run WHERE \"metroId\"=? AND \"serviceDate\"=? ORDER BY \"createdAt\" DESC LIMIT 20",
                (rs, _) -> response(dev.waterflex.scheduler.DatabaseFacts.string(rs, 1)), metroId, dayStamp(day));
        return Required.value(Map.of("runs", runs));
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

    private record RawProblem(List<TechData> techs, List<VisitData> visits, Map<String, RoadPoint> points,
            dev.waterflex.scheduler.BookingSnapshot.Rates rates, Map<String, Integer> versions, Map<String, RouteEndpoints> endpoints,
            SchedulingPolicy.Rules policy, String configuration, String revision, boolean held) { }
    private Problem build(String metroId, LocalDate day) {
        return build(metroId, day, null);
    }
    private Problem build(String metroId, LocalDate day, DailyAttempts.@Nullable Claim claim) {
        boolean daily = DailyOperation.current() != null;
        RawProblem raw = daily ? snapshot(() -> capture(metroId, day)) : capture(metroId, day);
        return hydrate(raw, day, claim, daily);
    }
    private Problem hydrate(RawProblem raw, LocalDate day, DailyAttempts.@Nullable Claim claim, boolean daily) {
        var techs = raw.techs(); var visits = raw.visits(); var points = raw.points(); var rates = raw.rates();
        // Bind this request's matrix explicitly; another caller may change RoadClient's cached identity.
        @Nullable String routing = daily ? roads.activeIdentity() : null;
        Map<String, DayPlan.RoadLeg> matrix = new HashMap<>();
        SearchDeadline.checkpoint();
        (daily ? roads.matrix(points, Required.value(routing)) : roads.matrix(points)).forEach((pair, leg) -> matrix.put(pair, new DayPlan.RoadLeg(leg.seconds(), leg.meters())));
        // RoadClient.matrix completed every requested pair; its omitted pairs are explicitly unroutable.
        Set<String> unreachable = new HashSet<>();
        for (String from : points.keySet()) for (String to : points.keySet())
            if (!from.equals(to) && !matrix.containsKey(from + ">" + to)) unreachable.add(from + ">" + to);
        DayPlan plan = new DayPlan(Required.value(techs.stream().<TechRoute>map((TechData tech) -> tech.route()).toList()), Required.value(visits.stream().<PlanVisit>map((VisitData visit) -> visit.visit()).toList()), matrix, unreachable,
                rates.regularHourly(), rates.overtimeHourly(), rates.mileagePerMile(), rates.travelBufferPct(), rates.travelBufferMinutes());
        if (!daily) routing = points.isEmpty() ? roads.activeIdentity() : roads.currentVersion();
        String capturedRouting = Required.value(routing, "captured routing identity");
        if (daily) {
            if (!capturedRouting.equals(roads.activeIdentity())) throw new Stale();
            snapshot(() -> { validateRevision(day, raw.revision()); if (claim != null) attempts.bind(claim, raw.revision(), capturedRouting); return Boolean.TRUE; });
        }
        return new Problem(plan, raw.versions(), visits, raw.configuration(), capturedRouting, raw.endpoints(), raw.policy(), raw.revision(), raw.held());
    }

    private RawProblem capture(String metroId, LocalDate day) {
        dev.waterflex.scheduler.DatabaseDeadline.apply(jdbc);
        List<TechBase> base = jdbc.query("SELECT t.id," + RouteEndpoints.COLUMNS + ",t.\"maxDailyMinutes\",t.\"maxOvertimeMinutes\" FROM technician t" + RouteEndpoints.JOINS + " WHERE p.\"metroId\"=? AND t.active=true ORDER BY t.id",
                (rs, _) -> new TechBase(dev.waterflex.scheduler.DatabaseFacts.string(rs, 1), RouteEndpoints.from(rs, 2), dev.waterflex.scheduler.DatabaseFacts.integer(rs, 8), dev.waterflex.scheduler.DatabaseFacts.integer(rs, 9)), dayStamp(day), dayStamp(day), metroId);
        List<TechData> techs = new ArrayList<>();
        for (TechBase technician : base) {
            dev.waterflex.scheduler.DatabaseDeadline.apply(jdbc);
            WeeklyAvailability.Shift shift = WeeklyAvailability.resolve(jdbc, technician.id(), day);
            if (shift == null) continue;
            Set<String> qualifications = new HashSet<String>(Required.value(jdbc.query("SELECT \"serviceId\" FROM technician_qualification WHERE \"technicianId\"=?", (r, _) -> dev.waterflex.scheduler.DatabaseFacts.string(r, 1), technician.id())));
            Instant start = ScheduleCutoff.localMinute(day, shift.start(), false);
            Instant end = ScheduleCutoff.localMinute(day, shift.end(), true);
            techs.add(new TechData(technician.id(), technician.endpoints(),
                    new TechRoute(technician.id(), start, end, technician.maxDaily(), technician.maxOvertime(), qualifications)));
        }
        Map<String, TechData> byId = new HashMap<>();
        for (TechData tech : techs) byId.put(tech.id(), tech);
        jdbc.query("SELECT r.\"technicianId\", i.\"startMin\", i.\"endMin\" FROM time_off_request r JOIN time_off_interval i ON i.\"requestId\"=r.id WHERE r.status='APPROVED' AND i.\"serviceDate\"=?",
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
                    TechData tech = byId.get(dev.waterflex.scheduler.DatabaseFacts.string(rs, 1));
                    if (tech != null) tech.route().getUnavailable().add(new TechRoute.Unavailable(localInstant(day, dev.waterflex.scheduler.DatabaseFacts.integer(rs, 2), false), localInstant(day, dev.waterflex.scheduler.DatabaseFacts.integer(rs, 3), true)));
                }, dayStamp(day));
        List<VisitData> visits = jdbc.query("SELECT a.id, a.\"technicianId\",a.sequence,a.\"windowStart\",a.\"windowEnd\",a.\"plannedStart\",j.\"serviceId\",j.\"durationMin\",ad.lat,ad.lng FROM appointment a JOIN job j ON j.id=a.\"jobId\" JOIN address ad ON ad.id=j.\"addressId\" JOIN technician t ON t.id=a.\"technicianId\" JOIN technician_depot_assignment assignment ON assignment.\"technicianId\"=t.id AND assignment.\"effectiveDate\"=(SELECT max(x.\"effectiveDate\") FROM technician_depot_assignment x WHERE x.\"technicianId\"=t.id AND x.\"effectiveDate\"<=a.\"serviceDate\") JOIN depot p ON p.id=assignment.\"depotId\" WHERE p.\"metroId\"=? AND a.\"serviceDate\"=? AND a.\"cancelledAt\" IS NULL ORDER BY a.\"technicianId\",a.sequence",
                (rs, _) -> {
                    String id = dev.waterflex.scheduler.DatabaseFacts.string(rs, 1), techId = dev.waterflex.scheduler.DatabaseFacts.string(rs, 2);
                    Instant start = dev.waterflex.scheduler.DatabaseFacts.timestamp(rs, 4).toInstant(), end = dev.waterflex.scheduler.DatabaseFacts.timestamp(rs, 5).toInstant();
                    return new VisitData(new PlanVisit(id, dev.waterflex.scheduler.DatabaseFacts.string(rs, 7), Required.value(start), Required.value(end), dev.waterflex.scheduler.DatabaseFacts.integer(rs, 8), techId, Required.value(dev.waterflex.scheduler.DatabaseFacts.timestamp(rs, 6).toInstant())),
                            dev.waterflex.scheduler.DatabaseFacts.location(rs, 9, 10, HttpStatus.CONFLICT), techId, dev.waterflex.scheduler.DatabaseFacts.integer(rs, 3), Required.value(start), Required.value(end));
                }, metroId, dayStamp(day));
        for (VisitData visit : visits) {
            TechData tech = byId.get(visit.technicianId());
            if (tech == null) throw new ResponseStatusException(HttpStatus.CONFLICT, "An assigned technician is unavailable");
            tech.route().getVisits().add(visit.visit());
        }
        Map<String, RoadPoint> points = new LinkedHashMap<>();
        techs.forEach(tech -> {
            points.put(tech.id(), tech.endpoints().departure());
            points.put(tech.id() + ":return", tech.endpoints().returnTo());
        });
        visits.forEach(visit -> points.put(visit.visit().getId(), visit.point()));
        Map<String, java.math.BigDecimal> settings = new HashMap<>();
        jdbc.query("SELECT key,value FROM omaha_setting", (org.springframework.jdbc.core.RowCallbackHandler) rs -> settings.put(dev.waterflex.scheduler.DatabaseFacts.string(rs, 1), dev.waterflex.scheduler.DatabaseFacts.decimal(rs, 2)));
        var rates = dev.waterflex.scheduler.BookingSnapshot.Rates.read(settings);
        Map<String, Integer> versions = new LinkedHashMap<>();
        for (TechData tech : techs) {
            dev.waterflex.scheduler.DatabaseDeadline.apply(jdbc);
            jdbc.update("INSERT INTO schedule_day (id, \"technicianId\", \"serviceDate\", version) VALUES (?, ?, ?, 0) ON CONFLICT (\"technicianId\", \"serviceDate\") DO NOTHING",
                    UUID.randomUUID().toString(), tech.id(), dayStamp(day));
            versions.put(tech.id(), dev.waterflex.scheduler.DatabaseFacts.query(jdbc, "SELECT version FROM schedule_day WHERE \"technicianId\"=? AND \"serviceDate\"=?", Integer.class, tech.id(), dayStamp(day)));
        }
        Map<String, RouteEndpoints> endpoints = new LinkedHashMap<>();
        techs.forEach(tech -> endpoints.put(tech.id(), tech.endpoints()));
        return new RawProblem(techs, visits, points, rates, versions, endpoints, PolicySettings.read(settings), configurationVersion(metroId, day), inputRevision(day), hasHolds(Required.value(versions.keySet()), day));
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
        return dev.waterflex.scheduler.DatabaseFacts.query(jdbc, "SELECT count(*) FROM reservation_obligation WHERE \"serviceDate\"=? AND \"technicianId\" IN (" + placeholders + ") AND \"releasedAt\" IS NULL AND \"expiresAt\">clock_timestamp()",
                Integer.class, Required.value(args.toArray(new @Nullable Object[0]))) > 0;
    }
    private String configurationVersion(String metroId, LocalDate day) {
        StringBuilder raw = new StringBuilder(dev.waterflex.scheduler.Monetary.COST_MODEL).append(';').append(DailyDataset.SCORE_MODEL).append(';');
        jdbc.query("SELECT key, value, \"updatedAt\" FROM omaha_setting ORDER BY key",
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> raw.append(dev.waterflex.scheduler.DatabaseFacts.string(rs, 1)).append(':').append(dev.waterflex.scheduler.DatabaseFacts.string(rs, 2)).append(':').append(dev.waterflex.scheduler.DatabaseFacts.string(rs, 3)).append(';'));
        jdbc.query("SELECT t.id, t.active, t.\"homeLat\", t.\"homeLng\", t.\"shiftStartMin\", t.\"shiftEndMin\", t.\"maxDailyMinutes\", t.\"maxOvertimeMinutes\", p.\"dealershipId\", ep.departure, ep.\"returnTo\", p.id, p.lat, p.lng FROM technician t" + RouteEndpoints.JOINS + " WHERE p.\"metroId\"=? ORDER BY t.id",
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
                    for (int i = 1; i <= 14; i++) raw.append(rs.getString(i)).append(':');
                    raw.append(';');
                }, dayStamp(day), dayStamp(day), metroId);
        jdbc.query("SELECT q.\"technicianId\", q.\"serviceId\" FROM technician_qualification q JOIN technician t ON t.id=q.\"technicianId\" JOIN technician_depot_assignment a ON a.\"technicianId\"=t.id AND a.\"effectiveDate\"=(SELECT max(x.\"effectiveDate\") FROM technician_depot_assignment x WHERE x.\"technicianId\"=t.id AND x.\"effectiveDate\"<=?) JOIN depot p ON p.id=a.\"depotId\" WHERE p.\"metroId\"=? ORDER BY q.\"technicianId\", q.\"serviceId\"",
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> raw.append(dev.waterflex.scheduler.DatabaseFacts.string(rs, 1)).append(':').append(dev.waterflex.scheduler.DatabaseFacts.string(rs, 2)).append(';'), dayStamp(day), metroId);
        jdbc.query("SELECT o.\"technicianId\", o.available, o.\"shiftStartMin\", o.\"shiftEndMin\" FROM technician_shift_override o JOIN technician t ON t.id=o.\"technicianId\" JOIN technician_depot_assignment a ON a.\"technicianId\"=t.id AND a.\"effectiveDate\"=(SELECT max(x.\"effectiveDate\") FROM technician_depot_assignment x WHERE x.\"technicianId\"=t.id AND x.\"effectiveDate\"<=o.\"serviceDate\") JOIN depot p ON p.id=a.\"depotId\" WHERE p.\"metroId\"=? AND o.\"serviceDate\"=? ORDER BY o.\"technicianId\"",
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> raw.append(dev.waterflex.scheduler.DatabaseFacts.string(rs, 1)).append(':').append(dev.waterflex.scheduler.DatabaseFacts.string(rs, 2)).append(':').append(rs.getString(3)).append(':').append(rs.getString(4)).append(';'), metroId, dayStamp(day));
        jdbc.query("SELECT t.id, v.\"effectiveDate\", d.\"dayOfWeek\", d.available, d.\"shiftStartMin\", d.\"shiftEndMin\" FROM technician t JOIN technician_depot_assignment a ON a.\"technicianId\"=t.id AND a.\"effectiveDate\"=(SELECT max(x.\"effectiveDate\") FROM technician_depot_assignment x WHERE x.\"technicianId\"=t.id AND x.\"effectiveDate\"<=?) JOIN depot p ON p.id=a.\"depotId\" LEFT JOIN LATERAL (SELECT id, \"effectiveDate\" FROM technician_availability_version WHERE \"technicianId\"=t.id AND \"effectiveDate\"<=? ORDER BY \"effectiveDate\" DESC LIMIT 1) v ON true LEFT JOIN technician_availability_day d ON d.\"versionId\"=v.id AND d.\"dayOfWeek\"=? WHERE p.\"metroId\"=? ORDER BY t.id",
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
                    raw.append(dev.waterflex.scheduler.DatabaseFacts.string(rs, 1));
                    for (int i = 2; i <= 6; i++) raw.append(':').append(rs.getString(i));
                    raw.append(';');
                }, dayStamp(day), dayStamp(day), day.getDayOfWeek().getValue() % 7, metroId);
        jdbc.query("SELECT r.\"technicianId\", i.\"startMin\", i.\"endMin\" FROM time_off_request r JOIN time_off_interval i ON i.\"requestId\"=r.id JOIN technician t ON t.id=r.\"technicianId\" JOIN technician_depot_assignment a ON a.\"technicianId\"=t.id AND a.\"effectiveDate\"=(SELECT max(x.\"effectiveDate\") FROM technician_depot_assignment x WHERE x.\"technicianId\"=t.id AND x.\"effectiveDate\"<=i.\"serviceDate\") JOIN depot p ON p.id=a.\"depotId\" WHERE p.\"metroId\"=? AND r.status='APPROVED' AND i.\"serviceDate\"=? ORDER BY r.\"technicianId\", i.\"startMin\"",
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> raw.append(dev.waterflex.scheduler.DatabaseFacts.string(rs, 1)).append(':').append(dev.waterflex.scheduler.DatabaseFacts.string(rs, 2)).append(':').append(dev.waterflex.scheduler.DatabaseFacts.string(rs, 3)).append(';'), metroId, dayStamp(day));
        try { return Required.value(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw.toString().getBytes(StandardCharsets.UTF_8)))); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    private void lockDay(String techId, LocalDate day) {
        dev.waterflex.scheduler.DatabaseFacts.query(jdbc, "SELECT version FROM schedule_day WHERE \"technicianId\"=? AND \"serviceDate\"=? FOR UPDATE", Integer.class, techId, dayStamp(day));
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
