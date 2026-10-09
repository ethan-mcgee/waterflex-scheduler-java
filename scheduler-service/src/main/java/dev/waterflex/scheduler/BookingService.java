package dev.waterflex.scheduler;

import dev.waterflex.scheduler.optimizer.DayPlan;
import dev.waterflex.scheduler.optimizer.RouteEvaluator;
import dev.waterflex.scheduler.optimizer.PlanVisit;
import dev.waterflex.scheduler.optimizer.TechRoute;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.*;
import java.util.*;

/** Re-times booked days after a depot, dealership or assignment change. Booking itself goes through the public API. */
@Service
public class BookingService {
    private final JdbcTemplate jdbc;
    private final RoadClient roads;

    private record Tech(String id, RouteEndpoints endpoints, int shiftStart, int shiftEnd, int maxDaily, int maxOvertime) { }
    private record Visit(String id, RoadPoint point, Instant start, Instant end, Instant planned, int duration, boolean newJob) { }
    private record EvaluationContext(List<TechRoute.Unavailable> absences, Map<String, DayPlan.RoadLeg> matrix,
                                     Map<String, java.math.BigDecimal> settings) { }
    private record Metrics(boolean feasible, @Nullable Instant newArrival, long paidMinutes, long overtimeMinutes,
                           long meters, long costCents, Map<String, Instant> arrivals, List<RouteEvaluator.WorkingSegment> segments) { }

    public BookingService(JdbcTemplate jdbc, RoadClient roads) {
        this.jdbc = jdbc; this.roads = roads;
    }

    static List<LocalDate> bookingDates(Instant now) { return BookingCalendar.bookingDates(now); }
    static List<LocalDate> overflowDates(Instant now) { return BookingCalendar.overflowDates(now); }

    private void persistTiming(List<Visit> route, Metrics result) {
        for (int index = 0; index < route.size(); index++) {
            Visit visit = route.get(index);
            Instant arrival = Required.value(result.arrivals().get(visit.id()), "route arrival");
            Timestamp plannedStart = stamp(arrival), plannedEnd = stamp(Required.value(arrival.plus(Duration.ofMinutes(visit.duration()))));
            int updated = jdbc.update("UPDATE appointment SET sequence=?,\"plannedStart\"=?,\"plannedEnd\"=?,\"updatedAt\"=CURRENT_TIMESTAMP WHERE id=? AND \"cancelledAt\" IS NULL",
                    index, plannedStart, plannedEnd, visit.id());
            if (updated != 1) throw new ResponseStatusException(HttpStatus.CONFLICT, "A route appointment changed during validation");
        }
    }

    private Metrics evaluate(Tech tech, LocalDate day, List<Visit> visits) {
        if (visits.isEmpty()) return new Metrics(true, null, 0, 0, 0, 0, Required.value(Map.of()), Required.value(List.of()));
        return evaluate(tech, day, visits, prepare(tech, day, visits, settings(), roads.activeIdentity()));
    }

    private EvaluationContext prepare(Tech tech, LocalDate day, List<Visit> visits, Map<String, java.math.BigDecimal> settings, String routingIdentity) {
        dev.waterflex.scheduler.DatabaseDeadline.apply(jdbc);
        List<TechRoute.Unavailable> absences = jdbc.query("SELECT i.\"startMin\", i.\"endMin\" FROM time_off_interval i JOIN time_off_request r ON r.id=i.\"requestId\" WHERE r.status='APPROVED' AND r.\"technicianId\"=? AND i.\"serviceDate\"=? ORDER BY i.\"startMin\"",
                (rs, _) -> new TechRoute.Unavailable(ScheduleCutoff.localMinute(day, dev.waterflex.scheduler.DatabaseFacts.integer(rs, 1), false),
                        ScheduleCutoff.localMinute(day, dev.waterflex.scheduler.DatabaseFacts.integer(rs, 2), true)), tech.id(), dayStamp(day));
        Map<String, RoadPoint> points = new LinkedHashMap<>();
        points.put(tech.id(), tech.endpoints().departure());
        points.put(tech.id() + ":return", tech.endpoints().returnTo());
        visits.forEach(visit -> points.put(visit.id(), visit.point()));
        Map<String, DayPlan.RoadLeg> matrix = new HashMap<>();
        Map<String, RoadClient.Pair> pairs = new LinkedHashMap<>();
        for (Visit visit : visits) {
            addPair(pairs, points, tech.id(), visit.id());
            addPair(pairs, points, visit.id(), tech.id() + ":return");
        }
        for (int index = 1; index < visits.size(); index++) addPair(pairs, points, visits.get(index - 1).id(), visits.get(index).id());
        for (Visit candidate : visits) if (candidate.newJob()) for (Visit existing : visits) if (!candidate.id().equals(existing.id())) {
            addPair(pairs, points, candidate.id(), existing.id());
            addPair(pairs, points, existing.id(), candidate.id());
        }
        roads.sparse(new ArrayList<>(pairs.values()), routingIdentity).forEach((pair, leg) -> matrix.put(pair, new DayPlan.RoadLeg(leg.seconds(), leg.meters())));
        return new EvaluationContext(Required.value(List.copyOf(absences)), Required.value(Map.copyOf(matrix)), Required.value(Map.copyOf(settings)));
    }

    private static void addPair(Map<String, RoadClient.Pair> pairs, Map<String, RoadPoint> points, String from, String to) {
        pairs.putIfAbsent(from + ">" + to, new RoadClient.Pair(from + ">" + to,
                Required.value(points.get(from), "road origin"), Required.value(points.get(to), "road destination")));
    }

    private Metrics evaluate(Tech tech, LocalDate day, List<Visit> visits, EvaluationContext snapshot) {
        Instant shiftStart = ScheduleCutoff.localMinute(day, tech.shiftStart(), false);
        Instant shiftEnd = ScheduleCutoff.localMinute(day, tech.shiftEnd(), true);
        TechRoute route = new TechRoute(tech.id(), shiftStart, shiftEnd, tech.maxDaily(), tech.maxOvertime(), Required.value(Set.of("BOOKING")));
        route.setUnavailable(new ArrayList<>(snapshot.absences()));
        for (Visit visit : visits) route.getVisits().add(new PlanVisit(visit.id(), "BOOKING", visit.start(), visit.end(), visit.duration(), tech.id(), visit.planned()));
        BookingSnapshot.Rates rates = BookingSnapshot.Rates.read(snapshot.settings());
        DayPlan plan = new DayPlan(Required.value(List.of(route)), route.getVisits(), snapshot.matrix(),
                rates.regularHourly(), rates.overtimeHourly(), rates.mileagePerMile(), rates.travelBufferPct(), rates.travelBufferMinutes());
        var result = RouteEvaluator.evaluate(plan);
        Instant newArrival = visits.stream().filter((Visit visit) -> visit.newJob()).findFirst().map(v -> result.arrivals().get(v.id())).orElse(null);
        return new Metrics(result.feasible(), newArrival, result.paidMinutes(), result.overtimeMinutes(),
                result.meters(), result.costCents(), result.arrivals(), Required.value(result.segments().get(tech.id()), "working segments"));
    }

    /** Called inside the locked dealership policy transaction after its new endpoints are saved. */
    void replanExisting(String techId, LocalDate day) {
        WeeklyAvailability.Shift shift = WeeklyAvailability.resolve(jdbc, techId, day);
        if (shift == null) throw new ResponseStatusException(HttpStatus.CONFLICT, "Booked technician has no shift");
        var rows = jdbc.query("SELECT " + RouteEndpoints.COLUMNS + ",t.\"maxDailyMinutes\",t.\"maxOvertimeMinutes\" FROM technician t" + RouteEndpoints.JOINS + " WHERE t.id=?",
                (rs, _) -> new Tech(techId, RouteEndpoints.from(rs, 1), shift.start(), shift.end(), dev.waterflex.scheduler.DatabaseFacts.integer(rs, 7), dev.waterflex.scheduler.DatabaseFacts.integer(rs, 8)), dayStamp(day), dayStamp(day), techId);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.CONFLICT, "Booked technician is missing");
        List<Visit> visits = jdbc.query("SELECT a.id, ad.lat, ad.lng, a.\"windowStart\", a.\"windowEnd\", a.\"plannedStart\", j.\"durationMin\" FROM appointment a JOIN job j ON j.id=a.\"jobId\" JOIN address ad ON ad.id=j.\"addressId\" WHERE a.\"technicianId\"=? AND a.\"serviceDate\"=? AND a.\"cancelledAt\" IS NULL ORDER BY a.sequence, a.\"plannedStart\"",
                (rs, _) -> new Visit(dev.waterflex.scheduler.DatabaseFacts.string(rs, 1), dev.waterflex.scheduler.DatabaseFacts.location(rs, 2, 3, HttpStatus.CONFLICT), Required.value(dev.waterflex.scheduler.DatabaseFacts.timestamp(rs, 4).toInstant()), Required.value(dev.waterflex.scheduler.DatabaseFacts.timestamp(rs, 5).toInstant()), Required.value(dev.waterflex.scheduler.DatabaseFacts.timestamp(rs, 6).toInstant()), dev.waterflex.scheduler.DatabaseFacts.integer(rs, 7), false), techId, dayStamp(day));
        Metrics result = evaluate(Required.value(rows.getFirst()), day, visits);
        if (!result.feasible() || result.arrivals().size() != visits.size())
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Dealership route would make a booked day infeasible");
        for (Visit visit : visits) {
            Instant arrival = Required.value(result.arrivals().get(visit.id()), "appointment arrival");
            jdbc.update("UPDATE appointment SET \"plannedStart\"=?,\"plannedEnd\"=?,\"updatedAt\"=CURRENT_TIMESTAMP WHERE id=?",
                    stamp(arrival), stamp(Required.value(arrival.plus(Duration.ofMinutes(visit.duration())))), visit.id());
        }
        jdbc.update("UPDATE schedule_day SET version=version+1 WHERE \"technicianId\"=? AND \"serviceDate\"=?", techId, dayStamp(day));
        persistCurrent(techId, day);
    }

    /** Revalidate every booked window after a depot move. */
    void replanAssignment(String techId, LocalDate day) {
        WeeklyAvailability.Shift shift = WeeklyAvailability.resolve(jdbc, techId, day);
        if (shift == null) throw new ResponseStatusException(HttpStatus.CONFLICT, "Assigned technician has no shift");
        var rows = jdbc.query("SELECT " + RouteEndpoints.COLUMNS + ",t.\"maxDailyMinutes\",t.\"maxOvertimeMinutes\" FROM technician t" + RouteEndpoints.JOINS + " WHERE t.id=?",
                (rs, _) -> new Tech(techId, RouteEndpoints.from(rs, 1), shift.start(), shift.end(), dev.waterflex.scheduler.DatabaseFacts.integer(rs, 7), dev.waterflex.scheduler.DatabaseFacts.integer(rs, 8)), dayStamp(day), dayStamp(day), techId);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.CONFLICT, "Assigned technician is missing");
        List<Visit> route = visits(techId, day, "");
        Metrics result = evaluate(Required.value(rows.getFirst()), day, route);
        if (!result.feasible() || result.arrivals().size() != route.size())
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Depot move would make a booked route infeasible");
        for (int index = 0; index < route.size(); index++) {
            Visit visit = route.get(index);
            Instant arrival = Required.value(result.arrivals().get(visit.id()), "route arrival");
            Timestamp plannedStart = stamp(arrival), plannedEnd = stamp(Required.value(arrival.plus(Duration.ofMinutes(visit.duration()))));
            int updated = jdbc.update("UPDATE appointment SET sequence=?,\"plannedStart\"=?,\"plannedEnd\"=?,\"updatedAt\"=CURRENT_TIMESTAMP WHERE id=? AND \"cancelledAt\" IS NULL",
                    index, plannedStart, plannedEnd, visit.id());
            if (updated != 1) throw new ResponseStatusException(HttpStatus.CONFLICT, "A route appointment changed during the depot move");
        }
        jdbc.update("INSERT INTO schedule_day (id,\"technicianId\",\"serviceDate\",version) VALUES (?,?,?,0) ON CONFLICT (\"technicianId\",\"serviceDate\") DO NOTHING",
                UUID.randomUUID().toString(), techId, dayStamp(day));
        jdbc.update("UPDATE schedule_day SET version=version+1 WHERE \"technicianId\"=? AND \"serviceDate\"=?", techId, dayStamp(day));
        persistCurrent(techId, day);
    }

    /** Persist the actual confirmed route separately from conservative unconfirmed placeholders. */
    private void persistCurrent(String technician, LocalDate day) {
        List<Visit> confirmed = visits(technician, day, "");
        if (confirmed.isEmpty()) {
            ScheduleSegments.save(jdbc, technician, day, Required.value(List.of()), roads.currentVersion());
            return;
        }
        WeeklyAvailability.Shift shift = WeeklyAvailability.resolve(jdbc, technician, day);
        if (shift == null) throw new ResponseStatusException(HttpStatus.CONFLICT, "Confirmed route has no working shift");
        var rows = jdbc.query("SELECT " + RouteEndpoints.COLUMNS + ",t.\"maxDailyMinutes\",t.\"maxOvertimeMinutes\" FROM technician t" + RouteEndpoints.JOINS + " WHERE t.id=?",
                (rs, _) -> new Tech(technician, RouteEndpoints.from(rs, 1), shift.start(), shift.end(), dev.waterflex.scheduler.DatabaseFacts.integer(rs, 7), dev.waterflex.scheduler.DatabaseFacts.integer(rs, 8)), dayStamp(day), dayStamp(day), technician);
        if (rows.size() != 1) throw new ResponseStatusException(HttpStatus.CONFLICT, "Confirmed technician is unavailable");
        Metrics actual = evaluate(Required.value(rows.getFirst()), day, confirmed);
        if (!actual.feasible()) throw new ResponseStatusException(HttpStatus.CONFLICT, "Confirmed route requires repair");
        persistTiming(confirmed, actual);
        ScheduleSegments.save(jdbc, technician, day, actual.segments(), roads.currentVersion());
    }

    private List<Visit> visits(String techId, LocalDate day, String excludeJobId) {
        dev.waterflex.scheduler.DatabaseDeadline.apply(jdbc);
        List<Visit> result = new ArrayList<Visit>(Required.value(jdbc.query("SELECT a.id, ad.lat, ad.lng, a.\"windowStart\", a.\"windowEnd\", a.\"plannedStart\", j.\"durationMin\" FROM appointment a JOIN job j ON j.id=a.\"jobId\" JOIN address ad ON ad.id=j.\"addressId\" WHERE a.\"technicianId\"=? AND a.\"serviceDate\"=? AND a.\"cancelledAt\" IS NULL AND (? IS NULL OR a.\"jobId\"<>?) ORDER BY a.sequence, a.\"plannedStart\"",
                (rs, _) -> new Visit(dev.waterflex.scheduler.DatabaseFacts.string(rs, 1), dev.waterflex.scheduler.DatabaseFacts.location(rs, 2, 3, HttpStatus.CONFLICT), Required.value(dev.waterflex.scheduler.DatabaseFacts.timestamp(rs, 4).toInstant()), Required.value(dev.waterflex.scheduler.DatabaseFacts.timestamp(rs, 5).toInstant()), Required.value(dev.waterflex.scheduler.DatabaseFacts.timestamp(rs, 6).toInstant()), dev.waterflex.scheduler.DatabaseFacts.integer(rs, 7), false), techId, dayStamp(day), excludeJobId, excludeJobId)));
        result.sort(Comparator.comparing((Visit visit) -> visit.planned()));
        return result;
    }

    private Map<String, java.math.BigDecimal> settings() {
        Map<String, java.math.BigDecimal> values = new HashMap<>();
        jdbc.query("SELECT key, value FROM omaha_setting", (org.springframework.jdbc.core.RowCallbackHandler) rs -> values.put(dev.waterflex.scheduler.DatabaseFacts.string(rs, 1), dev.waterflex.scheduler.DatabaseFacts.decimal(rs, 2)));
        return values;
    }

    private static Timestamp stamp(Instant time) { return Required.value(Timestamp.from(time)); }
    private static Timestamp dayStamp(LocalDate day) { return stamp(Required.value(day.atStartOfDay(ZoneOffset.UTC).toInstant())); }
}
