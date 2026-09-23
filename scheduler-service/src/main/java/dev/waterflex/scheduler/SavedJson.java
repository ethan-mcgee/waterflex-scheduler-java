package dev.waterflex.scheduler;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;
import java.util.HashSet;

/** Validate persisted proposals before they can influence scheduling mutations. */
public final class SavedJson {
    private SavedJson() { }
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
