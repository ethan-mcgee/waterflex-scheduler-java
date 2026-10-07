package dev.waterflex.scheduler;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;
import java.util.HashSet;

/** Validate persisted proposals before they can influence scheduling mutations. */
public final class SavedJson {
    private SavedJson() { }
    public static JsonNode policyDiagnostics(JsonNode node) {
        object(node);
        if (integer(node,"schemaVersion") != 1) throw invalid();
        try {
            var fairness = dev.waterflex.scheduler.optimizer.DailyPolicyDiagnostics.Fairness.valueOf(text(node,"fairness"));
            dev.waterflex.scheduler.optimizer.DailyPolicyDiagnostics.Decision.valueOf(text(node,"decision"));
            for (String key : new String[]{"referenceOvertimeMinutes","candidateOvertimeMinutes"})
                if (!node.has(key) || node.hasNonNull(key) && integer(node,Required.value(key)) < 0) throw invalid();
            dailyOutcome(Required.value(node.path("candidate")));
            if (fairness == dev.waterflex.scheduler.optimizer.DailyPolicyDiagnostics.Fairness.SKIPPED_REFERENCE_OVERTIME
                    && (!node.hasNonNull("referenceOvertimeMinutes") || integer(node,"referenceOvertimeMinutes") == 0)) throw invalid();
        } catch (RuntimeException failure) { throw invalid(); }
        return node;
    }
    public static JsonNode object(JsonNode node) { if (!node.isObject()) throw invalid(); return node; }
    public static JsonNode array(JsonNode node) { if (!node.isArray()) throw invalid(); return node; }
    public static String text(JsonNode node, String key) {
        JsonNode value = node.path(key);
        if (!value.isTextual() || value.asText().isBlank()) throw invalid();
        return Required.value(value.asText());
    }
    public static long integer(JsonNode node, String key) {
        JsonNode value = node.path(key);
        if (!value.isIntegralNumber() || !value.canConvertToLong()) throw invalid();
        return value.asLong();
    }
    public static JsonNode versions(JsonNode node) {
        object(node);
        node.properties().forEach(entry -> { if (entry.getKey().isBlank() || !entry.getValue().isInt() || entry.getValue().asInt() < 0) throw invalid(); });
        return node;
    }
    public static JsonNode provenance(JsonNode node) { object(node); text(node, "mapVersion"); text(node, "configVersion"); return node; }
    public static void currentCostModel(JsonNode node) {
        object(node);
        if (!Monetary.COST_MODEL.equals(node.path("costModelVersion").textValue()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A fresh preview with the current cost model is required");
    }
    public static long moneyCents(JsonNode node, String key) {
        long value = integer(node, key); if (value < 0 || value > Monetary.MAX_CENTS) throw invalid(); return value;
    }
    public static void currentScoreModel(JsonNode node) {
        object(node);
        if (!dev.waterflex.scheduler.optimizer.DailyDataset.SCORE_MODEL.equals(node.path("scoreModelVersion").textValue()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A fresh preview with the current score model is required");
        JsonNode outcome = dailyOutcome(Required.value(node.path("calculationOutcome")));
        if (!outcome.path("complete").booleanValue() || !outcome.path("policyEligible").booleanValue())
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A complete eligible preview is required");
    }
    public static JsonNode dailyOutcome(JsonNode node) {
        object(node);
        if (!java.util.Set.of("ASSIGNED", "COLD", "PARTIAL", "REPAIR").contains(text(node, "mode"))) throw invalid();
        if (!dev.waterflex.scheduler.optimizer.DailyDataset.SCORE_MODEL.equals(text(node, "scoreModelVersion"))) throw invalid();
        java.util.Set<String> identities = new HashSet<>();
        for (String field : new String[]{"assignedVisitIds", "unassignedVisitIds"})
            for (JsonNode value : array(Required.value(node.path(field))))
                if (!value.isTextual() || value.asText().isBlank() || !identities.add(value.asText())) throw invalid();
        for (String field : new String[]{"complete", "assignedWorkFeasible", "scoringMatchesValidation", "policyEligible"})
            if (!node.path(field).isBoolean()) throw invalid();
        if (node.path("complete").booleanValue() != node.path("unassignedVisitIds").isEmpty()) throw invalid();
        if (node.path("scoringMatchesValidation").booleanValue() && !node.path("assignedWorkFeasible").booleanValue()) throw invalid();
        if (node.path("policyEligible").booleanValue() && (!node.path("complete").booleanValue()
                || !node.path("scoringMatchesValidation").booleanValue())) throw invalid();
        return node;
    }
    public static JsonNode solverAnalysis(JsonNode node) {
        object(node); text(node, "engine"); text(node, "configurationXml");
        if (node.hasNonNull("policy")) policyDiagnostics(Required.value(node.path("policy")));
        if (node.hasNonNull("constructionConfigurationXml")) text(node, "constructionConfigurationXml");
        JsonNode phases = array(Required.value(node.path("phases"))); if (phases.isEmpty()) throw invalid();
        for (JsonNode phase : phases) {
            text(Required.value(phase), "name"); JsonNode statistics = object(Required.value(phase.path("statistics")));
            text(statistics, "variant");
            if (!text(statistics, "configurationFingerprint").matches("[a-f0-9]{64}")) throw invalid();
            boolean current = statistics.has("format");
            if (current && integer(statistics, "format") != 2) throw invalid();
            String termination = text(statistics, "termination");
            if (!(current ? java.util.Set.of("TERMINATED_EARLY", "STEP_AND_TIME_LIMIT", "STEP_LIMIT", "TIME_LIMIT", "PHASE_COMPLETED", "SOLVE_RETURNED", "NOT_RUN")
                    : java.util.Set.of("TERMINATED_EARLY", "STEP_AND_TIME_LIMIT", "STEP_LIMIT", "TIME_LIMIT", "PHASE_COMPLETED")).contains(termination)) throw invalid();
            integer(statistics, "seed");
            for (String key : new String[]{"budgetMs", "solveMs"})
                if (integer(statistics, Required.value(key)) < 0) throw invalid();
            boolean unavailable = current && statistics.hasNonNull("diagnosticsUnavailableReason");
            for (String key : new String[]{"steps", "moveEvaluations", "scoreCalculations"}) {
                if (unavailable) { if (!statistics.path(key).isNull()) throw invalid(); }
                else if (integer(statistics, Required.value(key)) < 0) throw invalid();
            }
            if (current) {
                nullableText(statistics, "diagnosticsUnavailableReason");
                nullableText(statistics, "timeToBestUnavailableReason");
                if (statistics.path("timeToBestMs").isNull() != statistics.hasNonNull("timeToBestUnavailableReason")) throw invalid();
                String basis = text(statistics, "terminationBasis");
                boolean observed = java.util.Set.of("SOLVE_RETURNED", "TERMINATED_EARLY", "NOT_RUN").contains(termination);
                if (!(observed ? "OBSERVED" : "INFERRED").equals(basis) || (!observed && unavailable)) throw invalid();
                if (!java.util.Set.of("NO_ASSERT", "FULL_ASSERT", "NON_INTRUSIVE_FULL_ASSERT", "PHASE_ASSERT").contains(text(statistics, "environmentMode"))) throw invalid();
                if (!java.util.Set.of("NONE", "TIMEFOLD_INTERNAL_2_6_0").contains(text(statistics, "instrumentation"))) throw invalid();
                JsonNode provenance = object(Required.value(statistics.path("provenance")));
                if (!"timefold-solver-core".equals(text(provenance, "artifact"))) throw invalid();
                nullableText(provenance, "version"); nullableText(provenance, "sha256"); nullableText(provenance, "unavailableReason");
                boolean missing = provenance.hasNonNull("unavailableReason");
                if (missing != provenance.path("version").isNull() || missing != provenance.path("sha256").isNull()) throw invalid();
                if (!missing && !text(provenance, "sha256").matches("[a-f0-9]{64}")) throw invalid();
            }
            if (integer(statistics, "budgetMs") == 0) throw invalid();
            for (String key : new String[]{"stepLimit", "timeToBestMs"}) {
                if (!statistics.has(key)) throw invalid();
                if (statistics.hasNonNull(key) && integer(statistics, Required.value(key)) < 0) throw invalid();
            }
            if (statistics.hasNonNull("stepLimit") && integer(statistics, "stepLimit") == 0) throw invalid();
        }
        return node;
    }
    private static void nullableText(JsonNode node, String field) {
        if (!node.has(field)) throw invalid();
        if (!node.path(field).isNull()) text(node, field);
    }
    public static JsonNode policyAnalysis(JsonNode node) {
        object(node);
        if (!dev.waterflex.scheduler.optimizer.SchedulingPolicy.VERSION.equals(text(node, "version"))) throw invalid();
        integer(node, "costChangeCents");
        JsonNode rules = object(Required.value(node.path("rules")));
        new dev.waterflex.scheduler.optimizer.SchedulingPolicy.Rules(
                Math.toIntExact(integer(rules, "regularWindowThreshold")),
                decimal(rules, "utilizationThreshold"), decimal(rules, "fairnessAllowance"),
                Math.toIntExact(integer(rules, "bookingDeadlineMs")));
        JsonNode decision = object(Required.value(node.path("decision")));
        if (!decision.path("accepted").isBoolean()) throw invalid();
        text(decision, "reason");
        for (String key : new String[]{"referenceCostCents", "overtimeTargetMinutes", "costCeilingCents"})
            if (integer(decision, Required.value(key)) < 0) throw invalid();
        for (String phase : new String[]{"before", "after"}) {
            JsonNode metrics = object(Required.value(node.path(phase)));
            if (integer(metrics, "overtimeMinutes") < 0 || integer(metrics, "costCents") < 0) throw invalid();
            JsonNode fairness = object(Required.value(metrics.path("fairness")));
            if (decimal(fairness, "variance").signum() < 0 || decimal(fairness, "maximumUtilization").signum() < 0) throw invalid();
            var technicians = new HashSet<String>();
            for (JsonNode workload : array(Required.value(fairness.path("workloads")))) {
                JsonNode item = Required.value(workload);
                if (!technicians.add(text(item, "technicianId")) || integer(item, "paidMinutes") < 0
                        || integer(item, "regularCapacityMinutes") <= 0 || decimal(item, "utilization").signum() < 0) throw invalid();
            }
        }
        return node;
    }
    private static java.math.BigDecimal decimal(JsonNode node, String key) {
        JsonNode value = node.path(key);
        if (!value.isNumber() || !Double.isFinite(value.asDouble())) throw invalid();
        return Required.value(value.decimalValue());
    }
    public static JsonNode assignments(JsonNode node) {
        array(node);
        var ids = new HashSet<String>();
        var positions = new HashSet<String>();
        for (JsonNode item : node) {
            object(Required.value(item));
            if (!ids.add(text(Required.value(item), "appointmentId"))) throw invalid();
            String tech = text(Required.value(item), "technicianId");
            long sequence = integer(Required.value(item), "sequence");
            if (sequence < 0 || sequence > Integer.MAX_VALUE || !positions.add(tech + ":" + sequence)) throw invalid();
            try {
                Instant.parse(text(Required.value(item), "plannedStart"));
                if (item.has("plannedEnd") && !Instant.parse(text(Required.value(item), "plannedStart"))
                        .isBefore(Instant.parse(text(Required.value(item), "plannedEnd")))) throw invalid();
                if (!Instant.parse(text(Required.value(item), "windowStart")).isBefore(Instant.parse(text(Required.value(item), "windowEnd")))) throw invalid();
            } catch (RuntimeException e) { throw invalid(); }
            for (String key : new String[]{"locationLat", "locationLng"}) {
                JsonNode coordinate = item.path(key);
                if (!coordinate.isNumber() || !Double.isFinite(coordinate.asDouble()) || Math.abs(coordinate.asDouble()) > (key.equals("locationLat") ? 90 : 180)) throw invalid();
            }
        }
        return node;
    }
    public static JsonNode summary(JsonNode node) {
        array(node);
        for (JsonNode route : node) {
            text(Required.value(route), "technician_id");
            for (String key : new String[]{"stop_count", "route_minutes", "drive_minutes", "waiting_minutes", "distance_meters", "modeled_cost_cents", "workload_minutes", "overtime_minutes"})
                if (integer(Required.value(route), Required.value(key)) < 0) throw invalid();
            for (JsonNode id : array(Required.value(route.path("appointment_ids")))) if (!id.isTextual() || id.asText().isBlank()) throw invalid();
            if (route.hasNonNull("travel_breakdown")) {
                JsonNode travel = object(Required.value(route.path("travel_breakdown")));
                long road = integer(travel, "road_seconds"), modeled = integer(travel, "modeled_travel_minutes");
                if (road < 0 || modeled < 0 || integer(travel, "leg_count") < 0 || modeled != integer(Required.value(route), "drive_minutes")) throw invalid();
                for (String key : new String[]{"configured_buffer_seconds", "rounding_seconds"}) {
                    JsonNode number = Required.value(travel.path(key));
                    if (!number.isNumber() || !Double.isFinite(number.doubleValue()) || number.decimalValue().signum() < 0) throw invalid();
                }
                var total = java.math.BigDecimal.valueOf(road).add(travel.path("configured_buffer_seconds").decimalValue()).add(travel.path("rounding_seconds").decimalValue());
                if (total.compareTo(java.math.BigDecimal.valueOf(modeled).multiply(java.math.BigDecimal.valueOf(60))) != 0) throw invalid();
            }
            if (route.hasNonNull("segments")) for (JsonNode segment : array(Required.value(route.path("segments")))) {
                JsonNode value = object(Required.value(segment));
                try {
                    if (Instant.parse(text(value, "departure")).isAfter(Instant.parse(text(value, "returned_at")))) throw invalid();
                } catch (RuntimeException e) { throw invalid(); }
                for (JsonNode id : array(Required.value(value.path("appointment_ids"))))
                    if (!id.isTextual() || id.asText().isBlank()) throw invalid();
            }
        }
        return node;
    }
    public static JsonNode readyReport(JsonNode node) {
        object(node);
        text(node, "technician_id");
        if (integer(node, "reassigned_jobs") < 0) throw invalid();
        JsonNode days = array(Required.value(node.path("days")));
        if (days.isEmpty()) throw invalid();
        boolean allRepair = true;
        for (JsonNode day : days) {
            String status = text(Required.value(day), "status");
            if (!"REPAIR_PREVIEW".equals(status) && !"NO_SHIFT".equals(status)) throw invalid();
            try { java.time.LocalDate.parse(text(Required.value(day), "service_date")); }
            catch (RuntimeException e) { throw invalid(); }
            long start = integer(Required.value(day), "start_min"), end = integer(Required.value(day), "end_min");
            if (start < 0 || end > 1440 || start >= end) throw invalid();
            if ("NO_SHIFT".equals(status)) {
                allRepair = false;
                if (day.has("run_id") || day.has("before") || day.has("after") || day.has("changes")) throw invalid();
                continue;
            }
            text(Required.value(day), "run_id");
            summary(Required.value(day.path("before"))); summary(Required.value(day.path("after")));
            metrics(Required.value(day.path("daily_before"))); metrics(Required.value(day.path("daily_after")));
            if (integer(Required.value(day), "reassigned_jobs") < 0) throw invalid();
            for (JsonNode change : array(Required.value(day.path("changes")))) {
                for (String field : new String[]{"appointment_id", "from_technician_id", "to_technician_id"}) text(Required.value(change), Required.value(field));
                for (String field : new String[]{"from_sequence", "to_sequence", "from_planned_arrival_min", "to_planned_arrival_min"}) integer(Required.value(change), Required.value(field));
            }
        }
        if (allRepair) {
            metrics(Required.value(node.path("total_before")));
            metrics(Required.value(node.path("total_after")));
        } else if (!node.path("total_before").isNull() || !node.path("total_after").isNull()) throw invalid();
        return node;
    }
    private static void metrics(JsonNode node) {
        object(node);
        for (String key : new String[]{"route_minutes", "overtime_minutes", "drive_minutes", "waiting_minutes", "distance_meters", "modeled_cost_cents"})
            if (integer(node, Required.value(key)) < 0) throw invalid();
    }
    public static ResponseStatusException invalid() { return new ResponseStatusException(HttpStatus.CONFLICT, "Invalid saved scheduling preview or report"); }
}
