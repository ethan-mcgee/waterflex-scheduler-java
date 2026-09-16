package dev.waterflex.scheduler.optimizer;

import ai.timefold.solver.core.api.solver.SolverFactory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.waterflex.scheduler.RoadClient;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.*;
import java.util.*;

@Service
public class OptimizationService {
    private static final ZoneId CHICAGO = ZoneId.of("America/Chicago");
    private final JdbcTemplate jdbc;
    private final RoadClient roads;
    private final SolverFactory<DayPlan> solverFactory;
    private final ObjectMapper mapper = new ObjectMapper();

    public record Request(String metro_id, String date) { }
    private record TechData(String id, RoadClient.Point point, TechRoute route) { }
    private record VisitData(PlanVisit visit, RoadClient.Point point, String technicianId, int sequence,
                             Instant windowStart, Instant windowEnd) { }
    private record Problem(DayPlan plan, Map<String, Integer> versions, List<VisitData> visits) { }

    public OptimizationService(JdbcTemplate jdbc, RoadClient roads, SolverFactory<DayPlan> solverFactory) {
        this.jdbc = jdbc; this.roads = roads; this.solverFactory = solverFactory;
    }

    public Map<String, Object> preview(Request request) {
        LocalDate day = parseDay(request.date());
        if (day.isBefore(LocalDate.now(CHICAGO).plusDays(2)))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Tomorrow's routes are frozen");
        Problem baseline = build(request.metro_id(), day);
        if (baseline.visits().isEmpty()) return persist(request.metro_id(), day, baseline, baseline.plan(),
                new DayScoreCalculator.Evaluation(0, 0, Map.of(), 0, 0, 0),
                new DayScoreCalculator.Evaluation(0, 0, Map.of(), 0, 0, 0), 0, "SKIPPED", "No appointments");
        if (hasHolds(baseline.versions().keySet(), day)) return skipped(request.metro_id(), day, baseline, "Active hold");
        var before = DayScoreCalculator.evaluate(baseline.plan());
        if (before.hardPenalty() != 0) return skipped(request.metro_id(), day, baseline, "Baseline infeasible");
        long started = System.nanoTime();
        DayPlan solved = solverFactory.buildSolver().solve(baseline.plan());
        int solveMs = (int) Duration.ofNanos(System.nanoTime() - started).toMillis();
        var after = DayScoreCalculator.evaluate(solved);
        String status = after.hardPenalty() == 0 && after.costCents() < before.costCents() ? "PREVIEW" : "SKIPPED";
        return persist(request.metro_id(), day, baseline, solved, before, after, solveMs, status,
                status.equals("PREVIEW") ? null : "No feasible cost improvement");
    }

    private Map<String, Object> skipped(String metroId, LocalDate day, Problem baseline, String reason) {
        var metrics = DayScoreCalculator.evaluate(baseline.plan());
        return persist(metroId, day, baseline, baseline.plan(), metrics, metrics, 0, "SKIPPED", reason);
    }

    private Map<String, Object> persist(String metroId, LocalDate day, Problem baseline, DayPlan proposal,
                                        DayScoreCalculator.Evaluation before, DayScoreCalculator.Evaluation after,
                                        int solveMs, String status, String reason) {
        String id = UUID.randomUUID().toString();
        List<Map<String, Object>> assignments = new ArrayList<>();
        for (TechRoute route : proposal.getRoutes()) {
            for (int i = 0; i < route.getVisits().size(); i++) {
                PlanVisit visit = route.getVisits().get(i);
                Instant arrival = after.arrivals().get(visit.getId());
                if (arrival != null) assignments.add(Map.of("appointmentId", visit.getId(), "technicianId", route.getId(),
                        "sequence", i, "plannedStart", arrival.toString(), "windowStart", visit.getWindowStart().toString(),
                        "windowEnd", visit.getWindowEnd().toString()));
            }
        }
        int improvement = (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, before.costCents() - after.costCents()));
        try {
            jdbc.update("INSERT INTO optimization_run (id, \"metroId\", \"serviceDate\", \"scheduleVersions\", weights, \"solverStatus\", \"solveMs\", \"routeSummaryBefore\", \"routeSummaryAfter\", warnings, \"proposedAssignments\", \"objectiveImprovement\", \"churnCost\", status, reason) VALUES (?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?::jsonb, ?, 0, ?, ?)",
                    id, metroId, dayStamp(day), mapper.writeValueAsString(baseline.versions()),
                    mapper.writeValueAsString(Map.of("mapVersion", roads.currentVersion())), status, solveMs,
                    mapper.writeValueAsString(summary(baseline.plan(), before)), mapper.writeValueAsString(summary(proposal, after)),
                    "[]", mapper.writeValueAsString(assignments), improvement, status, reason);
            Map<String, VisitData> original = new HashMap<>();
            baseline.visits().forEach(visit -> original.put(visit.visit().getId(), visit));
            for (Map<String, Object> assignment : assignments) {
                String appointmentId = (String) assignment.get("appointmentId");
                VisitData source = original.get(appointmentId);
                if (source == null) continue;
                String toTech = (String) assignment.get("technicianId");
                int toSequence = (Integer) assignment.get("sequence");
                Instant planned = Instant.parse((String) assignment.get("plannedStart"));
                if (source.technicianId().equals(toTech) && source.sequence() == toSequence
                        && source.visit().getOriginalPlannedStart().equals(planned)) continue;
                jdbc.update("INSERT INTO optimization_change (id, \"runId\", \"appointmentId\", \"fromTechnicianId\", \"toTechnicianId\", \"fromSequence\", \"toSequence\", \"fromPlannedArrivalMin\", \"toPlannedArrivalMin\") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                        UUID.randomUUID().toString(), id, appointmentId, source.technicianId(), toTech,
                        source.sequence(), toSequence, localMinute(source.visit().getOriginalPlannedStart()), localMinute(planned));
            }
        } catch (Exception e) { throw new IllegalStateException("Could not save optimization preview", e); }
        return response(id);
    }

    @Transactional
    public Map<String, Object> apply(String runId) {
        var runRows = jdbc.query("SELECT \"metroId\", \"serviceDate\", \"scheduleVersions\"::text, \"proposedAssignments\"::text, weights::text, status FROM optimization_run WHERE id=? FOR UPDATE",
                (rs, n) -> new String[]{rs.getString(1), rs.getTimestamp(2).toInstant().toString(), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6)}, runId);
        if (runRows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Preview not found");
        String[] run = runRows.getFirst();
        if (!run[5].equals("PREVIEW")) throw new ResponseStatusException(HttpStatus.CONFLICT, "Preview cannot be applied");
        LocalDate day = Instant.parse(run[1]).atZone(ZoneOffset.UTC).toLocalDate();
        if (day.isBefore(LocalDate.now(CHICAGO).plusDays(2))) throw new ResponseStatusException(HttpStatus.CONFLICT, "Tomorrow's routes are frozen");
        try {
            JsonNode versionNode = mapper.readTree(run[2]);
            List<String> techIds = new ArrayList<>();
            versionNode.fieldNames().forEachRemaining(techIds::add);
            Collections.sort(techIds);
            for (String techId : techIds) {
                lockDay(techId, day);
                int current = jdbc.queryForObject("SELECT version FROM schedule_day WHERE \"technicianId\"=? AND \"serviceDate\"=?", Integer.class, techId, dayStamp(day));
                if (current != versionNode.path(techId).asInt()) throw new ResponseStatusException(HttpStatus.CONFLICT, "Schedule changed");
            }
            if (hasHolds(new HashSet<>(techIds), day)) throw new ResponseStatusException(HttpStatus.CONFLICT, "Active hold");
            Problem current = build(run[0], day);
            JsonNode assignments = mapper.readTree(run[3]);
            Map<String, JsonNode> proposed = new HashMap<>();
            for (JsonNode node : assignments) proposed.put(node.path("appointmentId").asText(), node);
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
            if (!evaluated.feasible()) throw new ResponseStatusException(HttpStatus.CONFLICT, "Proposal infeasible");
            Problem baseline = build(run[0], day);
            var baselineMetrics = RouteEvaluator.evaluate(baseline.plan());
            if (!baselineMetrics.feasible() || evaluated.costCents() >= baselineMetrics.costCents())
                throw new ResponseStatusException(HttpStatus.CONFLICT, "No cost improvement");
            for (TechRoute route : current.plan().getRoutes()) {
                for (int index = 0; index < route.getVisits().size(); index++) {
                    PlanVisit visit = route.getVisits().get(index);
                    Instant arrival = evaluated.arrivals().get(visit.getId());
                    jdbc.update("UPDATE appointment SET \"technicianId\"=?, sequence=?, \"plannedStart\"=?, \"plannedEnd\"=?, \"updatedAt\"=CURRENT_TIMESTAMP WHERE id=?",
                            route.getId(), index, stamp(arrival), stamp(arrival.plus(Duration.ofMinutes(visit.getDurationMinutes()))), visit.getId());
                }
            }
            for (String techId : techIds) jdbc.update("UPDATE schedule_day SET version=version+1 WHERE \"technicianId\"=? AND \"serviceDate\"=?", techId, dayStamp(day));
            jdbc.update("UPDATE optimization_run SET status='APPLIED', \"appliedAt\"=CURRENT_TIMESTAMP WHERE id=?", runId);
            return response(runId);
        } catch (ResponseStatusException e) { throw e; }
          catch (Exception e) { throw new IllegalStateException("Could not apply proposal", e); }
    }

    public Map<String, Object> response(String id) {
        var rows = jdbc.query("SELECT \"metroId\", \"serviceDate\", status, reason, \"solverStatus\", \"solveMs\", \"objectiveImprovement\", \"routeSummaryBefore\"::text, \"routeSummaryAfter\"::text, \"createdAt\", \"appliedAt\" FROM optimization_run WHERE id=?",
                (rs, n) -> new Object[]{rs.getString(1), rs.getTimestamp(2).toInstant(), rs.getString(3), rs.getString(4), rs.getString(5), rs.getInt(6), rs.getInt(7), rs.getString(8), rs.getString(9), rs.getTimestamp(10).toInstant(), rs.getTimestamp(11) == null ? null : rs.getTimestamp(11).toInstant()}, id);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Preview not found");
        Object[] row = rows.getFirst();
        try {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("run_id", id); value.put("metro_id", row[0]); value.put("service_date", ((Instant) row[1]).toString().substring(0, 10));
            value.put("status", row[2]); value.put("reason", row[3]); value.put("solver_status", row[4]); value.put("solve_ms", row[5]);
            value.put("objective_improvement", row[6]); value.put("churn_penalty_minutes", 0);
            var changes = jdbc.query("SELECT \"appointmentId\", \"fromTechnicianId\", \"toTechnicianId\", \"fromSequence\", \"toSequence\", \"fromPlannedArrivalMin\", \"toPlannedArrivalMin\" FROM optimization_change WHERE \"runId\"=?",
                    (rs, n) -> Map.of("appointment_id", (Object) rs.getString(1), "from_technician_id", rs.getString(2),
                            "to_technician_id", rs.getString(3), "from_sequence", rs.getInt(4), "to_sequence", rs.getInt(5),
                            "from_planned_arrival_min", rs.getInt(6), "to_planned_arrival_min", rs.getInt(7)), id);
            value.put("optimized", row[2].equals("APPLIED")); value.put("appointments_moved", changes.size());
            value.put("route_summary_before", mapper.readValue((String) row[7], Object.class)); value.put("route_summary_after", mapper.readValue((String) row[8], Object.class));
            value.put("changes", changes); value.put("warnings", List.of());
            value.put("created_at", row[9]); value.put("applied_at", row[10]);
            return value;
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    public Map<String, Object> history(String metroId, String date) {
        LocalDate day = parseDay(date);
        List<Map<String, Object>> runs = jdbc.query("SELECT id FROM optimization_run WHERE \"metroId\"=? AND \"serviceDate\"=? ORDER BY \"createdAt\" DESC LIMIT 20",
                (rs, n) -> response(rs.getString(1)), metroId, dayStamp(day));
        return Map.of("runs", runs);
    }

    @Scheduled(cron = "0 0 2 * * *", zone = "America/Chicago")
    public void overnight() {
        var metros = jdbc.query("SELECT id FROM metro", (rs, n) -> rs.getString(1));
        for (String metro : metros) for (int offset = 2; offset <= 10; offset++) {
            try { preview(new Request(metro, LocalDate.now(CHICAGO).plusDays(offset).toString())); }
            catch (Exception ignored) { /* A failed day remains unchanged and can be retried by dispatch. */ }
        }
    }

    private Problem build(String metroId, LocalDate day) {
        List<TechData> techs = jdbc.query("SELECT t.id,t.\"homeLat\",t.\"homeLng\",COALESCE(o.\"shiftStartMin\",t.\"shiftStartMin\"),COALESCE(o.\"shiftEndMin\",t.\"shiftEndMin\"),t.\"maxDailyMinutes\",t.\"maxOvertimeMinutes\" FROM technician t LEFT JOIN technician_shift_override o ON o.\"technicianId\"=t.id AND o.\"serviceDate\"=? WHERE t.\"metroId\"=? AND t.active=true AND COALESCE(o.available,true)=true ORDER BY t.id",
                (rs, n) -> {
                    String id = rs.getString(1);
                    Set<String> qualifications = new HashSet<>(jdbc.query("SELECT \"serviceId\" FROM technician_qualification WHERE \"technicianId\"=?", (r, i) -> r.getString(1), id));
                    Instant start = day.atStartOfDay(CHICAGO).plusMinutes(rs.getInt(4)).toInstant();
                    Instant end = day.atStartOfDay(CHICAGO).plusMinutes(rs.getInt(5)).toInstant();
                    return new TechData(id, new RoadClient.Point(rs.getDouble(2), rs.getDouble(3)),
                            new TechRoute(id, start, end, rs.getInt(6), rs.getInt(7), qualifications));
                }, dayStamp(day), metroId);
        Map<String, TechData> byId = new HashMap<>();
        for (TechData tech : techs) byId.put(tech.id(), tech);
        List<VisitData> visits = jdbc.query("SELECT a.id, a.\"technicianId\",a.sequence,a.\"windowStart\",a.\"windowEnd\",a.\"plannedStart\",j.\"serviceId\",j.\"durationMin\",ad.lat,ad.lng FROM appointment a JOIN job j ON j.id=a.\"jobId\" JOIN address ad ON ad.id=j.\"addressId\" JOIN technician t ON t.id=a.\"technicianId\" WHERE t.\"metroId\"=? AND a.\"serviceDate\"=? ORDER BY a.\"technicianId\",a.sequence",
                (rs, n) -> {
                    String id = rs.getString(1), techId = rs.getString(2);
                    Instant start = rs.getTimestamp(4).toInstant(), end = rs.getTimestamp(5).toInstant();
                    return new VisitData(new PlanVisit(id, rs.getString(7), start, end, rs.getInt(8), techId, rs.getTimestamp(6).toInstant()),
                            new RoadClient.Point(rs.getDouble(9), rs.getDouble(10)), techId, rs.getInt(3), start, end);
                }, metroId, dayStamp(day));
        for (VisitData visit : visits) {
            TechData tech = byId.get(visit.technicianId());
            if (tech == null) throw new ResponseStatusException(HttpStatus.CONFLICT, "An assigned technician is unavailable");
            tech.route().getVisits().add(visit.visit());
        }
        Map<String, RoadClient.Point> points = new LinkedHashMap<>();
        techs.forEach(tech -> points.put(tech.id(), tech.point()));
        visits.forEach(visit -> points.put(visit.visit().getId(), visit.point()));
        Map<String, DayPlan.RoadLeg> matrix = new HashMap<>();
        for (var origin : points.entrySet()) for (var destination : points.entrySet()) {
            if (origin.getKey().equals(destination.getKey())) continue;
            try {
                RoadClient.Leg leg = roads.leg(origin.getValue(), destination.getValue());
                matrix.put(origin.getKey() + ">" + destination.getKey(), new DayPlan.RoadLeg(leg.seconds(), leg.meters()));
            } catch (RoadClient.RoadUnavailable e) { if (e.getMessage().contains("unavailable")) throw e; }
        }
        Map<String, Double> settings = new HashMap<>();
        jdbc.query("SELECT key,value FROM omaha_setting", (org.springframework.jdbc.core.RowCallbackHandler) rs -> settings.put(rs.getString(1), rs.getDouble(2)));
        DayPlan plan = new DayPlan(techs.stream().map(TechData::route).toList(), visits.stream().map(VisitData::visit).toList(), matrix,
                settings.getOrDefault("regular_hourly_dollars", 30.0), settings.getOrDefault("overtime_hourly_dollars", 45.0),
                settings.getOrDefault("mileage_dollars_per_mile", 0.67), settings.getOrDefault("travel_buffer_pct", 0.2),
                Math.round(settings.getOrDefault("travel_buffer_minutes_per_leg", 5.0)));
        Map<String, Integer> versions = new LinkedHashMap<>();
        for (TechData tech : techs) {
            jdbc.update("INSERT INTO schedule_day (id, \"technicianId\", \"serviceDate\", version) VALUES (?, ?, ?, 0) ON CONFLICT (\"technicianId\", \"serviceDate\") DO NOTHING",
                    UUID.randomUUID().toString(), tech.id(), dayStamp(day));
            versions.put(tech.id(), jdbc.queryForObject("SELECT version FROM schedule_day WHERE \"technicianId\"=? AND \"serviceDate\"=?", Integer.class, tech.id(), dayStamp(day)));
        }
        return new Problem(plan, versions, visits);
    }

    private List<Map<String, Object>> summary(DayPlan plan, DayScoreCalculator.Evaluation metrics) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (TechRoute route : plan.getRoutes()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("technician_id", route.getId()); item.put("stop_count", route.getVisits().size());
            item.put("appointment_ids", route.getVisits().stream().map(PlanVisit::getId).toList());
            var routeMetrics = DayScoreCalculator.evaluate(new DayPlan(List.of(route), route.getVisits(), plan.getMatrix(),
                    plan.getRegularHourly(), plan.getOvertimeHourly(), plan.getMileagePerMile(),
                    plan.getTravelBufferPct(), plan.getTravelBufferMinutes()));
            item.put("route_minutes", routeMetrics.paidMinutes()); item.put("drive_minutes", 0);
            item.put("workload_minutes", routeMetrics.paidMinutes()); item.put("overtime_minutes", routeMetrics.overtimeMinutes());
            result.add(item);
        }
        return result;
    }
    private boolean hasHolds(Set<String> techIds, LocalDate day) {
        if (techIds.isEmpty()) return false;
        String placeholders = String.join(",", Collections.nCopies(techIds.size(), "?"));
        List<Object> args = new ArrayList<>();
        args.add(dayStamp(day)); args.addAll(techIds);
        return jdbc.queryForObject("SELECT count(*) FROM slot_hold WHERE \"serviceDate\"=? AND \"technicianId\" IN (" + placeholders + ") AND \"releasedAt\" IS NULL AND \"expiresAt\">CURRENT_TIMESTAMP",
                Integer.class, args.toArray()) > 0;
    }
    private void lockDay(String techId, LocalDate day) {
        jdbc.queryForObject("SELECT version FROM schedule_day WHERE \"technicianId\"=? AND \"serviceDate\"=? FOR UPDATE", Integer.class, techId, dayStamp(day));
    }
    private static LocalDate parseDay(String text) {
        try { return LocalDate.parse(text); }
        catch (Exception e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid date"); }
    }
    private static Timestamp stamp(Instant time) { return Timestamp.from(time); }
    private static int localMinute(Instant time) { LocalTime local = time.atZone(CHICAGO).toLocalTime(); return local.getHour() * 60 + local.getMinute(); }
    private static Timestamp dayStamp(LocalDate day) { return stamp(day.atStartOfDay(ZoneOffset.UTC).toInstant()); }
}
