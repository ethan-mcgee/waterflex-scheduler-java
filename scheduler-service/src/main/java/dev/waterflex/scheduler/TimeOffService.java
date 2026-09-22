package dev.waterflex.scheduler;


import com.fasterxml.jackson.databind.JsonNode;
import org.jspecify.annotations.Nullable;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.waterflex.scheduler.optimizer.OptimizationService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.*;
import java.util.*;

@Service
public class TimeOffService {
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
    private record Owner(String technicianId, String metroId) { }
    private record ApprovalOwner(String technicianId, String status) { }
    private record Report(String data, String status) { }
    private record Interval(LocalDate day, int start, int end) { }

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

    @Scheduled(fixedDelay = 30000)
    public void processQueued() {
        var ids = jdbc.query("SELECT \"requestId\" FROM time_off_report WHERE status='QUEUED' ORDER BY \"createdAt\" LIMIT 3", (rs, _) -> Required.string(rs, 1));
        for (String id : ids) {
            if (jdbc.update("UPDATE time_off_report SET status='ANALYZING', \"updatedAt\"=CURRENT_TIMESTAMP WHERE \"requestId\"=? AND status='QUEUED'", id) == 0) continue;
            try { analyze(Required.value(id)); }
            catch (Exception e) {
                String category = e instanceof RoadClient.RoadUnavailable ? "ROUTING_FAILURE" : "ANALYSIS_FAILURE";
                jdbc.update("UPDATE time_off_report SET status=?, data=?::jsonb, \"updatedAt\"=CURRENT_TIMESTAMP WHERE \"requestId\"=?",
                        category, "{\"reason\":\"" + category + "\"}", id);
            }
        }
    }

    private void analyze(String id) throws Exception {
        var owner = jdbc.query("SELECT r.\"technicianId\", t.\"metroId\" FROM time_off_request r JOIN technician t ON t.id=r.\"technicianId\" WHERE r.id=?",
                (rs, _) -> new Owner(Required.string(rs, 1), Required.string(rs, 2)), id);
        if (owner.isEmpty()) return;
        List<Interval> intervals = intervals(id);
        List<Map<String, Object>> days = new ArrayList<>();
        boolean feasible = !intervals.isEmpty();
        List<Map<String, Long>> beforeMetrics = new ArrayList<>(), afterMetrics = new ArrayList<>();
        int reassignedJobs = 0;
        for (Interval interval : intervals) {
            Map<String, Object> preview;
            if (ScheduleCutoff.frozen(interval.day(), Required.value(Instant.now()))) {
                preview = Map.of("serviceDate", interval.day().toString(), "status", "FROZEN_CSR_COORDINATION");
            } else {
                preview = optimizer.previewRepair(owner.getFirst().metroId(), interval.day(), owner.getFirst().technicianId(), interval.start(), interval.end());
            }
            Map<String, Object> saved = new LinkedHashMap<>();
            saved.put("service_date", interval.day().toString());
            saved.put("start_min", interval.start());
            saved.put("end_min", interval.end());
            saved.put("status", preview.get("status"));
            if (preview.containsKey("run_id")) saved.put("run_id", preview.get("run_id"));
            if (preview.containsKey("reason")) saved.put("reason", preview.get("reason"));
            if (preview.containsKey("route_summary_before")) saved.put("before", preview.get("route_summary_before"));
            if (preview.containsKey("route_summary_after")) saved.put("after", preview.get("route_summary_after"));
            if (preview.containsKey("changes")) saved.put("changes", preview.get("changes"));
            if (saved.containsKey("before") && saved.containsKey("after") && saved.containsKey("changes")) {
                Map<String, Long> before = totals(Required.value(mapper.<@Nullable JsonNode>valueToTree(saved.get("before"))));
                Map<String, Long> after = totals(Required.value(mapper.<@Nullable JsonNode>valueToTree(saved.get("after"))));
                int moved = reassigned(Required.value(mapper.<@Nullable JsonNode>valueToTree(saved.get("changes"))));
                saved.put("daily_before", before); saved.put("daily_after", after); saved.put("reassigned_jobs", moved);
                beforeMetrics.add(before); afterMetrics.add(after); reassignedJobs += moved;
            }
            days.add(saved);
            feasible &= "REPAIR_PREVIEW".equals(preview.get("status"));
            jdbc.update("UPDATE time_off_report SET progress=?, \"updatedAt\"=CURRENT_TIMESTAMP WHERE \"requestId\"=?",
                    days.size() * 100 / intervals.size(), id);
        }
        String status = feasible ? "READY" : "NEEDS_COORDINATION";
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("technician_id", owner.getFirst().technicianId());
        data.put("days", days);
        data.put("total_before", beforeMetrics.size() == days.size() ? combined(beforeMetrics) : null);
        data.put("total_after", afterMetrics.size() == days.size() ? combined(afterMetrics) : null);
        data.put("reassigned_jobs", reassignedJobs);
        jdbc.update("UPDATE time_off_report SET status=?, data=?::jsonb, progress=100, \"updatedAt\"=CURRENT_TIMESTAMP WHERE \"requestId\"=?",
                status, mapper.writeValueAsString(data), id);
        if (feasible) {
            jdbc.update("UPDATE time_off_request SET status='READY' WHERE id=? AND status='PENDING'", id);
            if (automaticEligible(intervals.getFirst().day(), Required.value(LocalDate.now(LOCAL))))
                try { tryApprove(id); }
                catch (ResponseStatusException ignored) { /* Stale reports are queued for another analysis. */ }
        }
    }

    public Map<String, Object> approve(String id) { return tryApprove(id); }

    private Map<String, Object> tryApprove(String id) {
        try { return Required.value(transactions.execute(_ -> approveLocked(id))); }
        catch (ResponseStatusException e) {
            if (e.getStatusCode() == HttpStatus.CONFLICT) {
                boolean frozen = String.valueOf(e.getReason()).contains("Frozen date");
                jdbc.update("UPDATE time_off_request SET status='PENDING' WHERE id=? AND status='READY'", id);
                jdbc.update("UPDATE time_off_report SET status=?, progress=?, \"updatedAt\"=CURRENT_TIMESTAMP WHERE \"requestId\"=? AND status='READY'",
                        frozen ? "NEEDS_COORDINATION" : "QUEUED", frozen ? 100 : 0, id);
            }
            throw e;
        }
    }

    private Map<String, Object> approveLocked(String id) {
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
            for (Interval interval : intervals) guard.unfrozen(interval.day());
            JsonNode report = SavedJson.readyReport(Required.value(mapper.readTree(reports.getFirst().data())));
            JsonNode days = report.path("days");
            if (!owner.getFirst().technicianId().equals(report.path("technician_id").asText()))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Request changed");
            if (!days.isArray() || intervals.isEmpty() || days.size() != intervals.size()) throw new ResponseStatusException(HttpStatus.CONFLICT, "Incomplete report");
            for (int i = 0; i < intervals.size(); i++) {
                SavedJson.text(Required.value(days.path(i)), "run_id");
                SavedJson.integer(Required.value(days.path(i)), "start_min"); SavedJson.integer(Required.value(days.path(i)), "end_min");
                if (!"REPAIR_PREVIEW".equals(SavedJson.text(Required.value(days.path(i)), "status"))) throw SavedJson.invalid();
                if (!intervals.get(i).day().toString().equals(days.get(i).path("service_date").asText())
                        || intervals.get(i).start() != days.get(i).path("start_min").asInt(-1)
                        || intervals.get(i).end() != days.get(i).path("end_min").asInt(-1))
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "Report date changed");
                optimizer.applyRepair(Required.value(days.get(i).path("run_id").asText()), owner.getFirst().technicianId(), intervals.get(i).day(), intervals.get(i).start(), intervals.get(i).end());
            }
            jdbc.update("UPDATE time_off_request SET status='APPROVED', \"decidedAt\"=CURRENT_TIMESTAMP WHERE id=?", id);
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

    private static Map<String, Long> totals(JsonNode routes) {
        Map<String, Long> values = new LinkedHashMap<>();
        for (String metric : METRICS) values.put(metric, 0L);
        for (JsonNode route : SavedJson.array(routes))
            for (String metric : METRICS) values.merge(metric, SavedJson.integer(Required.value(route), Required.value(metric)), (a, b) -> Required.value(a) + Required.value(b));
        return values;
    }

    private static Map<String, Long> combined(List<Map<String, Long>> days) {
        Map<String, Long> values = new LinkedHashMap<>();
        for (String metric : METRICS) values.put(metric, 0L);
        for (Map<String, Long> daily : days) {
            for (String metric : METRICS) values.merge(metric, Required.value(daily.get(metric), Required.value(metric)), (a, b) -> Required.value(a) + Required.value(b));
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
