package dev.waterflex.scheduler;


import com.fasterxml.jackson.databind.JsonNode;
import org.jspecify.annotations.Nullable;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.waterflex.scheduler.optimizer.OptimizationService;
import dev.waterflex.scheduler.optimizer.TravelBreakdown;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Timestamp;
import java.time.*;
import java.util.*;

@Service
public class TimeOffService {
    private static final Logger log = Required.value(LoggerFactory.getLogger(TimeOffService.class));
    private static final ZoneId LOCAL = Required.value(ZoneId.of("America/Chicago"));
    private static final Set<String> CATEGORIES = Required.value(Set.of(
            "Vacation / personal travel", "Medical appointment", "Illness", "Family emergency",
            "Bereavement", "Jury duty / civic obligation", "Other"));
    private final JdbcTemplate jdbc;
    private final OptimizationService optimizer;
    private final ScheduleGuardService guard;
    private final ObjectMapper mapper = new ObjectMapper();
    private final TransactionTemplate transactions;

    public record Request(String technicianId, String firstDate, String lastDate, Integer startMin, Integer endMin, String category, String reason) { public Request { technicianId = RequestChecks.text(technicianId, "technicianId"); firstDate = RequestChecks.date(firstDate); lastDate = RequestChecks.date(lastDate); category = RequestChecks.text(category, "category"); reason = RequestChecks.text(reason, "reason"); if (startMin == null || endMin == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing absence hours"); if (!allowedCategory(category)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid time-off category"); } }
    private record Owner(String technicianId) { }
    private record ApprovalOwner(String technicianId, String status) { }
    private record Report(String data, String status) { }
    private record Interval(LocalDate day, int start, int end) { }
    private static final class DayAnalysisException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final LocalDate day;
        DayAnalysisException(LocalDate day, Exception cause) { super(cause); this.day = day; }
    }

    public TimeOffService(JdbcTemplate jdbc, OptimizationService optimizer, ScheduleGuardService guard, PlatformTransactionManager manager) {
        this.jdbc = jdbc; this.optimizer = optimizer; this.guard = guard; this.transactions = new TransactionTemplate(manager);
    }

    @Transactional
    public Map<String, Object> submit(Request request) {
        if (request.reason().isBlank() || request.reason().length() > 500
                || request.startMin() < 0 || request.endMin() > 1440
                || request.startMin() >= request.endMin()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid time-off request");
        LocalDate first, last;
        try { first = LocalDate.parse(request.firstDate()); last = LocalDate.parse(request.lastDate()); }
        catch (Exception e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid date"); }
        if (last.isBefore(first) || first.isBefore(LocalDate.now(LOCAL)) || last.isAfter(first.plusDays(30))
                || !ScheduleCutoff.localMinute(Required.value(first), request.startMin(), false).isAfter(Instant.now()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid or past date range");
        if (jdbc.query("SELECT id FROM technician WHERE id=? AND active=true FOR UPDATE", (rs, _) -> Required.string(rs, 1), request.technicianId()).isEmpty())
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Technician not found");
        for (LocalDate day = first; !day.isAfter(last); day = day.plusDays(1)) {
            int overlaps = Required.query(jdbc, "SELECT count(*) FROM time_off_interval i JOIN time_off_request r ON r.id=i.\"requestId\" WHERE r.\"technicianId\"=? AND r.status IN ('PENDING','READY','APPROVED','ANALYZING') AND i.\"serviceDate\"=? AND i.\"startMin\"<? AND i.\"endMin\">?",
                    Integer.class, request.technicianId(), stamp(Required.value(day)), request.endMin(), request.startMin());
            if (overlaps > 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "Overlapping time-off request");
        }
        String id = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO time_off_request (id, \"technicianId\", category, reason, status) VALUES (?, ?, ?, ?, 'PENDING')", id, request.technicianId(), request.category(), request.reason().trim());
        for (LocalDate day = first; !day.isAfter(last); day = day.plusDays(1))
            jdbc.update("INSERT INTO time_off_interval (id, \"requestId\", \"serviceDate\", \"startMin\", \"endMin\") VALUES (?, ?, ?, ?, ?)",
                    UUID.randomUUID().toString(), id, stamp(Required.value(day)), request.startMin(), request.endMin());
        jdbc.update("INSERT INTO time_off_report (id, \"requestId\", status) VALUES (?, ?, 'QUEUED')", UUID.randomUUID().toString(), id);
        return Required.value(Map.<String, Object>of("requestId", id, "status", "PENDING"));
    }

    @org.springframework.beans.factory.annotation.Value("${time-off.analysis.enabled:true}")
    private boolean analysisEnabled = true;

    @Scheduled(fixedDelayString = "#{@timeOffPolling.delayMs()}")
    public void processQueued() {
        if (!analysisEnabled) return;
        var ids = jdbc.query("SELECT p.\"requestId\" FROM time_off_report p JOIN time_off_request r ON r.id=p.\"requestId\" WHERE p.status='QUEUED' AND r.status='PENDING' ORDER BY p.\"createdAt\" LIMIT 3", (rs, _) -> Required.string(rs, 1));
        for (String id : ids) {
            if (jdbc.update("UPDATE time_off_report SET status='ANALYZING', \"updatedAt\"=CURRENT_TIMESTAMP WHERE \"requestId\"=? AND status='QUEUED' AND EXISTS (SELECT 1 FROM time_off_request WHERE id=? AND status='PENDING')", id, id) == 0) continue;
            try { analyze(Required.value(id)); }
            catch (Exception e) {
                log.error("Time-off analysis failed for request {}", id, e);
                Throwable cause = e instanceof DayAnalysisException && e.getCause() != null ? e.getCause() : e;
                String category = cause instanceof RoadClient.RoadUnavailable ? "ROUTING_FAILURE" : "ANALYSIS_FAILURE";
                String date = e instanceof DayAnalysisException dayError ? dayError.day.toString() : "the request";
                @Nullable String detail = cause instanceof ResponseStatusException statusError ? statusError.getReason() : null;
                String reason;
                if (cause instanceof RoadClient.RoadUnavailable) reason = "Routing was unavailable for " + date + ". Retry analysis when routing is available.";
                else if (detail != null && detail.startsWith("Technician weekly availability"))
                    reason = "Technician weekly availability is missing or invalid for " + date + ". Correct the availability and retry analysis.";
                else reason = "Could not analyze " + date + ". Retry analysis; if it fails again, ask an administrator to check the scheduler logs.";
                try {
                    jdbc.update("UPDATE time_off_report SET status=?, data=?::jsonb, \"updatedAt\"=CURRENT_TIMESTAMP WHERE \"requestId\"=? AND status='ANALYZING' AND EXISTS (SELECT 1 FROM time_off_request WHERE id=? AND status='PENDING')",
                            category, mapper.writeValueAsString(Map.of("reason", reason)), id, id);
                } catch (Exception saveError) { log.error("Could not save time-off failure for request {}", id, saveError); }
            }
        }
    }

    private void analyze(String id) throws Exception {
        var owner = jdbc.query("SELECT r.\"technicianId\" FROM time_off_request r WHERE r.id=?",
                (rs, _) -> new Owner(Required.string(rs, 1)), id);
        if (owner.isEmpty()) return;
        List<Interval> intervals = intervals(id);
        List<Map<String, Object>> days = new ArrayList<>();
        boolean feasible = !intervals.isEmpty();
        List<Map<String, Long>> beforeMetrics = new ArrayList<>(), afterMetrics = new ArrayList<>();
        List<TravelBreakdown> beforeTravel = new ArrayList<>(), afterTravel = new ArrayList<>();
        int reassignedJobs = 0;
        for (Interval interval : intervals) {
            Map<String, Object> preview;
            try {
                if (ScheduleCutoff.frozen(interval.day(), Required.value(Instant.now()))) {
                    preview = Map.of("serviceDate", interval.day().toString(), "status", "FROZEN_CSR_COORDINATION", "reason", "Scheduling cutoff has passed; coordinate with customer service");
                } else {
                    String metroId = Required.query(jdbc, "SELECT p.\"metroId\" FROM technician_depot_assignment a JOIN depot p ON p.id=a.\"depotId\" WHERE a.\"technicianId\"=? AND a.\"effectiveDate\"<=? ORDER BY a.\"effectiveDate\" DESC LIMIT 1", String.class,
                            owner.getFirst().technicianId(), java.sql.Timestamp.from(interval.day().atStartOfDay(java.time.ZoneOffset.UTC).toInstant()));
                    preview = optimizer.previewRepair(metroId, interval.day(), owner.getFirst().technicianId(), interval.start(), interval.end());
                }
            } catch (Exception e) { throw new DayAnalysisException(interval.day(), e); }
            Map<String, Object> saved = new LinkedHashMap<>();
            saved.put("service_date", interval.day().toString());
            saved.put("start_min", interval.start());
            saved.put("end_min", interval.end());
            saved.put("status", preview.get("status"));
            if (preview.containsKey("run_id")) saved.put("run_id", preview.get("run_id"));
            if (preview.containsKey("reason")) saved.put("reason", preview.get("reason"));
            if (preview.containsKey("calculation_outcome")) saved.put("calculation_outcome", preview.get("calculation_outcome"));
            if (preview.containsKey("score_model_version")) saved.put("score_model_version", preview.get("score_model_version"));
            if (preview.containsKey("solver_analysis")) saved.put("solver_analysis", preview.get("solver_analysis"));
            if (preview.containsKey("route_summary_before")) saved.put("before", preview.get("route_summary_before"));
            if (preview.containsKey("route_summary_after")) saved.put("after", preview.get("route_summary_after"));
            if (preview.containsKey("changes")) saved.put("changes", preview.get("changes"));
            if (saved.containsKey("before") && saved.containsKey("after") && saved.containsKey("changes")) {
                Map<String, Long> before = totals(Required.value(mapper.<@Nullable JsonNode>valueToTree(saved.get("before"))), previewFleetCents(preview, "fleet_cost_before_cents"));
                Map<String, Long> after = totals(Required.value(mapper.<@Nullable JsonNode>valueToTree(saved.get("after"))), previewFleetCents(preview, "fleet_cost_after_cents"));
                saved.put("cost_model_version", Monetary.COST_MODEL);
                int moved = reassigned(Required.value(mapper.<@Nullable JsonNode>valueToTree(saved.get("changes"))));
                saved.put("daily_before", before); saved.put("daily_after", after); saved.put("reassigned_jobs", moved);
                beforeMetrics.add(before); afterMetrics.add(after); reassignedJobs += moved;
                var roadBefore = travelTotals(Required.value(mapper.<@Nullable JsonNode>valueToTree(saved.get("before"))));
                var roadAfter = travelTotals(Required.value(mapper.<@Nullable JsonNode>valueToTree(saved.get("after"))));
                saved.put("travel_before", roadBefore); saved.put("travel_after", roadAfter);
                if (roadBefore != null) beforeTravel.add(roadBefore);
                if (roadAfter != null) afterTravel.add(roadAfter);
            }
            days.add(saved);
            feasible &= "REPAIR_PREVIEW".equals(preview.get("status")) || "NO_SHIFT".equals(preview.get("status"));
            jdbc.update("UPDATE time_off_report SET progress=?, \"updatedAt\"=CURRENT_TIMESTAMP WHERE \"requestId\"=? AND status='ANALYZING'",
                    days.size() * 100 / intervals.size(), id);
        }
        String status = feasible ? "READY" : "NEEDS_COORDINATION";
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("technician_id", owner.getFirst().technicianId());
        data.put("days", days);
        data.put("total_before", beforeMetrics.size() == days.size() ? combined(beforeMetrics) : null);
        data.put("total_after", afterMetrics.size() == days.size() ? combined(afterMetrics) : null);
        data.put("travel_before", beforeTravel.size() == days.size() ? combineTravel(beforeTravel) : null);
        data.put("travel_after", afterTravel.size() == days.size() ? combineTravel(afterTravel) : null);
        data.put("reassigned_jobs", reassignedJobs);
        String reportJson = mapper.writeValueAsString(data);
        boolean ready = feasible;
        boolean committed = Boolean.TRUE.equals(transactions.execute(_ -> {
            var current = jdbc.query("SELECT status FROM time_off_request WHERE id=? FOR UPDATE", (rs, _) -> Required.string(rs, 1), id);
            if (current.isEmpty() || !"PENDING".equals(current.getFirst())) return false;
            int updated = jdbc.update("UPDATE time_off_report SET status=?, data=?::jsonb, progress=100, \"updatedAt\"=CURRENT_TIMESTAMP WHERE \"requestId\"=? AND status='ANALYZING'",
                    status, reportJson, id);
            if (updated == 0) return false;
            if (ready) jdbc.update("UPDATE time_off_request SET status='READY' WHERE id=? AND status='PENDING'", id);
            return true;
        }));
        if (committed && feasible) {
            if (automaticEligible(intervals.getFirst().day(), Required.value(LocalDate.now(LOCAL))))
                try { tryApprove(id, false, Required.value(List.of())); }
                catch (ResponseStatusException ignored) { /* Stale reports are queued for another analysis. */ }
        }
    }

    public Map<String, Object> approve(String id, boolean allowAdditionalOvertime, List<String> approvedRepairIds) {
        return tryApprove(id, allowAdditionalOvertime, approvedRepairIds);
    }

    public Map<String, Object> retry(String id) {
        return Required.value(transactions.execute(_ -> {
            var rows = jdbc.query("SELECT status FROM time_off_request WHERE id=? FOR UPDATE", (rs, _) -> Required.string(rs, 1), id);
            if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Request not found");
            if (!"PENDING".equals(rows.getFirst())) throw new ResponseStatusException(HttpStatus.CONFLICT, "Only pending requests can be retried");
            int updated = jdbc.update("UPDATE time_off_report SET status='QUEUED', data=NULL, progress=0, \"updatedAt\"=CURRENT_TIMESTAMP WHERE \"requestId\"=? AND status IN ('ANALYSIS_FAILURE','ROUTING_FAILURE')", id);
            if (updated == 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "Only failed analyses can be retried");
            return Map.<String, Object>of("requestId", id, "status", "PENDING");
        }));
    }

    public Map<String, Object> deny(String id) {
        return Required.value(transactions.execute(_ -> {
            var rows = jdbc.query("SELECT status FROM time_off_request WHERE id=? FOR UPDATE", (rs, _) -> Required.string(rs, 1), id);
            if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Request not found");
            if ("DENIED".equals(rows.getFirst())) return Map.<String, Object>of("requestId", id, "status", "DENIED");
            if (!"PENDING".equals(rows.getFirst()) && !"READY".equals(rows.getFirst()))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Approved requests cannot be denied");
            jdbc.update("UPDATE time_off_request SET status='DENIED', \"decidedAt\"=CURRENT_TIMESTAMP WHERE id=?", id);
            jdbc.update("UPDATE time_off_report SET status='DENIED', \"updatedAt\"=CURRENT_TIMESTAMP WHERE \"requestId\"=?", id);
            return Map.<String, Object>of("requestId", id, "status", "DENIED");
        }));
    }

    private Map<String, Object> tryApprove(String id, boolean allowAdditionalOvertime, List<String> approvedRepairIds) {
        try { return Required.value(transactions.execute(_ -> approveLocked(id, allowAdditionalOvertime, approvedRepairIds))); }
        catch (ResponseStatusException e) {
            if (e instanceof dev.waterflex.scheduler.optimizer.RepairOvertimeApprovalRequired) throw e;
            if (e.getStatusCode() == HttpStatus.CONFLICT) {
                boolean frozen = String.valueOf(e.getReason()).contains("Frozen date");
                jdbc.update("UPDATE time_off_request SET status='PENDING' WHERE id=? AND status='READY'", id);
                jdbc.update("UPDATE time_off_report SET status=?, progress=?, \"updatedAt\"=CURRENT_TIMESTAMP WHERE \"requestId\"=? AND status='READY'",
                        frozen ? "NEEDS_COORDINATION" : "QUEUED", frozen ? 100 : 0, id);
            }
            throw e;
        }
    }

    private Map<String, Object> approveLocked(String id, boolean allowAdditionalOvertime, List<String> approvedRepairIds) {
        var owner = jdbc.query("SELECT \"technicianId\", status FROM time_off_request WHERE id=? FOR UPDATE",
                (rs, _) -> new ApprovalOwner(Required.string(rs, 1), Required.string(rs, 2)), id);
        if (owner.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Request not found");
        if (owner.getFirst().status().equals("APPROVED")) return Required.value(Map.<String, Object>of("requestId", id, "status", "APPROVED"));
        if (!owner.getFirst().status().equals("READY")) throw new ResponseStatusException(HttpStatus.CONFLICT, "Fresh feasible report required");
        guard.lockTechnician(owner.getFirst().technicianId());
        var reports = jdbc.query("SELECT data::text, status FROM time_off_report WHERE \"requestId\"=? FOR UPDATE",
                (rs, _) -> new Report(Required.string(rs, 1), Required.string(rs, 2)), id);
        if (reports.isEmpty() || !reports.getFirst().status().equals("READY")) throw new ResponseStatusException(HttpStatus.CONFLICT, "Fresh feasible report required");
        try {
            List<Interval> intervals = intervals(id);
            for (Interval interval : intervals) {
                guard.unfrozen(interval.day());
                guard.noHolds(owner.getFirst().technicianId(), interval.day());
            }
            JsonNode report = SavedJson.readyReport(Required.value(mapper.readTree(reports.getFirst().data())));
            JsonNode days = report.path("days");
            if (allowAdditionalOvertime) {
                java.util.Set<String> currentRepairs = new java.util.HashSet<>();
                for (JsonNode repair : days) if ("REPAIR_PREVIEW".equals(repair.path("status").asText()))
                    currentRepairs.add(SavedJson.text(Required.value(repair), "run_id"));
                if (!currentRepairs.equals(new java.util.HashSet<>(approvedRepairIds)))
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "Repair preview changed; review the latest report");
            }
            if (!owner.getFirst().technicianId().equals(report.path("technician_id").asText()))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Request changed");
            if (!days.isArray() || intervals.isEmpty() || days.size() != intervals.size()) throw new ResponseStatusException(HttpStatus.CONFLICT, "Incomplete report");
            for (int i = 0; i < intervals.size(); i++) {
                String dayStatus = SavedJson.text(Required.value(days.path(i)), "status");
                SavedJson.integer(Required.value(days.path(i)), "start_min"); SavedJson.integer(Required.value(days.path(i)), "end_min");
                if (!"REPAIR_PREVIEW".equals(dayStatus) && !"NO_SHIFT".equals(dayStatus)) throw SavedJson.invalid();
                if (!intervals.get(i).day().toString().equals(days.get(i).path("service_date").asText())
                        || intervals.get(i).start() != days.get(i).path("start_min").asInt(-1)
                        || intervals.get(i).end() != days.get(i).path("end_min").asInt(-1))
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "Report date changed");
                if ("NO_SHIFT".equals(dayStatus)) {
                    if (WeeklyAvailability.resolve(jdbc, owner.getFirst().technicianId(), intervals.get(i).day()) != null)
                        throw new ResponseStatusException(HttpStatus.CONFLICT, "Availability changed; retry analysis");
                    continue;
                }
                SavedJson.text(Required.value(days.path(i)), "run_id");
                optimizer.applyRepair(Required.value(days.get(i).path("run_id").asText()), owner.getFirst().technicianId(), intervals.get(i).day(), intervals.get(i).start(), intervals.get(i).end(), allowAdditionalOvertime);
            }
            jdbc.update("UPDATE time_off_request SET status='APPROVED', \"decidedAt\"=CURRENT_TIMESTAMP, \"additionalOvertimeApproved\"=? WHERE id=?", allowAdditionalOvertime, id);
            jdbc.update("UPDATE time_off_report SET status='APPLIED', \"updatedAt\"=CURRENT_TIMESTAMP WHERE \"requestId\"=?", id);
            return Required.value(Map.<String, Object>of("requestId", id, "status", "APPROVED"));
        } catch (ResponseStatusException e) { throw e; }
        catch (Exception e) { throw new ResponseStatusException(HttpStatus.CONFLICT, "Invalid saved time-off report", e); }
    }

    private List<Interval> intervals(String id) {
        return jdbc.query("SELECT \"serviceDate\", \"startMin\", \"endMin\" FROM time_off_interval WHERE \"requestId\"=? ORDER BY \"serviceDate\"",
                (rs, _) -> new Interval(Required.value(Required.timestamp(rs, 1).toInstant().atZone(ZoneOffset.UTC).toLocalDate()), Required.integer(rs, 2), Required.integer(rs, 3)), id);
    }

    private static final List<String> METRICS = Required.value(List.of("route_minutes", "overtime_minutes", "drive_minutes", "waiting_minutes", "distance_meters", "modeled_cost_cents"));

    static @Nullable TravelBreakdown travelTotals(JsonNode routes) {
        SavedJson.summary(routes);
        List<TravelBreakdown> values = new ArrayList<>();
        for (JsonNode route : routes) {
            if (!route.hasNonNull("travel_breakdown")) return null;
            JsonNode travel = Required.value(route.path("travel_breakdown"));
            values.add(new TravelBreakdown(SavedJson.integer(travel, "road_seconds"),
                    Required.value(travel.path("configured_buffer_seconds").decimalValue()),
                    Required.value(travel.path("rounding_seconds").decimalValue()),
                    SavedJson.integer(travel, "modeled_travel_minutes"), Math.toIntExact(SavedJson.integer(travel, "leg_count"))));
        }
        return combineTravel(values);
    }

    private static TravelBreakdown combineTravel(List<TravelBreakdown> values) {
        long road = 0, modeled = 0; int legs = 0;
        java.math.BigDecimal buffer = java.math.BigDecimal.ZERO, rounding = java.math.BigDecimal.ZERO;
        for (TravelBreakdown value : values) {
            road = Math.addExact(road, value.road_seconds()); modeled = Math.addExact(modeled, value.modeled_travel_minutes());
            legs = Math.addExact(legs, value.leg_count()); buffer = buffer.add(value.configured_buffer_seconds());
            rounding = rounding.add(value.rounding_seconds());
        }
        return new TravelBreakdown(road, Required.value(buffer), Required.value(rounding), modeled, legs);
    }

    static long previewFleetCents(Map<String, Object> preview, String field) {
        if (!Monetary.COST_MODEL.equals(preview.get("cost_model_version"))
                || !(preview.get(field) instanceof Long cents) || cents < 0 || cents > Monetary.MAX_CENTS)
            throw SavedJson.invalid();
        return cents;
    }
    static Map<String, Long> totals(JsonNode routes, long fleetCents) {
        if (fleetCents < 0 || fleetCents > Monetary.MAX_CENTS) throw SavedJson.invalid();
        Map<String, Long> values = new LinkedHashMap<>();
        for (String metric : METRICS) values.put(metric, 0L);
        for (JsonNode route : SavedJson.array(routes))
            for (String metric : METRICS) if (!metric.equals("modeled_cost_cents")) values.merge(metric, SavedJson.integer(Required.value(route), Required.value(metric)), (a, b) -> Math.addExact(Required.value(a), Required.value(b)));
        values.put("modeled_cost_cents", fleetCents);
        return values;
    }

    private static Map<String, Long> combined(List<Map<String, Long>> days) {
        Map<String, Long> values = new LinkedHashMap<>();
        for (String metric : METRICS) values.put(metric, 0L);
        for (Map<String, Long> daily : days) {
            for (String metric : METRICS) values.merge(metric, Required.value(daily.get(metric), Required.value(metric)), (a, b) -> Math.addExact(Required.value(a), Required.value(b)));
        }
        return values;
    }

    private static int reassigned(JsonNode changes) {
        SavedJson.array(changes);
        int count = 0;
        for (JsonNode change : changes) if (!SavedJson.text(Required.value(change), "from_technician_id").equals(SavedJson.text(Required.value(change), "to_technician_id"))) count++;
        return count;
    }

    private static Timestamp stamp(LocalDate day) { return Required.value(Timestamp.from(day.atStartOfDay(ZoneOffset.UTC).toInstant())); }
    static boolean automaticEligible(LocalDate firstDate, LocalDate today) { return !firstDate.isBefore(today.plusDays(14)); }
    static boolean allowedCategory(String category) { return CATEGORIES.contains(category); }
}
