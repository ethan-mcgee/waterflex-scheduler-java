package dev.waterflex.scheduler;

import dev.waterflex.scheduler.optimizer.DayPlan;
import dev.waterflex.scheduler.optimizer.DayScoreCalculator;
import dev.waterflex.scheduler.optimizer.PlanVisit;
import dev.waterflex.scheduler.optimizer.TechRoute;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.*;
import java.util.*;

@Service
public class BookingService {
    private static final ZoneId CHICAGO = ZoneId.of("America/Chicago");
    private final JdbcTemplate jdbc;
    private final RoadClient roads;

    public record Offer(String offerId, String date, Instant windowStart, Instant windowEnd, Instant expiresAt) { }
    public record Offers(String jobId, List<Offer> offers) { }
    public record Selection(String holdId, Instant expiresAt, String appointmentId, Instant windowStart, Instant windowEnd) { }
    public record Confirmation(String appointmentId, Instant windowStart, Instant windowEnd) { }
    public record Candidate(String techId, LocalDate day, Instant start, Instant end, Instant arrival,
                            int position, double cost, long regularDeltaMinutes, long overtimeDeltaMinutes, long roadDeltaMeters) { }
    private record Job(String id, String serviceId, int duration, RoadClient.Point point, String status) { }
    private record Tech(String id, RoadClient.Point home, int shiftStart, int shiftEnd, int maxDaily, int maxOvertime) { }
    private record Visit(String id, RoadClient.Point point, Instant start, Instant end, Instant planned, int duration, boolean newJob) { }
    private record Metrics(boolean feasible, Instant newArrival, long paidMinutes, long overtimeMinutes,
                           long meters, Map<String, Instant> arrivals) { }

    public BookingService(JdbcTemplate jdbc, RoadClient roads) { this.jdbc = jdbc; this.roads = roads; }

    @Transactional
    public Offers offers(String jobId, boolean refresh) {
        lockJob(jobId);
        Job job = job(jobId);
        if (!job.status().equals("PENDING")) throw new ResponseStatusException(HttpStatus.CONFLICT, "Job already booked");
        var active = jdbc.query("SELECT id FROM booking_offer_set WHERE \"jobId\"=? AND \"expiresAt\">CURRENT_TIMESTAMP AND \"supersededAt\" IS NULL ORDER BY \"createdAt\" DESC LIMIT 1",
                (rs, n) -> rs.getString(1), jobId);
        if (!active.isEmpty() && !refresh) return savedOffers(jobId, active.getFirst());
        if (!active.isEmpty()) {
            jdbc.update("UPDATE booking_offer_set SET \"supersededAt\"=CURRENT_TIMESTAMP WHERE id=?", active.getFirst());
            jdbc.update("UPDATE slot_hold SET \"releasedAt\"=CURRENT_TIMESTAMP WHERE \"offerSetId\"=? AND \"releasedAt\" IS NULL", active.getFirst());
        }
        roads.leg(job.point(), job.point());
        List<Candidate> candidates = candidates(job, null, null);
        LinkedHashMap<String, Candidate> windows = new LinkedHashMap<>();
        candidates.stream().sorted(Comparator.comparingDouble(Candidate::cost).thenComparing(Candidate::start))
                .forEach(c -> windows.putIfAbsent(c.start().toString(), c));
        List<Offer> result = new ArrayList<>();
        Instant expiry = Instant.now().plus(Duration.ofMinutes(10));
        String setId = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO booking_offer_set (id, \"jobId\", \"expiresAt\") VALUES (?, ?, ?)", setId, jobId, stamp(expiry));
        for (Candidate c : windows.values()) {
            if (result.size() == 4) break;
            lockDay(c.techId(), c.day());
            Tech tech = technicians(job.serviceId(), c.day()).stream().filter(t -> t.id().equals(c.techId())).findFirst().orElse(null);
            Candidate reserved = tech == null ? null : evaluateCandidate(job, tech, c.day(), c.start(), c.end());
            if (reserved == null) continue;
            String id = UUID.randomUUID().toString();
            jdbc.update("INSERT INTO booking_offer (id, \"jobId\", \"serviceDate\", \"windowStart\", \"windowEnd\", \"expiresAt\", \"incrementalRegularMinutes\", \"incrementalOvertimeMinutes\", \"incrementalRoadMeters\", \"incrementalCostDollars\", \"offerSetId\") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    id, jobId, dayStamp(c.day()), stamp(c.start()), stamp(c.end()), stamp(expiry),
                    reserved.regularDeltaMinutes(), reserved.overtimeDeltaMinutes(), reserved.roadDeltaMeters(), reserved.cost(), setId);
            jdbc.update("INSERT INTO slot_hold (id, \"offerToken\", \"jobId\", \"technicianId\", \"serviceDate\", \"windowStart\", \"windowEnd\", \"plannedStart\", \"plannedEnd\", \"insertPosition\", \"locationLat\", \"locationLng\", \"expiresAt\", \"offerSetId\") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    UUID.randomUUID().toString(), id, jobId, reserved.techId(), dayStamp(c.day()), stamp(c.start()), stamp(c.end()),
                    stamp(reserved.arrival()), stamp(reserved.arrival().plus(Duration.ofMinutes(job.duration()))), reserved.position(),
                    job.point().lat(), job.point().lng(), stamp(expiry), setId);
            result.add(new Offer(id, c.day().toString(), c.start(), c.end(), expiry));
        }
        return new Offers(jobId, result);
    }

    private Offers savedOffers(String jobId, String setId) {
        return new Offers(jobId, jdbc.query("SELECT id, \"serviceDate\", \"windowStart\", \"windowEnd\", \"expiresAt\" FROM booking_offer WHERE \"offerSetId\"=? ORDER BY \"createdAt\", id",
                (rs, n) -> new Offer(rs.getString(1), rs.getTimestamp(2).toInstant().atZone(ZoneOffset.UTC).toLocalDate().toString(),
                        rs.getTimestamp(3).toInstant(), rs.getTimestamp(4).toInstant(), rs.getTimestamp(5).toInstant()), setId));
    }

    @Transactional
    public Selection select(String jobId, String offerId) {
        lockJob(jobId);
        Job job = job(jobId);
        var selected = jdbc.query("SELECT s.\"selectedOfferId\", h.id, h.\"expiresAt\", h.\"releasedAt\" FROM booking_offer o JOIN booking_offer_set s ON s.id=o.\"offerSetId\" JOIN slot_hold h ON h.\"offerToken\"=o.id WHERE o.id=? AND o.\"jobId\"=? AND s.\"supersededAt\" IS NULL",
                (rs, n) -> new Object[]{rs.getString(1), rs.getString(2), rs.getTimestamp(3).toInstant(), rs.getTimestamp(4)}, offerId, jobId);
        if (selected.isEmpty()) throw new ResponseStatusException(HttpStatus.CONFLICT, "Offer expired");
        Object[] selectedRow = selected.getFirst();
        if (job.status().equals("SCHEDULED")) {
            if (!offerId.equals(selectedRow[0])) throw new ResponseStatusException(HttpStatus.CONFLICT, "A different offer was selected");
            Confirmation confirmed = appointment(jobId);
            return new Selection((String) selectedRow[1], (Instant) selectedRow[2], confirmed.appointmentId(), confirmed.windowStart(), confirmed.windowEnd());
        }
        if (!job.status().equals("PENDING") || selectedRow[3] != null || !((Instant) selectedRow[2]).isAfter(Instant.now()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Offer expired");
        roads.leg(job.point(), job.point());
        var rows = jdbc.query("SELECT o.\"serviceDate\", o.\"windowStart\", o.\"windowEnd\", o.\"offerSetId\" FROM booking_offer o JOIN booking_offer_set s ON s.id=o.\"offerSetId\" WHERE o.id=? AND o.\"jobId\"=? AND s.\"supersededAt\" IS NULL AND s.\"expiresAt\">CURRENT_TIMESTAMP",
                (rs, n) -> new Object[]{rs.getTimestamp(1).toInstant(), rs.getTimestamp(2).toInstant(), rs.getTimestamp(3).toInstant(), rs.getString(4)}, offerId, jobId);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.CONFLICT, "Offer expired");
        LocalDate day = rows.getFirst()[0] instanceof Instant date ? date.atZone(ZoneOffset.UTC).toLocalDate() : null;
        Instant start = (Instant) rows.getFirst()[1], end = (Instant) rows.getFirst()[2];
        String holdId = (String) selectedRow[1];
        List<Candidate> feasible = new ArrayList<>();
        for (Tech tech : technicians(job.serviceId(), day)) {
            lockDay(tech.id(), day);
            Candidate candidate = evaluateCandidate(job, tech, day, start, end);
            if (candidate != null) feasible.add(candidate);
        }
        Candidate chosen = feasible.stream().min(Comparator.comparingDouble(Candidate::cost).thenComparing(Candidate::techId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "Window no longer available"));
        jdbc.update("UPDATE slot_hold SET \"technicianId\"=?, \"plannedStart\"=?, \"plannedEnd\"=?, \"insertPosition\"=? WHERE id=?",
                chosen.techId(), stamp(chosen.arrival()), stamp(chosen.arrival().plus(Duration.ofMinutes(job.duration()))), chosen.position(), holdId);
        jdbc.update("UPDATE booking_offer_set SET \"selectedOfferId\"=? WHERE id=?", offerId, rows.getFirst()[3]);
        Confirmation confirmed = confirm(holdId);
        return new Selection(holdId, (Instant) selectedRow[2], confirmed.appointmentId(), confirmed.windowStart(), confirmed.windowEnd());
    }

    @Transactional
    public Confirmation confirm(String holdId) {
        var holds = jdbc.query("SELECT h.\"jobId\", h.\"technicianId\", h.\"serviceDate\", h.\"windowStart\", h.\"windowEnd\", h.\"expiresAt\", h.\"releasedAt\", h.\"offerToken\", s.\"selectedOfferId\", s.\"supersededAt\", h.\"offerSetId\" FROM slot_hold h LEFT JOIN booking_offer_set s ON s.id=h.\"offerSetId\" WHERE h.id=?",
                (rs, n) -> new Object[]{rs.getString(1), rs.getString(2), rs.getTimestamp(3).toInstant(), rs.getTimestamp(4).toInstant(), rs.getTimestamp(5).toInstant(), rs.getTimestamp(6).toInstant(), rs.getTimestamp(7), rs.getString(8), rs.getString(9), rs.getTimestamp(10), rs.getString(11)}, holdId);
        if (holds.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Hold not found");
        Object[] h = holds.getFirst();
        String jobId = (String) h[0], techId = (String) h[1];
        LocalDate day = ((Instant) h[2]).atZone(ZoneOffset.UTC).toLocalDate();
        Instant start = (Instant) h[3], end = (Instant) h[4];
        lockJob(jobId);
        lockDay(techId, day);
        if (h[10] != null && (!Objects.equals(h[8], h[7]) || h[9] != null))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A different offer was selected");
        var existing = jdbc.query("SELECT id FROM appointment WHERE \"jobId\"=? AND \"cancelledAt\" IS NULL", (rs, n) -> rs.getString(1), jobId);
        if (!existing.isEmpty()) return appointment(jobId);
        if (h[6] != null) throw new ResponseStatusException(HttpStatus.CONFLICT, "Hold released");
        if (!((Instant) h[5]).isAfter(Instant.now())) throw new ResponseStatusException(HttpStatus.CONFLICT, "Hold expired");
        Job job = job(jobId);
        roads.leg(job.point(), job.point());
        Tech tech = technicians(job.serviceId(), day).stream().filter(t -> t.id().equals(techId)).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "Technician unavailable"));
        Candidate candidate = evaluateCandidate(job, tech, day, start, end);
        if (candidate == null) throw new ResponseStatusException(HttpStatus.CONFLICT, "Window no longer available");
        String id = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO appointment (id, \"jobId\", \"technicianId\", \"serviceDate\", \"windowStart\", \"windowEnd\", \"plannedStart\", \"plannedEnd\", sequence, \"updatedAt\") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)",
                id, jobId, techId, dayStamp(day), stamp(start), stamp(end), stamp(candidate.arrival()),
                stamp(candidate.arrival().plus(Duration.ofMinutes(job.duration()))), candidate.position());
        List<Visit> route = visits(techId, day, jobId);
        route.add(Math.min(candidate.position(), route.size()), new Visit(id, job.point(), start, end,
                candidate.arrival(), job.duration(), true));
        Metrics planned = evaluate(tech, day, route);
        if (!planned.feasible()) throw new ResponseStatusException(HttpStatus.CONFLICT, "Window no longer available");
        for (int index = 0; index < route.size(); index++) {
            Visit visit = route.get(index);
            Instant arrival = planned.arrivals().get(visit.id());
            jdbc.update("UPDATE appointment SET sequence=?, \"plannedStart\"=?, \"plannedEnd\"=?, \"updatedAt\"=CURRENT_TIMESTAMP WHERE id=?",
                    index, stamp(arrival), stamp(arrival.plus(Duration.ofMinutes(visit.duration()))), visit.id());
        }
        jdbc.update("UPDATE job SET status='SCHEDULED', \"updatedAt\"=CURRENT_TIMESTAMP WHERE id=?", jobId);
        jdbc.update("UPDATE job SET \"manualFollowUpStatus\"=NULL, \"manualFollowUpReason\"=NULL WHERE id=?", jobId);
        jdbc.update("UPDATE slot_hold SET \"releasedAt\"=CURRENT_TIMESTAMP WHERE \"jobId\"=? AND \"releasedAt\" IS NULL", jobId);
        jdbc.update("UPDATE schedule_day SET version=version+1 WHERE \"technicianId\"=? AND \"serviceDate\"=?", techId, dayStamp(day));
        return new Confirmation(id, start, end);
    }

    private Confirmation appointment(String jobId) {
        return jdbc.query("SELECT id, \"windowStart\", \"windowEnd\" FROM appointment WHERE \"jobId\"=? AND \"cancelledAt\" IS NULL",
                (rs, n) -> new Confirmation(rs.getString(1), rs.getTimestamp(2).toInstant(), rs.getTimestamp(3).toInstant()), jobId).getFirst();
    }

    @Transactional
    public Map<String, Object> cancel(String appointmentId, String reason) {
        if (reason == null || reason.isBlank() || reason.length() > 500)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cancellation reason required");
        var rows = jdbc.query("SELECT \"jobId\", \"technicianId\", \"serviceDate\", \"cancelledAt\" FROM appointment WHERE id=?",
                (rs, n) -> new Object[]{rs.getString(1), rs.getString(2), rs.getTimestamp(3).toInstant(), rs.getTimestamp(4)}, appointmentId);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Appointment not found");
        Object[] row = rows.getFirst();
        String jobId = (String) row[0], techId = (String) row[1];
        LocalDate day = ((Instant) row[2]).atZone(ZoneOffset.UTC).toLocalDate();
        lockJob(jobId);
        lockDay(techId, day);
        if (jdbc.queryForObject("SELECT \"cancelledAt\" IS NOT NULL FROM appointment WHERE id=?", Boolean.class, appointmentId))
            return Map.of("success", true, "appointmentId", appointmentId, "alreadyCancelled", true);
        jdbc.update("UPDATE appointment SET \"cancelledAt\"=CURRENT_TIMESTAMP, \"cancellationReason\"=?, \"updatedAt\"=CURRENT_TIMESTAMP WHERE id=?", reason.trim(), appointmentId);
        jdbc.update("UPDATE job SET status='CANCELLED', \"updatedAt\"=CURRENT_TIMESTAMP WHERE id=?", jobId);
        if (!ScheduleCutoff.frozen(day, Instant.now())) {
            List<Visit> remaining = visits(techId, day, jobId);
            var techs = jdbc.query("SELECT \"homeLat\", \"homeLng\", COALESCE(o.\"shiftStartMin\",t.\"shiftStartMin\"), COALESCE(o.\"shiftEndMin\",t.\"shiftEndMin\"), \"maxDailyMinutes\", \"maxOvertimeMinutes\" FROM technician t LEFT JOIN technician_shift_override o ON o.\"technicianId\"=t.id AND o.\"serviceDate\"=? WHERE t.id=?",
                    (rs, n) -> new Tech(techId, new RoadClient.Point(rs.getDouble(1), rs.getDouble(2)), rs.getInt(3), rs.getInt(4), rs.getInt(5), rs.getInt(6)), dayStamp(day), techId);
            if (!techs.isEmpty()) {
                Metrics recalculated = evaluate(techs.getFirst(), day, remaining);
                if (recalculated.feasible()) for (int i = 0; i < remaining.size(); i++) {
                    Visit visit = remaining.get(i);
                    Instant arrival = recalculated.arrivals().get(visit.id());
                    if (arrival != null && jdbc.update("UPDATE appointment SET sequence=?, \"plannedStart\"=?, \"plannedEnd\"=?, \"updatedAt\"=CURRENT_TIMESTAMP WHERE id=? AND \"cancelledAt\" IS NULL",
                            i, stamp(arrival), stamp(arrival.plus(Duration.ofMinutes(visit.duration()))), visit.id()) > 0) { /* active appointment */ }
                }
            }
        }
        jdbc.update("UPDATE schedule_day SET version=version+1 WHERE \"technicianId\"=? AND \"serviceDate\"=?", techId, dayStamp(day));
        return Map.of("success", true, "appointmentId", appointmentId, "alreadyCancelled", false);
    }

    private List<Candidate> candidates(Job job, LocalDate onlyDay, Instant onlyStart) {
        List<Candidate> result = new ArrayList<>();
        LocalDate day = onlyDay == null ? LocalDate.now(CHICAGO).plusDays(1) : onlyDay;
        int weekdays = 0;
        while (weekdays < (onlyDay == null ? 10 : 1)) {
            if (day.getDayOfWeek().getValue() <= 5) {
                weekdays++;
                for (Tech tech : technicians(job.serviceId(), day)) {
                    for (int hour = 8; hour <= 15; hour++) {
                        Instant start = day.atTime(hour, 0).atZone(CHICAGO).toInstant();
                        if (onlyStart != null && !start.equals(onlyStart)) continue;
                        Candidate c = evaluateCandidate(job, tech, day, start, start.plus(Duration.ofHours(2)));
                        if (c != null) result.add(c);
                    }
                }
            }
            day = day.plusDays(1);
        }
        return result;
    }

    private Candidate evaluateCandidate(Job job, Tech tech, LocalDate day, Instant start, Instant end) {
        List<Visit> visits = visits(tech.id(), day, job.id());
        Metrics baseline = evaluate(tech, day, visits);
        if (!baseline.feasible()) return null;
        Map<String, Double> settings = settings();
        Candidate best = null;
        for (int position = 0; position <= visits.size(); position++) {
            List<Visit> proposal = new ArrayList<>(visits);
            proposal.add(position, new Visit(job.id(), job.point(), start, end, start, job.duration(), true));
            Metrics m = evaluate(tech, day, proposal);
            if (!m.feasible() || m.newArrival() == null) continue;
            double paidDelta = m.paidMinutes() - baseline.paidMinutes();
            double overtimeDelta = m.overtimeMinutes() - baseline.overtimeMinutes();
            double regularRate = settings.getOrDefault("regular_hourly_dollars", 30.0);
            double overtimeRate = settings.getOrDefault("overtime_hourly_dollars", 45.0);
            double mileageRate = settings.getOrDefault("mileage_dollars_per_mile", 0.67);
            double cost = (paidDelta - overtimeDelta) * regularRate / 60.0 + overtimeDelta * overtimeRate / 60.0
                    + (m.meters() - baseline.meters()) / 1609.344 * mileageRate;
            Candidate c = new Candidate(tech.id(), day, start, end, m.newArrival(), position, cost,
                    (long) (paidDelta - overtimeDelta), (long) overtimeDelta, m.meters() - baseline.meters());
            if (best == null || c.cost() < best.cost()) best = c;
        }
        return best;
    }

    private Metrics evaluate(Tech tech, LocalDate day, List<Visit> visits) {
        if (visits.isEmpty()) return new Metrics(true, null, 0, 0, 0, Map.of());
        Map<String, Double> settings = settings();
        Instant shiftStart = ScheduleCutoff.localMinute(day, tech.shiftStart(), false);
        Instant shiftEnd = ScheduleCutoff.localMinute(day, tech.shiftEnd(), true);
        TechRoute route = new TechRoute(tech.id(), shiftStart, shiftEnd, tech.maxDaily(), tech.maxOvertime(), Set.of("BOOKING"));
        jdbc.query("SELECT i.\"startMin\", i.\"endMin\" FROM time_off_interval i JOIN time_off_request r ON r.id=i.\"requestId\" WHERE r.status='APPROVED' AND r.\"technicianId\"=? AND i.\"serviceDate\"=? ORDER BY i.\"startMin\"",
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> route.getUnavailable().add(new TechRoute.Unavailable(
                        ScheduleCutoff.localMinute(day, rs.getInt(1), false),
                        ScheduleCutoff.localMinute(day, rs.getInt(2), true))), tech.id(), dayStamp(day));
        Map<String, RoadClient.Point> points = new LinkedHashMap<>();
        points.put(tech.id(), tech.home());
        for (Visit visit : visits) {
            route.getVisits().add(new PlanVisit(visit.id(), "BOOKING", visit.start(), visit.end(), visit.duration(), tech.id(), visit.planned()));
            points.put(visit.id(), visit.point());
        }
        Map<String, DayPlan.RoadLeg> matrix = new HashMap<>();
        for (var origin : points.entrySet()) for (var destination : points.entrySet()) {
            if (origin.getKey().equals(destination.getKey())) continue;
            RoadClient.Leg leg = roads.leg(origin.getValue(), destination.getValue());
            matrix.put(origin.getKey() + ">" + destination.getKey(), new DayPlan.RoadLeg(leg.seconds(), leg.meters()));
        }
        DayPlan plan = new DayPlan(List.of(route), route.getVisits(), matrix,
                settings.getOrDefault("regular_hourly_dollars", 30.0), settings.getOrDefault("overtime_hourly_dollars", 45.0),
                settings.getOrDefault("mileage_dollars_per_mile", 0.67), settings.getOrDefault("travel_buffer_pct", 0.2),
                Math.round(settings.getOrDefault("travel_buffer_minutes_per_leg", 5.0)));
        var result = DayScoreCalculator.evaluate(plan);
        Instant newArrival = visits.stream().filter(Visit::newJob).findFirst().map(v -> result.arrivals().get(v.id())).orElse(null);
        return new Metrics(result.hardPenalty() == 0, newArrival, result.paidMinutes(), result.overtimeMinutes(), result.meters(), result.arrivals());
    }

    private Job job(String id) {
        var rows = jdbc.query("SELECT j.id, j.\"serviceId\", j.\"durationMin\", a.lat, a.lng, j.status::text FROM job j JOIN address a ON a.id=j.\"addressId\" WHERE j.id=?",
                (rs, n) -> new Job(rs.getString(1), rs.getString(2), rs.getInt(3), new RoadClient.Point(rs.getDouble(4), rs.getDouble(5)), rs.getString(6)), id);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Job not found");
        return rows.getFirst();
    }

    private List<Tech> technicians(String serviceId, LocalDate day) {
        return jdbc.query("SELECT t.id, t.\"homeLat\", t.\"homeLng\", COALESCE(o.\"shiftStartMin\",t.\"shiftStartMin\"), COALESCE(o.\"shiftEndMin\",t.\"shiftEndMin\"), t.\"maxDailyMinutes\", t.\"maxOvertimeMinutes\" FROM technician t JOIN technician_qualification q ON q.\"technicianId\"=t.id AND q.\"serviceId\"=? LEFT JOIN technician_shift_override o ON o.\"technicianId\"=t.id AND o.\"serviceDate\"=? WHERE t.active=true AND COALESCE(o.available,true)=true ORDER BY t.id FOR SHARE OF t",
                (rs, n) -> new Tech(rs.getString(1), new RoadClient.Point(rs.getDouble(2), rs.getDouble(3)), rs.getInt(4), rs.getInt(5), rs.getInt(6), rs.getInt(7)), serviceId, dayStamp(day));
    }

    private List<Visit> visits(String techId, LocalDate day, String excludeJobId) {
        List<Visit> result = new ArrayList<>(jdbc.query("SELECT a.id, ad.lat, ad.lng, a.\"windowStart\", a.\"windowEnd\", a.\"plannedStart\", j.\"durationMin\" FROM appointment a JOIN job j ON j.id=a.\"jobId\" JOIN address ad ON ad.id=j.\"addressId\" WHERE a.\"technicianId\"=? AND a.\"serviceDate\"=? AND a.\"cancelledAt\" IS NULL AND (? IS NULL OR a.\"jobId\"<>?) ORDER BY a.sequence, a.\"plannedStart\"",
                (rs, n) -> new Visit(rs.getString(1), new RoadClient.Point(rs.getDouble(2), rs.getDouble(3)), rs.getTimestamp(4).toInstant(), rs.getTimestamp(5).toInstant(), rs.getTimestamp(6).toInstant(), rs.getInt(7), false), techId, dayStamp(day), excludeJobId, excludeJobId));
        result.addAll(jdbc.query("SELECT h.id, h.\"locationLat\", h.\"locationLng\", h.\"windowStart\", h.\"windowEnd\", h.\"plannedStart\", j.\"durationMin\" FROM slot_hold h JOIN job j ON j.id=h.\"jobId\" WHERE h.\"technicianId\"=? AND h.\"serviceDate\"=? AND h.\"jobId\"<>? AND h.\"releasedAt\" IS NULL AND h.\"expiresAt\">CURRENT_TIMESTAMP ORDER BY h.\"plannedStart\"",
                (rs, n) -> new Visit(rs.getString(1), new RoadClient.Point(rs.getDouble(2), rs.getDouble(3)), rs.getTimestamp(4).toInstant(), rs.getTimestamp(5).toInstant(), rs.getTimestamp(6).toInstant(), rs.getInt(7), false), techId, dayStamp(day), excludeJobId));
        result.sort(Comparator.comparing(Visit::planned));
        return result;
    }

    private Map<String, Double> settings() {
        Map<String, Double> values = new HashMap<>();
        jdbc.query("SELECT key, value FROM omaha_setting", (org.springframework.jdbc.core.RowCallbackHandler) rs -> values.put(rs.getString(1), rs.getDouble(2)));
        return values;
    }

    private void lockDay(String techId, LocalDate day) {
        jdbc.update("INSERT INTO schedule_day (id, \"technicianId\", \"serviceDate\", version) VALUES (?, ?, ?, 0) ON CONFLICT (\"technicianId\", \"serviceDate\") DO NOTHING", UUID.randomUUID().toString(), techId, dayStamp(day));
        jdbc.queryForObject("SELECT version FROM schedule_day WHERE \"technicianId\"=? AND \"serviceDate\"=? FOR UPDATE", Integer.class, techId, dayStamp(day));
    }

    private void lockJob(String jobId) {
        var rows = jdbc.query("SELECT id FROM job WHERE id=? FOR UPDATE", (rs, n) -> rs.getString(1), jobId);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Job not found");
    }

    private static Timestamp stamp(Instant time) { return Timestamp.from(time); }
    private static Timestamp dayStamp(LocalDate day) { return stamp(day.atStartOfDay(ZoneOffset.UTC).toInstant()); }
}
