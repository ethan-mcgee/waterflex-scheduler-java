package dev.waterflex.scheduler;

import org.jspecify.annotations.Nullable;

import dev.waterflex.scheduler.optimizer.DayPlan;
import dev.waterflex.scheduler.optimizer.RouteEvaluator;
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
    private static final ZoneId CHICAGO = Required.value(ZoneId.of("America/Chicago"));
    private final JdbcTemplate jdbc;
    private final RoadClient roads;

    public record Offer(String offerId, String date, Instant windowStart, Instant windowEnd, Instant expiresAt) { }
    public record Offers(String jobId, List<Offer> offers) { }
    public record Selection(String holdId, Instant expiresAt, String appointmentId, Instant windowStart, Instant windowEnd) { }
    public record Confirmation(String appointmentId, Instant windowStart, Instant windowEnd) { }
    public record Candidate(String techId, LocalDate day, Instant start, Instant end, Instant arrival,
                            int position, double cost, long regularDeltaMinutes, long overtimeDeltaMinutes, long roadDeltaMeters) { }
    static final Comparator<Candidate> INSERTION_ORDER = Required.value(Comparator.comparingLong((Candidate candidate) -> candidate.overtimeDeltaMinutes())
            .thenComparingDouble(candidate -> candidate.cost())
            .thenComparing(candidate -> candidate.start())
            .thenComparing(candidate -> candidate.techId())
            .thenComparingInt(candidate -> candidate.position()));
    private record SelectedOffer(@Nullable String selectedOfferId, String holdId, Instant expiresAt, @Nullable Timestamp releasedAt) { }
    private record ReleasableSet(String id, @Nullable Timestamp supersededAt) { }
    private record OfferWindow(Instant day, Instant start, Instant end, String setId) { }
    private record Hold(String jobId, String techId, Instant day, Instant start, Instant end, Instant expiresAt, @Nullable Timestamp releasedAt, String offerToken, @Nullable String selectedOfferId, @Nullable Timestamp supersededAt, @Nullable String offerSetId) { }
    private record Cancellation(String jobId, String techId, Instant day, @Nullable Timestamp cancelledAt) { }
    private record Job(String id, String serviceId, int duration, RoadClient.Point point, String status, String metroId) { }
    private record ServiceDepot(String metroId, double lat, double lng, double radiusMi) { }
    private record Tech(String id, RouteEndpoints endpoints, int shiftStart, int shiftEnd, int maxDaily, int maxOvertime) { }
    private record TechBase(String id, RouteEndpoints endpoints, int maxDaily, int maxOvertime) { }
    private record Visit(String id, RoadClient.Point point, Instant start, Instant end, Instant planned, int duration, boolean newJob) { }
    private record EvaluationContext(List<TechRoute.Unavailable> absences, Map<String, DayPlan.RoadLeg> matrix,
                                     Map<String, Double> settings) { }
    private record Metrics(boolean feasible, @Nullable Instant newArrival, long paidMinutes, long overtimeMinutes,
                           long meters, long costCents, Map<String, Instant> arrivals) { }

    public BookingService(JdbcTemplate jdbc, RoadClient roads) { this.jdbc = jdbc; this.roads = roads; }

    @Transactional(timeout = 5)
    public Offers offers(String jobId, boolean refresh) {
        SearchDeadline.database(jdbc);
        org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                new org.springframework.transaction.support.TransactionSynchronization() {
                    @Override public void beforeCommit(boolean readOnly) { SearchDeadline.checkpoint(); }
                });
        lockJob(jobId);
        Job job = job(jobId);
        if (!job.status().equals("PENDING")) throw new ResponseStatusException(HttpStatus.CONFLICT, "Job already booked");
        var active = jdbc.query("SELECT id FROM booking_offer_set WHERE \"jobId\"=? AND \"expiresAt\">CURRENT_TIMESTAMP AND \"supersededAt\" IS NULL ORDER BY \"createdAt\" DESC LIMIT 1",
                (rs, _) -> Required.string(rs, 1), jobId);
        if (!active.isEmpty() && !refresh) return savedOffers(jobId, Required.value(active.getFirst()));
        if (!active.isEmpty()) {
            jdbc.update("UPDATE booking_offer_set SET \"supersededAt\"=CURRENT_TIMESTAMP WHERE id=?", active.getFirst());
            jdbc.update("UPDATE slot_hold SET \"releasedAt\"=CURRENT_TIMESTAMP WHERE \"offerSetId\"=? AND \"releasedAt\" IS NULL", active.getFirst());
        }
        roads.matrix(Required.value(Map.<String, RoadClient.Point>of("job", job.point())));
        List<Candidate> candidates = candidates(job, null, null);
        SearchDeadline.beginCommit();
        LinkedHashMap<String, Candidate> windows = new LinkedHashMap<>();
        candidates.stream().filter(candidate -> candidate.overtimeDeltaMinutes() <= 0)
                .sorted(INSERTION_ORDER)
                .forEach(c -> windows.putIfAbsent(c.start().toString(), c));
        List<Offer> result = new ArrayList<>();
        Instant expiry = Instant.now().plus(Duration.ofMinutes(10));
        String setId = UUID.randomUUID().toString();
        SearchDeadline.database(jdbc);
        jdbc.update("INSERT INTO booking_offer_set (id, \"jobId\", \"expiresAt\") VALUES (?, ?, ?)", setId, jobId, stamp(Required.value(expiry)));
        for (Candidate c : windows.values()) {
            if (result.size() == 4) break;
            SearchDeadline.database(jdbc);
            lockDay(c.techId(), c.day());
            Tech tech = technicians(job.serviceId(), c.day(), job.metroId()).stream().filter(t -> t.id().equals(c.techId())).findFirst().orElse(null);
            Candidate reserved = tech == null ? null : evaluateCandidate(job, tech, c.day(), c.start(), c.end());
            if (reserved == null || reserved.overtimeDeltaMinutes() > 0) continue;
            String id = UUID.randomUUID().toString();
            jdbc.update("INSERT INTO booking_offer (id, \"jobId\", \"serviceDate\", \"windowStart\", \"windowEnd\", \"expiresAt\", \"incrementalRegularMinutes\", \"incrementalOvertimeMinutes\", \"incrementalRoadMeters\", \"incrementalCostDollars\", \"offerSetId\") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    id, jobId, dayStamp(c.day()), stamp(c.start()), stamp(c.end()), stamp(Required.value(expiry)),
                    c.regularDeltaMinutes(), c.overtimeDeltaMinutes(), c.roadDeltaMeters(), c.cost(), setId);
            jdbc.update("INSERT INTO slot_hold (id, \"offerToken\", \"jobId\", \"technicianId\", \"serviceDate\", \"windowStart\", \"windowEnd\", \"plannedStart\", \"plannedEnd\", \"insertPosition\", \"locationLat\", \"locationLng\", \"expiresAt\", \"offerSetId\") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    UUID.randomUUID().toString(), id, jobId, reserved.techId(), dayStamp(c.day()), stamp(c.start()), stamp(c.end()),
                    stamp(reserved.arrival()), stamp(Required.value(reserved.arrival().plus(Duration.ofMinutes(job.duration())))), reserved.position(),
                    job.point().lat(), job.point().lng(), stamp(Required.value(expiry)), setId);
            result.add(new Offer(Required.value(id), Required.value(c.day().toString()), c.start(), c.end(), Required.value(expiry)));
        }
        return new Offers(jobId, result);
    }

    private Offers savedOffers(String jobId, String setId) {
        return new Offers(jobId, jdbc.query("SELECT id, \"serviceDate\", \"windowStart\", \"windowEnd\", \"expiresAt\" FROM booking_offer WHERE \"offerSetId\"=? ORDER BY \"createdAt\", id",
                (rs, _) -> new Offer(Required.string(rs, 1), Required.value(Required.timestamp(rs, 2).toInstant().atZone(ZoneOffset.UTC).toLocalDate().toString()),
                        Required.value(Required.timestamp(rs, 3).toInstant()), Required.value(Required.timestamp(rs, 4).toInstant()), Required.value(Required.timestamp(rs, 5).toInstant())), setId));
    }

    @Transactional
    public Map<String, Boolean> release(String jobId, String offerId) {
        lockJob(jobId);
        String status = Required.query(jdbc, "SELECT status::text FROM job WHERE id=?", String.class, jobId);
        if (!status.equals("PENDING")) throw new ResponseStatusException(HttpStatus.CONFLICT, "Job already booked");
        var sets = jdbc.query("SELECT s.id, s.\"supersededAt\" FROM booking_offer o JOIN booking_offer_set s ON s.id=o.\"offerSetId\" WHERE o.id=? AND o.\"jobId\"=? AND s.\"jobId\"=?",
                (rs, _) -> new ReleasableSet(Required.string(rs, 1), rs.getTimestamp(2)), offerId, jobId, jobId);
        if (sets.isEmpty()) throw new ResponseStatusException(HttpStatus.CONFLICT, "Offer does not belong to job");
        ReleasableSet set = sets.getFirst();
        if (set.supersededAt() == null) {
            jdbc.update("UPDATE booking_offer_set SET \"supersededAt\"=CURRENT_TIMESTAMP WHERE id=?", set.id());
            jdbc.update("UPDATE slot_hold SET \"releasedAt\"=CURRENT_TIMESTAMP WHERE \"offerSetId\"=? AND \"releasedAt\" IS NULL", set.id());
        }
        return Required.value(Map.of("success", true));
    }

    @Transactional
    public Selection select(String jobId, String offerId) {
        lockJob(jobId);
        Job job = job(jobId, HttpStatus.CONFLICT);
        var selected = jdbc.query("SELECT s.\"selectedOfferId\", h.id, h.\"expiresAt\", h.\"releasedAt\" FROM booking_offer o JOIN booking_offer_set s ON s.id=o.\"offerSetId\" JOIN slot_hold h ON h.\"offerToken\"=o.id WHERE o.id=? AND o.\"jobId\"=? AND s.\"supersededAt\" IS NULL",
                (rs, _) -> new SelectedOffer(rs.getString(1), Required.string(rs, 2), Required.value(Required.timestamp(rs, 3).toInstant()), rs.getTimestamp(4)), offerId, jobId);
        if (selected.isEmpty()) throw new ResponseStatusException(HttpStatus.CONFLICT, "Offer expired");
        SelectedOffer selectedRow = selected.getFirst();
        if (job.status().equals("SCHEDULED")) {
            if (!offerId.equals(selectedRow.selectedOfferId())) throw new ResponseStatusException(HttpStatus.CONFLICT, "A different offer was selected");
            Confirmation confirmed = appointment(jobId);
            return new Selection(selectedRow.holdId(), selectedRow.expiresAt(), confirmed.appointmentId(), confirmed.windowStart(), confirmed.windowEnd());
        }
        if (!job.status().equals("PENDING") || selectedRow.releasedAt() != null || !(selectedRow.expiresAt()).isAfter(Instant.now()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Offer expired");
        roads.matrix(Required.value(Map.<String, RoadClient.Point>of("job", job.point())));
        var rows = jdbc.query("SELECT o.\"serviceDate\", o.\"windowStart\", o.\"windowEnd\", o.\"offerSetId\" FROM booking_offer o JOIN booking_offer_set s ON s.id=o.\"offerSetId\" WHERE o.id=? AND o.\"jobId\"=? AND s.\"supersededAt\" IS NULL AND s.\"expiresAt\">CURRENT_TIMESTAMP",
                (rs, _) -> new OfferWindow(Required.value(Required.timestamp(rs, 1).toInstant()), Required.value(Required.timestamp(rs, 2).toInstant()), Required.value(Required.timestamp(rs, 3).toInstant()), Required.string(rs, 4)), offerId, jobId);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.CONFLICT, "Offer expired");
        LocalDate day = rows.getFirst().day().atZone(ZoneOffset.UTC).toLocalDate();
        Instant start = rows.getFirst().start(), end = rows.getFirst().end();
        String holdId = selectedRow.holdId();
        boolean overtimeAuthorized = Required.query(jdbc, "SELECT \"overtimeAuthorized\" FROM booking_offer WHERE id=? AND \"jobId\"=?", Boolean.class, offerId, jobId);
        List<Candidate> feasible = new ArrayList<>();
        for (Tech tech : technicians(job.serviceId(), Required.value(day), job.metroId())) {
            lockDay(tech.id(), Required.value(day));
            Candidate candidate = evaluateCandidate(job, tech, Required.value(day), start, end);
            if (candidate != null && (candidate.overtimeDeltaMinutes() <= 0 || overtimeAuthorized)) feasible.add(candidate);
        }
        Candidate chosen = feasible.stream().min(INSERTION_ORDER)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "Window no longer available"));
        jdbc.update("UPDATE slot_hold SET \"technicianId\"=?, \"plannedStart\"=?, \"plannedEnd\"=?, \"insertPosition\"=? WHERE id=?",
                chosen.techId(), stamp(chosen.arrival()), stamp(Required.value(chosen.arrival().plus(Duration.ofMinutes(job.duration())))), chosen.position(), holdId);
        jdbc.update("UPDATE booking_offer_set SET \"selectedOfferId\"=? WHERE id=?", offerId, rows.getFirst().setId());
        Confirmation confirmed = confirm(holdId);
        return new Selection(holdId, selectedRow.expiresAt(), confirmed.appointmentId(), confirmed.windowStart(), confirmed.windowEnd());
    }

    @Transactional
    public Confirmation confirm(String holdId) {
        var holds = jdbc.query("SELECT h.\"jobId\", h.\"technicianId\", h.\"serviceDate\", h.\"windowStart\", h.\"windowEnd\", h.\"expiresAt\", h.\"releasedAt\", h.\"offerToken\", s.\"selectedOfferId\", s.\"supersededAt\", h.\"offerSetId\" FROM slot_hold h LEFT JOIN booking_offer_set s ON s.id=h.\"offerSetId\" WHERE h.id=?",
                (rs, _) -> new Hold(Required.string(rs, 1), Required.string(rs, 2), Required.value(Required.timestamp(rs, 3).toInstant()), Required.value(Required.timestamp(rs, 4).toInstant()), Required.value(Required.timestamp(rs, 5).toInstant()), Required.value(Required.timestamp(rs, 6).toInstant()), rs.getTimestamp(7), Required.string(rs, 8), rs.getString(9), rs.getTimestamp(10), rs.getString(11)), holdId);
        if (holds.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Hold not found");
        Hold h = holds.getFirst();
        String jobId = h.jobId(), techId = h.techId();
        LocalDate day = (h.day()).atZone(ZoneOffset.UTC).toLocalDate();
        Instant start = h.start(), end = h.end();
        lockJob(jobId);
        lockDay(techId, Required.value(day));
        if (h.offerSetId() != null && (!Objects.equals(h.selectedOfferId(), h.offerToken()) || h.supersededAt() != null))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A different offer was selected");
        var existing = jdbc.query("SELECT id FROM appointment WHERE \"jobId\"=? AND \"cancelledAt\" IS NULL", (rs, _) -> Required.string(rs, 1), jobId);
        if (!existing.isEmpty()) return appointment(jobId);
        if (h.releasedAt() != null) throw new ResponseStatusException(HttpStatus.CONFLICT, "Hold released");
        if (!(h.expiresAt()).isAfter(Instant.now())) throw new ResponseStatusException(HttpStatus.CONFLICT, "Hold expired");
        Job job = job(jobId, HttpStatus.CONFLICT);
        roads.matrix(Required.value(Map.<String, RoadClient.Point>of("job", job.point())));
        Tech tech = technicians(job.serviceId(), Required.value(day), job.metroId()).stream().filter(t -> t.id().equals(techId)).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "Technician unavailable"));
        Candidate candidate = evaluateCandidate(job, Required.value(tech), Required.value(day), start, end);
        if (candidate == null) throw new ResponseStatusException(HttpStatus.CONFLICT, "Window no longer available");
        boolean overtimeAuthorized = Required.query(jdbc, "SELECT \"overtimeAuthorized\" FROM booking_offer WHERE id=? AND \"jobId\"=?", Boolean.class, h.offerToken(), jobId);
        if (candidate.overtimeDeltaMinutes() > 0 && !overtimeAuthorized)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Reserved offer does not authorize additional overtime");
        String id = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO appointment (id, \"jobId\", \"technicianId\", \"serviceDate\", \"windowStart\", \"windowEnd\", \"plannedStart\", \"plannedEnd\", sequence, \"updatedAt\") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)",
                id, jobId, techId, dayStamp(Required.value(day)), stamp(start), stamp(end), stamp(candidate.arrival()),
                stamp(Required.value(candidate.arrival().plus(Duration.ofMinutes(job.duration())))), candidate.position());
        List<Visit> route = visits(techId, Required.value(day), jobId);
        route.add(Math.min(candidate.position(), route.size()), new Visit(Required.value(id), job.point(), start, end,
                candidate.arrival(), job.duration(), true));
        Metrics planned = evaluate(Required.value(tech), Required.value(day), route);
        if (!planned.feasible()) throw new ResponseStatusException(HttpStatus.CONFLICT, "Window no longer available");
        persistTiming(route, planned);
        jdbc.update("UPDATE job SET status='SCHEDULED', \"updatedAt\"=CURRENT_TIMESTAMP WHERE id=?", jobId);
        jdbc.update("UPDATE job SET \"manualFollowUpStatus\"=NULL, \"manualFollowUpReason\"=NULL WHERE id=?", jobId);
        jdbc.update("UPDATE slot_hold SET \"releasedAt\"=CURRENT_TIMESTAMP WHERE \"jobId\"=? AND \"releasedAt\" IS NULL", jobId);
        jdbc.update("UPDATE schedule_day SET version=version+1 WHERE \"technicianId\"=? AND \"serviceDate\"=?", techId, dayStamp(Required.value(day)));
        return new Confirmation(Required.value(id), start, end);
    }

    private Confirmation appointment(String jobId) {
        return Required.value(jdbc.query("SELECT id, \"windowStart\", \"windowEnd\" FROM appointment WHERE \"jobId\"=? AND \"cancelledAt\" IS NULL",
                (rs, _) -> new Confirmation(Required.string(rs, 1), Required.value(Required.timestamp(rs, 2).toInstant()), Required.value(Required.timestamp(rs, 3).toInstant())), jobId).getFirst());
    }

    @Transactional
    public Map<String, Object> cancel(String appointmentId, @Nullable String reason) {
        if (reason == null || reason.isBlank() || reason.length() > 500)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cancellation reason required");
        var rows = jdbc.query("SELECT \"jobId\", \"technicianId\", \"serviceDate\", \"cancelledAt\" FROM appointment WHERE id=?",
                (rs, _) -> new Cancellation(Required.string(rs, 1), Required.string(rs, 2), Required.value(Required.timestamp(rs, 3).toInstant()), rs.getTimestamp(4)), appointmentId);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Appointment not found");
        Cancellation row = rows.getFirst();
        String jobId = row.jobId(), techId = row.techId();
        LocalDate day = (row.day()).atZone(ZoneOffset.UTC).toLocalDate();
        lockJob(Required.value(jobId));
        lockDay(Required.value(techId), Required.value(day));
        if (Required.query(jdbc, "SELECT \"cancelledAt\" IS NOT NULL FROM appointment WHERE id=?", Boolean.class, appointmentId))
            return Required.value(Map.<String, Object>of("success", true, "appointmentId", appointmentId, "alreadyCancelled", true));
        jdbc.update("UPDATE appointment SET \"cancelledAt\"=CURRENT_TIMESTAMP, \"cancellationReason\"=?, \"updatedAt\"=CURRENT_TIMESTAMP WHERE id=?", reason.trim(), appointmentId);
        jdbc.update("UPDATE job SET status='CANCELLED', \"updatedAt\"=CURRENT_TIMESTAMP WHERE id=?", jobId);
        if (!ScheduleCutoff.frozen(Required.value(day), Required.value(Instant.now()))) {
            List<Visit> remaining = visits(Required.value(techId), Required.value(day), Required.value(jobId));
            WeeklyAvailability.Shift shift = WeeklyAvailability.resolve(jdbc, techId, Required.value(day));
            var techs = shift == null ? List.<Tech>of() : jdbc.query("SELECT " + RouteEndpoints.COLUMNS + ",t.\"maxDailyMinutes\",t.\"maxOvertimeMinutes\" FROM technician t" + RouteEndpoints.JOINS + " WHERE t.id=?",
                    (rs, _) -> new Tech(techId, RouteEndpoints.from(rs, 1), shift.start(), shift.end(), Required.integer(rs, 7), Required.integer(rs, 8)), dayStamp(Required.value(day)), dayStamp(Required.value(day)), techId);
            if (!remaining.isEmpty()) {
                if (techs.isEmpty()) throw new ResponseStatusException(HttpStatus.CONFLICT, "Remaining appointments or reservations have no working technician");
                Metrics recalculated = evaluate(Required.value(techs.getFirst()), Required.value(day), remaining);
                if (!recalculated.feasible())
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "Cancellation requires repair of remaining appointments or reservations");
                persistTiming(remaining, recalculated);
            }
        }
        jdbc.update("UPDATE schedule_day SET version=version+1 WHERE \"technicianId\"=? AND \"serviceDate\"=?", techId, dayStamp(Required.value(day)));
        return Required.value(Map.<String, Object>of("success", true, "appointmentId", appointmentId, "alreadyCancelled", false));
    }

    private void persistTiming(List<Visit> route, Metrics result) {
        for (int index = 0; index < route.size(); index++) {
            Visit visit = route.get(index);
            Instant arrival = Required.value(result.arrivals().get(visit.id()), "route arrival");
            Timestamp plannedStart = stamp(arrival), plannedEnd = stamp(Required.value(arrival.plus(Duration.ofMinutes(visit.duration()))));
            int updated = jdbc.update("UPDATE appointment SET sequence=?,\"plannedStart\"=?,\"plannedEnd\"=?,\"updatedAt\"=CURRENT_TIMESTAMP WHERE id=? AND \"cancelledAt\" IS NULL",
                    index, plannedStart, plannedEnd, visit.id());
            if (updated == 0) updated = jdbc.update("UPDATE slot_hold SET \"insertPosition\"=?,\"plannedStart\"=?,\"plannedEnd\"=? WHERE id=? AND \"releasedAt\" IS NULL AND \"expiresAt\">CURRENT_TIMESTAMP",
                    index, plannedStart, plannedEnd, visit.id());
            if (updated != 1) throw new ResponseStatusException(HttpStatus.CONFLICT, "A route appointment or reservation changed during validation");
        }
    }

    private List<Candidate> candidates(Job job, @Nullable LocalDate onlyDay, @Nullable Instant onlyStart) {
        List<Candidate> result = new ArrayList<>();
        try {
        Map<String, Double> sharedSettings = Required.value(Map.copyOf(settings()));
        SearchDeadline.policyLimit(dev.waterflex.scheduler.optimizer.PolicySettings.read(sharedSettings).bookingDeadlineMs());
        String routingIdentity = roads.activeIdentity();
        List<LocalDate> days = onlyDay == null ? bookingDates(Required.value(Instant.now())) : Required.value(List.of(Required.value(onlyDay)));
        for (LocalDate day : days) {
            SearchDeadline.database(jdbc);
            LocalDate serviceDay = Required.value(day);
            for (Tech tech : technicians(job.serviceId(), serviceDay, job.metroId())) {
                    SearchDeadline.database(jdbc);
                    List<Visit> visits = visits(tech.id(), serviceDay, job.id());
                    List<Visit> locations = new ArrayList<>(visits);
                    Instant startOfShift = ScheduleCutoff.localMinute(serviceDay, tech.shiftStart(), false);
                    locations.add(new Visit(job.id(), job.point(), startOfShift, Required.value(startOfShift.plus(Duration.ofHours(2))), startOfShift, job.duration(), true));
                    EvaluationContext snapshot = prepare(Required.value(tech), serviceDay, locations, sharedSettings, routingIdentity);
                    Metrics baseline = evaluate(Required.value(tech), serviceDay, visits, snapshot);
                    if (!baseline.feasible()) continue;
                    for (int minute : windowStartMinutes(tech.shiftStart(), tech.shiftEnd())) {
                        SearchDeadline.checkpoint();
                        Instant start = ScheduleCutoff.localMinute(serviceDay, minute, false);
                        if (onlyStart != null && !start.equals(onlyStart)) continue;
                        Candidate c = evaluateCandidate(job, Required.value(tech), serviceDay, start, Required.value(start.plus(Duration.ofHours(2))), visits, snapshot, baseline);
                        if (c != null) result.add(c);
                    }
            }
        }
        } catch (SearchDeadline.Expired exception) {
            // Independent validation and reservation commit still have the final second.
            // Only zero-added-overtime candidates are considered by the caller.
        }
        return result;
    }

    static List<LocalDate> bookingDates(Instant now) {
        List<LocalDate> days = new ArrayList<>();
        LocalDate day = now.atZone(CHICAGO).toLocalDate().plusDays(1);
        int weekdays = 0;
        while (weekdays < 10) {
            days.add(day);
            if (day.getDayOfWeek().getValue() <= 5) weekdays++;
            day = day.plusDays(1);
        }
        return days;
    }

    static List<Integer> windowStartMinutes(int shiftStart, int shiftEnd) {
        if (shiftStart < 0 || shiftEnd > 1440 || shiftStart >= shiftEnd)
            throw new IllegalArgumentException("Invalid shift hours");
        List<Integer> starts = new ArrayList<>();
        for (int minute = shiftStart; minute + 120 <= shiftEnd; minute += 60) starts.add(minute);
        return starts;
    }

    private @Nullable Candidate evaluateCandidate(Job job, Tech tech, LocalDate day, Instant start, Instant end) {
        List<Visit> visits = visits(tech.id(), day, job.id());
        List<Visit> locations = new ArrayList<>(visits);
        locations.add(new Visit(job.id(), job.point(), start, end, start, job.duration(), true));
        EvaluationContext snapshot = prepare(tech, day, locations, settings(), roads.activeIdentity());
        Metrics baseline = evaluate(tech, day, visits, snapshot);
        return evaluateCandidate(job, tech, day, start, end, visits, snapshot, baseline);
    }

    private @Nullable Candidate evaluateCandidate(Job job, Tech tech, LocalDate day, Instant start, Instant end,
            List<Visit> visits, EvaluationContext snapshot, Metrics baseline) {
        if (!baseline.feasible()) return null;
        Candidate best = null;
        for (int position = 0; position <= visits.size(); position++) {
            SearchDeadline.checkpoint();
            List<Visit> proposal = new ArrayList<>(visits);
            proposal.add(position, new Visit(job.id(), job.point(), start, end, start, job.duration(), true));
            Metrics m = evaluate(tech, day, proposal, snapshot);
            if (!m.feasible() || m.newArrival() == null) continue;
            double paidDelta = m.paidMinutes() - baseline.paidMinutes();
            double overtimeDelta = m.overtimeMinutes() - baseline.overtimeMinutes();
            double cost = (m.costCents() - baseline.costCents()) / 100.0;
            Candidate c = new Candidate(tech.id(), day, start, end, Required.value(m.newArrival(), "candidate arrival"), position, cost,
                    (long) (paidDelta - overtimeDelta), (long) overtimeDelta, m.meters() - baseline.meters());
            if (best == null || INSERTION_ORDER.compare(c, best) < 0) best = c;
        }
        return best;
    }

    private Metrics evaluate(Tech tech, LocalDate day, List<Visit> visits) {
        if (visits.isEmpty()) return new Metrics(true, null, 0, 0, 0, 0, Required.value(Map.of()));
        return evaluate(tech, day, visits, prepare(tech, day, visits, settings(), roads.activeIdentity()));
    }

    private EvaluationContext prepare(Tech tech, LocalDate day, List<Visit> visits, Map<String, Double> settings, String routingIdentity) {
        SearchDeadline.database(jdbc);
        List<TechRoute.Unavailable> absences = jdbc.query("SELECT i.\"startMin\", i.\"endMin\" FROM time_off_interval i JOIN time_off_request r ON r.id=i.\"requestId\" WHERE r.status='APPROVED' AND r.\"technicianId\"=? AND i.\"serviceDate\"=? ORDER BY i.\"startMin\"",
                (rs, _) -> new TechRoute.Unavailable(ScheduleCutoff.localMinute(day, Required.integer(rs, 1), false),
                        ScheduleCutoff.localMinute(day, Required.integer(rs, 2), true)), tech.id(), dayStamp(day));
        Map<String, RoadClient.Point> points = new LinkedHashMap<>();
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

    private static void addPair(Map<String, RoadClient.Pair> pairs, Map<String, RoadClient.Point> points, String from, String to) {
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
                result.meters(), result.costCents(), result.arrivals());
    }

    /** Called inside the locked dealership policy transaction after its new endpoints are saved. */
    void replanExisting(String techId, LocalDate day) {
        WeeklyAvailability.Shift shift = WeeklyAvailability.resolve(jdbc, techId, day);
        if (shift == null) throw new ResponseStatusException(HttpStatus.CONFLICT, "Booked technician has no shift");
        var rows = jdbc.query("SELECT " + RouteEndpoints.COLUMNS + ",t.\"maxDailyMinutes\",t.\"maxOvertimeMinutes\" FROM technician t" + RouteEndpoints.JOINS + " WHERE t.id=?",
                (rs, _) -> new Tech(techId, RouteEndpoints.from(rs, 1), shift.start(), shift.end(), Required.integer(rs, 7), Required.integer(rs, 8)), dayStamp(day), dayStamp(day), techId);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.CONFLICT, "Booked technician is missing");
        List<Visit> visits = jdbc.query("SELECT a.id, ad.lat, ad.lng, a.\"windowStart\", a.\"windowEnd\", a.\"plannedStart\", j.\"durationMin\" FROM appointment a JOIN job j ON j.id=a.\"jobId\" JOIN address ad ON ad.id=j.\"addressId\" WHERE a.\"technicianId\"=? AND a.\"serviceDate\"=? AND a.\"cancelledAt\" IS NULL ORDER BY a.sequence, a.\"plannedStart\"",
                (rs, _) -> new Visit(Required.string(rs, 1), Required.location(rs, 2, 3, HttpStatus.CONFLICT), Required.value(Required.timestamp(rs, 4).toInstant()), Required.value(Required.timestamp(rs, 5).toInstant()), Required.value(Required.timestamp(rs, 6).toInstant()), Required.integer(rs, 7), false), techId, dayStamp(day));
        Metrics result = evaluate(Required.value(rows.getFirst()), day, visits);
        if (!result.feasible() || result.arrivals().size() != visits.size())
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Dealership route would make a booked day infeasible");
        for (Visit visit : visits) {
            Instant arrival = Required.value(result.arrivals().get(visit.id()), "appointment arrival");
            jdbc.update("UPDATE appointment SET \"plannedStart\"=?,\"plannedEnd\"=?,\"updatedAt\"=CURRENT_TIMESTAMP WHERE id=?",
                    stamp(arrival), stamp(Required.value(arrival.plus(Duration.ofMinutes(visit.duration())))), visit.id());
        }
        jdbc.update("UPDATE schedule_day SET version=version+1 WHERE \"technicianId\"=? AND \"serviceDate\"=?", techId, dayStamp(day));
    }

    /** Revalidate every active reservation and booked window after a depot move. */
    void replanAssignment(String techId, LocalDate day) {
        WeeklyAvailability.Shift shift = WeeklyAvailability.resolve(jdbc, techId, day);
        if (shift == null) throw new ResponseStatusException(HttpStatus.CONFLICT, "Assigned technician has no shift");
        var rows = jdbc.query("SELECT " + RouteEndpoints.COLUMNS + ",t.\"maxDailyMinutes\",t.\"maxOvertimeMinutes\" FROM technician t" + RouteEndpoints.JOINS + " WHERE t.id=?",
                (rs, _) -> new Tech(techId, RouteEndpoints.from(rs, 1), shift.start(), shift.end(), Required.integer(rs, 7), Required.integer(rs, 8)), dayStamp(day), dayStamp(day), techId);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.CONFLICT, "Assigned technician is missing");
        List<Visit> route = visits(techId, day, "");
        Metrics result = evaluate(Required.value(rows.getFirst()), day, route);
        if (!result.feasible() || result.arrivals().size() != route.size())
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Depot move would make a booked route or active hold infeasible");
        for (int index = 0; index < route.size(); index++) {
            Visit visit = route.get(index);
            Instant arrival = Required.value(result.arrivals().get(visit.id()), "route arrival");
            Timestamp plannedStart = stamp(arrival), plannedEnd = stamp(Required.value(arrival.plus(Duration.ofMinutes(visit.duration()))));
            int updated = jdbc.update("UPDATE appointment SET sequence=?,\"plannedStart\"=?,\"plannedEnd\"=?,\"updatedAt\"=CURRENT_TIMESTAMP WHERE id=? AND \"cancelledAt\" IS NULL",
                    index, plannedStart, plannedEnd, visit.id());
            if (updated == 0) updated = jdbc.update("UPDATE slot_hold SET \"insertPosition\"=?,\"plannedStart\"=?,\"plannedEnd\"=? WHERE id=? AND \"releasedAt\" IS NULL AND \"expiresAt\">CURRENT_TIMESTAMP",
                    index, plannedStart, plannedEnd, visit.id());
            if (updated != 1) throw new ResponseStatusException(HttpStatus.CONFLICT, "A route reservation changed during the depot move");
        }
        jdbc.update("INSERT INTO schedule_day (id,\"technicianId\",\"serviceDate\",version) VALUES (?,?,?,0) ON CONFLICT (\"technicianId\",\"serviceDate\") DO NOTHING",
                UUID.randomUUID().toString(), techId, dayStamp(day));
        jdbc.update("UPDATE schedule_day SET version=version+1 WHERE \"technicianId\"=? AND \"serviceDate\"=?", techId, dayStamp(day));
    }

    private Job job(String id) { return job(id, HttpStatus.UNPROCESSABLE_ENTITY); }

    private Job job(String id, HttpStatus missingLocationStatus) {
        var rows = jdbc.query("SELECT j.id, j.\"serviceId\", j.\"durationMin\", a.lat, a.lng, j.status::text FROM job j JOIN address a ON a.id=j.\"addressId\" WHERE j.id=?",
                (rs, _) -> new Job(Required.string(rs, 1), Required.string(rs, 2), Required.integer(rs, 3), Required.location(rs, 4, 5, missingLocationStatus), Required.string(rs, 6), ""), id);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Job not found");
        Job row = Required.value(rows.getFirst());
        return new Job(row.id(), row.serviceId(), row.duration(), row.point(), row.status(), metroFor(row.point()));
    }

    private String metroFor(RoadClient.Point point) {
        List<ServiceDepot> depots = jdbc.query("SELECT p.\"metroId\",p.lat,p.lng,m.\"serviceRadiusMi\" FROM depot p JOIN metro m ON m.id=p.\"metroId\"",
                (rs, _) -> new ServiceDepot(Required.string(rs, 1), Required.number(rs, 2), Required.number(rs, 3), Required.number(rs, 4)));
        String best = null;
        double bestMiles = Double.POSITIVE_INFINITY;
        for (ServiceDepot depot : depots) {
            double lat = Math.toRadians(depot.lat() - point.lat());
            double lng = Math.toRadians(depot.lng() - point.lng());
            double a = Math.pow(Math.sin(lat / 2), 2) + Math.cos(Math.toRadians(point.lat())) * Math.cos(Math.toRadians(depot.lat())) * Math.pow(Math.sin(lng / 2), 2);
            double miles = 3958.7613 * 2 * Math.asin(Math.min(1, Math.sqrt(a)));
            if (miles <= depot.radiusMi() && miles < bestMiles) { best = depot.metroId(); bestMiles = miles; }
        }
        if (best == null) throw new ResponseStatusException(HttpStatus.CONFLICT, "Job location is outside configured metros");
        return best;
    }

    private List<Tech> technicians(String serviceId, LocalDate day, String metroId) {
        SearchDeadline.database(jdbc);
        List<TechBase> rows = jdbc.query("SELECT t.id," + RouteEndpoints.COLUMNS + ",t.\"maxDailyMinutes\",t.\"maxOvertimeMinutes\" FROM technician t" + RouteEndpoints.JOINS + " JOIN technician_qualification q ON q.\"technicianId\"=t.id AND q.\"serviceId\"=? WHERE t.active=true AND p.\"metroId\"=? ORDER BY t.id FOR SHARE OF t",
                (rs, _) -> new TechBase(Required.string(rs, 1), RouteEndpoints.from(rs, 2), Required.integer(rs, 8), Required.integer(rs, 9)), dayStamp(day), dayStamp(day), serviceId, metroId);
        List<Tech> result = new ArrayList<>();
        Map<String, WeeklyAvailability.Availability> availability = WeeklyAvailability.resolveAll(jdbc,
                Required.value(rows.stream().map((TechBase row) -> row.id()).toList()), day);
        for (TechBase row : rows) {
            TechBase technician = Required.value(row);
            WeeklyAvailability.Shift shift = Required.value(availability.get(technician.id()), "technician availability").shift();
            if (shift != null) result.add(new Tech(technician.id(), technician.endpoints(), shift.start(), shift.end(), technician.maxDaily(), technician.maxOvertime()));
        }
        return result;
    }

    private List<Visit> visits(String techId, LocalDate day, String excludeJobId) {
        SearchDeadline.database(jdbc);
        List<Visit> result = new ArrayList<Visit>(Required.value(jdbc.query("SELECT a.id, ad.lat, ad.lng, a.\"windowStart\", a.\"windowEnd\", a.\"plannedStart\", j.\"durationMin\" FROM appointment a JOIN job j ON j.id=a.\"jobId\" JOIN address ad ON ad.id=j.\"addressId\" WHERE a.\"technicianId\"=? AND a.\"serviceDate\"=? AND a.\"cancelledAt\" IS NULL AND (? IS NULL OR a.\"jobId\"<>?) ORDER BY a.sequence, a.\"plannedStart\"",
                (rs, _) -> new Visit(Required.string(rs, 1), Required.location(rs, 2, 3, HttpStatus.CONFLICT), Required.value(Required.timestamp(rs, 4).toInstant()), Required.value(Required.timestamp(rs, 5).toInstant()), Required.value(Required.timestamp(rs, 6).toInstant()), Required.integer(rs, 7), false), techId, dayStamp(day), excludeJobId, excludeJobId)));
        result.addAll(jdbc.query("SELECT h.id, h.\"locationLat\", h.\"locationLng\", h.\"windowStart\", h.\"windowEnd\", h.\"plannedStart\", j.\"durationMin\" FROM slot_hold h JOIN job j ON j.id=h.\"jobId\" WHERE h.\"technicianId\"=? AND h.\"serviceDate\"=? AND h.\"jobId\"<>? AND h.\"releasedAt\" IS NULL AND h.\"expiresAt\">CURRENT_TIMESTAMP ORDER BY h.\"plannedStart\"",
                (rs, _) -> new Visit(Required.string(rs, 1), Required.location(rs, 2, 3, HttpStatus.CONFLICT), Required.value(Required.timestamp(rs, 4).toInstant()), Required.value(Required.timestamp(rs, 5).toInstant()), Required.value(Required.timestamp(rs, 6).toInstant()), Required.integer(rs, 7), false), techId, dayStamp(day), excludeJobId));
        result.sort(Comparator.comparing((Visit visit) -> visit.planned()));
        return result;
    }

    private Map<String, Double> settings() {
        Map<String, Double> values = new HashMap<>();
        jdbc.query("SELECT key, value FROM omaha_setting", (org.springframework.jdbc.core.RowCallbackHandler) rs -> values.put(Required.string(rs, 1), Required.number(rs, 2)));
        return values;
    }

    private void lockDay(String techId, LocalDate day) {
        SearchDeadline.database(jdbc);
        jdbc.update("INSERT INTO schedule_day (id, \"technicianId\", \"serviceDate\", version) VALUES (?, ?, ?, 0) ON CONFLICT (\"technicianId\", \"serviceDate\") DO NOTHING", UUID.randomUUID().toString(), techId, dayStamp(day));
        Required.query(jdbc, "SELECT version FROM schedule_day WHERE \"technicianId\"=? AND \"serviceDate\"=? FOR UPDATE", Integer.class, techId, dayStamp(day));
    }

    private void lockJob(String jobId) {
        SearchDeadline.database(jdbc);
        var rows = jdbc.query("SELECT id FROM job WHERE id=? FOR UPDATE", (rs, _) -> Required.string(rs, 1), jobId);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Job not found");
    }

    private static Timestamp stamp(Instant time) { return Required.value(Timestamp.from(time)); }
    private static Timestamp dayStamp(LocalDate day) { return stamp(Required.value(day.atStartOfDay(ZoneOffset.UTC).toInstant())); }
}
