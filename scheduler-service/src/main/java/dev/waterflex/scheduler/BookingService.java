package dev.waterflex.scheduler;

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
    public record Selection(String holdId, Instant expiresAt) { }
    public record Confirmation(String appointmentId, Instant windowStart, Instant windowEnd) { }
    public record Candidate(String techId, LocalDate day, Instant start, Instant end, Instant arrival,
                            int position, double cost, long regularDeltaMinutes, long overtimeDeltaMinutes, long roadDeltaMeters) { }
    private record Job(String id, String serviceId, int duration, RoadClient.Point point, String status) { }
    private record Tech(String id, RoadClient.Point home, int shiftStart, int shiftEnd, int maxDaily, int maxOvertime) { }
    private record Visit(String id, RoadClient.Point point, Instant start, Instant end, Instant planned, int duration, boolean newJob) { }
    private record Metrics(boolean feasible, Instant newArrival, long paidMinutes, long overtimeMinutes,
                           long meters, Map<String, Instant> arrivals) { }

    public BookingService(JdbcTemplate jdbc, RoadClient roads) { this.jdbc = jdbc; this.roads = roads; }

    public Offers offers(String jobId) {
        Job job = job(jobId);
        if (!job.status().equals("PENDING")) throw new ResponseStatusException(HttpStatus.CONFLICT, "Job already booked");
        roads.leg(job.point(), job.point());
        List<Candidate> candidates = candidates(job, null, null);
        LinkedHashMap<String, Candidate> windows = new LinkedHashMap<>();
        candidates.stream().sorted(Comparator.comparingDouble(Candidate::cost).thenComparing(Candidate::start))
                .forEach(c -> windows.putIfAbsent(c.start().toString(), c));
        List<Offer> result = new ArrayList<>();
        Instant expiry = Instant.now().plus(Duration.ofMinutes(15));
        for (Candidate c : windows.values()) {
            if (result.size() == 4) break;
            String id = UUID.randomUUID().toString();
            jdbc.update("INSERT INTO booking_offer (id, \"jobId\", \"serviceDate\", \"windowStart\", \"windowEnd\", \"expiresAt\", \"incrementalRegularMinutes\", \"incrementalOvertimeMinutes\", \"incrementalRoadMeters\", \"incrementalCostDollars\") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    id, jobId, dayStamp(c.day()), stamp(c.start()), stamp(c.end()), stamp(expiry),
                    c.regularDeltaMinutes(), c.overtimeDeltaMinutes(), c.roadDeltaMeters(), c.cost());
            result.add(new Offer(id, c.day().toString(), c.start(), c.end(), expiry));
        }
        return new Offers(jobId, result);
    }

    @Transactional
    public Selection select(String jobId, String offerId) {
        lockJob(jobId);
        Job job = job(jobId);
        if (!job.status().equals("PENDING")) throw new ResponseStatusException(HttpStatus.CONFLICT, "Job already booked");
        roads.leg(job.point(), job.point());
        var rows = jdbc.query("SELECT \"serviceDate\", \"windowStart\", \"windowEnd\" FROM booking_offer WHERE id=? AND \"jobId\"=? AND \"expiresAt\">CURRENT_TIMESTAMP",
                (rs, n) -> new Object[]{rs.getTimestamp(1).toInstant(), rs.getTimestamp(2).toInstant(), rs.getTimestamp(3).toInstant()}, offerId, jobId);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.CONFLICT, "Offer expired");
        LocalDate day = rows.getFirst()[0] instanceof Instant date ? date.atZone(ZoneOffset.UTC).toLocalDate() : null;
        Instant start = (Instant) rows.getFirst()[1], end = (Instant) rows.getFirst()[2];
        var existingHolds = jdbc.query("SELECT id, \"expiresAt\" FROM slot_hold WHERE \"jobId\"=? AND \"offerToken\"=? AND \"releasedAt\" IS NULL AND \"expiresAt\">CURRENT_TIMESTAMP ORDER BY \"createdAt\" LIMIT 1",
                (rs, n) -> new Selection(rs.getString(1), rs.getTimestamp(2).toInstant()), jobId, offerId);
        if (!existingHolds.isEmpty()) return existingHolds.getFirst();
        for (Tech tech : technicians(job.serviceId(), day)) {
            lockDay(tech.id(), day);
            Candidate candidate = evaluateCandidate(job, tech, day, start, end);
            if (candidate == null) continue;
            String id = UUID.randomUUID().toString();
            Instant expires = Instant.now().plus(Duration.ofMinutes(10));
            jdbc.update("INSERT INTO slot_hold (id, \"offerToken\", \"jobId\", \"technicianId\", \"serviceDate\", \"windowStart\", \"windowEnd\", \"plannedStart\", \"plannedEnd\", \"insertPosition\", \"locationLat\", \"locationLng\", \"expiresAt\") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    id, offerId, jobId, tech.id(), dayStamp(day), stamp(start), stamp(end), stamp(candidate.arrival()),
                    stamp(candidate.arrival().plus(Duration.ofMinutes(job.duration()))), candidate.position(), job.point().lat(), job.point().lng(), stamp(expires));
            jdbc.update("UPDATE slot_hold SET \"releasedAt\"=CURRENT_TIMESTAMP WHERE \"jobId\"=? AND id<>? AND \"releasedAt\" IS NULL", jobId, id);
            return new Selection(id, expires);
        }
        throw new ResponseStatusException(HttpStatus.CONFLICT, "Window no longer available");
    }

    @Transactional
    public Confirmation confirm(String holdId) {
        var holds = jdbc.query("SELECT \"jobId\", \"technicianId\", \"serviceDate\", \"windowStart\", \"windowEnd\", \"expiresAt\" FROM slot_hold WHERE id=?",
                (rs, n) -> new Object[]{rs.getString(1), rs.getString(2), rs.getTimestamp(3).toInstant(), rs.getTimestamp(4).toInstant(), rs.getTimestamp(5).toInstant(), rs.getTimestamp(6).toInstant()}, holdId);
        if (holds.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Hold not found");
        Object[] h = holds.getFirst();
        String jobId = (String) h[0], techId = (String) h[1];
        LocalDate day = ((Instant) h[2]).atZone(ZoneOffset.UTC).toLocalDate();
        Instant start = (Instant) h[3], end = (Instant) h[4];
        lockJob(jobId);
        lockDay(techId, day);
        var existing = jdbc.query("SELECT id, \"windowStart\", \"windowEnd\" FROM appointment WHERE \"jobId\"=?",
                (rs, n) -> new Confirmation(rs.getString(1), rs.getTimestamp(2).toInstant(), rs.getTimestamp(3).toInstant()), jobId);
        if (!existing.isEmpty()) return existing.getFirst();
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
        jdbc.update("UPDATE slot_hold SET \"releasedAt\"=CURRENT_TIMESTAMP WHERE \"jobId\"=? AND \"releasedAt\" IS NULL", jobId);
        jdbc.update("UPDATE schedule_day SET version=version+1 WHERE \"technicianId\"=? AND \"serviceDate\"=?", techId, dayStamp(day));
        return new Confirmation(id, start, end);
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
        double pct = settings.getOrDefault("travel_buffer_pct", 0.2);
        long extra = Math.round(settings.getOrDefault("travel_buffer_minutes_per_leg", 5.0));
        Instant shiftStart = day.atStartOfDay(CHICAGO).plusMinutes(tech.shiftStart()).toInstant();
        Instant shiftEnd = day.atStartOfDay(CHICAGO).plusMinutes(tech.shiftEnd()).toInstant();
        RoadClient.Point location = tech.home();
        long meters = 0;
        Map<String, Instant> arrivals = new HashMap<>();
        Instant newArrival = null;
        Instant now = shiftStart;
        Instant departure = shiftStart;
        for (int i = 0; i < visits.size(); i++) {
            Visit v = visits.get(i);
            RoadClient.Leg leg;
            try { leg = roads.leg(location, v.point()); }
            catch (RoadClient.RoadUnavailable e) { if (e.getMessage().contains("unavailable") || e.getMessage().contains("unprepared")) throw e; return new Metrics(false, null, 0, 0, 0, Map.of()); }
            long travelMinutes = (long) Math.ceil(leg.seconds() * (1 + pct) / 60.0) + extra;
            meters += leg.meters();
            if (i == 0) {
                departure = v.start().minus(Duration.ofMinutes(travelMinutes));
                if (departure.isBefore(shiftStart)) departure = shiftStart;
                now = departure;
            }
            now = now.plus(Duration.ofMinutes(travelMinutes));
            if (now.isBefore(v.start())) now = v.start();
            if (!now.isBefore(v.end())) return new Metrics(false, null, 0, 0, 0, Map.of());
            arrivals.put(v.id(), now);
            if (v.newJob()) newArrival = now;
            now = now.plus(Duration.ofMinutes(v.duration()));
            location = v.point();
        }
        try {
            RoadClient.Leg home = roads.leg(location, tech.home());
            meters += home.meters();
            now = now.plus(Duration.ofMinutes((long) Math.ceil(home.seconds() * (1 + pct) / 60.0) + extra));
        } catch (RoadClient.RoadUnavailable e) { if (e.getMessage().contains("unavailable") || e.getMessage().contains("unprepared")) throw e; return new Metrics(false, null, 0, 0, 0, Map.of()); }
        long paid = Duration.between(departure, now).toMinutes();
        long overtime = Math.max(0, Duration.between(shiftEnd, now).toMinutes());
        boolean feasible = paid <= tech.maxDaily() && overtime <= tech.maxOvertime();
        return new Metrics(feasible, newArrival, paid, overtime, meters, arrivals);
    }

    private Job job(String id) {
        var rows = jdbc.query("SELECT j.id, j.\"serviceId\", j.\"durationMin\", a.lat, a.lng, j.status::text FROM job j JOIN address a ON a.id=j.\"addressId\" WHERE j.id=?",
                (rs, n) -> new Job(rs.getString(1), rs.getString(2), rs.getInt(3), new RoadClient.Point(rs.getDouble(4), rs.getDouble(5)), rs.getString(6)), id);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Job not found");
        return rows.getFirst();
    }

    private List<Tech> technicians(String serviceId, LocalDate day) {
        return jdbc.query("SELECT t.id, t.\"homeLat\", t.\"homeLng\", COALESCE(o.\"shiftStartMin\",t.\"shiftStartMin\"), COALESCE(o.\"shiftEndMin\",t.\"shiftEndMin\"), t.\"maxDailyMinutes\", t.\"maxOvertimeMinutes\" FROM technician t JOIN technician_qualification q ON q.\"technicianId\"=t.id AND q.\"serviceId\"=? LEFT JOIN technician_shift_override o ON o.\"technicianId\"=t.id AND o.\"serviceDate\"=? WHERE t.active=true AND COALESCE(o.available,true)=true ORDER BY t.id",
                (rs, n) -> new Tech(rs.getString(1), new RoadClient.Point(rs.getDouble(2), rs.getDouble(3)), rs.getInt(4), rs.getInt(5), rs.getInt(6), rs.getInt(7)), serviceId, dayStamp(day));
    }

    private List<Visit> visits(String techId, LocalDate day, String excludeJobId) {
        List<Visit> result = new ArrayList<>(jdbc.query("SELECT a.id, ad.lat, ad.lng, a.\"windowStart\", a.\"windowEnd\", a.\"plannedStart\", j.\"durationMin\" FROM appointment a JOIN job j ON j.id=a.\"jobId\" JOIN address ad ON ad.id=j.\"addressId\" WHERE a.\"technicianId\"=? AND a.\"serviceDate\"=? AND (? IS NULL OR a.\"jobId\"<>?) ORDER BY a.sequence, a.\"plannedStart\"",
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
