package dev.waterflex.scheduler;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.waterflex.scheduler.BookingSnapshot.*;
import dev.waterflex.scheduler.ReservationState.Hold;
import dev.waterflex.scheduler.optimizer.PolicySettings;
import dev.waterflex.scheduler.optimizer.SchedulingPolicy;
import dev.waterflex.scheduler.optimizer.TechRoute;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** Reads one repeatable snapshot. Routing and exploratory search run after this transaction ends. */
@Component
public final class BookingSnapshotLoader {
    public record Facts(String metroId, Instant capturedAt, String configurationFingerprint, String routingIdentity,
            SchedulingPolicy.Rules policy, Rates rates, Map<LocalDate, Day> days,
            Map<LocalDate, Map<String, ReservationState.Hold>> holds) {
        public Facts {
            days = Required.value(Map.copyOf(days));
            Map<LocalDate, Map<String, ReservationState.Hold>> copy = new TreeMap<>();
            holds.forEach((day, values) -> copy.put(day, Required.value(Map.copyOf(values))));
            holds = Required.value(Collections.unmodifiableMap(copy));
        }
    }
    public record Loaded(BookingSnapshot snapshot, Map<LocalDate, Map<String, ReservationState.Hold>> holds) {
        public Loaded {
            Map<LocalDate, Map<String, ReservationState.Hold>> copy = new TreeMap<>();
            holds.forEach((day, values) -> copy.put(day, Required.value(Map.copyOf(values))));
            holds = Required.value(Collections.unmodifiableMap(copy));
        }
    }
    private record Base(String id, boolean active, RouteEndpoints endpoints, int maxDaily, int maxOvertime, long version) { }
    private record Saved(int version, @Nullable ReservationState state) { }
    private record Stop(Visit visit, @Nullable Hold hold, boolean managed) { }
    private final JdbcTemplate jdbc;
    private final TransactionTemplate reads;
    private final ObjectMapper json = new ObjectMapper();

    private final ServiceCalendar calendar;
    public BookingSnapshotLoader(JdbcTemplate jdbc, PlatformTransactionManager transactions) {
        this(jdbc, transactions, new ServiceCalendar());
    }
    @org.springframework.beans.factory.annotation.Autowired
    public BookingSnapshotLoader(JdbcTemplate jdbc, PlatformTransactionManager transactions, ServiceCalendar calendar) {
        this.calendar = calendar;
        this.jdbc = jdbc;
        reads = new TransactionTemplate(transactions);
        reads.setReadOnly(true);
        reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        reads.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        reads.setTimeout(4);
    }

    public Loaded load(String metroId, String requestingJobId, Instant capturedAt, String routingIdentity) {
        return load(metroId, requestingJobId, capturedAt, routingIdentity, false);
    }
    public Loaded load(String metroId, String requestingJobId, Instant capturedAt, String routingIdentity, boolean overflow) {
        return load(metroId, requestingJobId, capturedAt, routingIdentity, overflow, calendar.at(capturedAt));
    }
    public Loaded load(String metroId, String requestingJobId, Instant capturedAt, String routingIdentity,
            boolean overflow, Instant calendarReference) {
        List<LocalDate> dates = new ArrayList<>(BookingService.bookingDates(calendarReference));
        if (overflow) dates.addAll(BookingService.overflowDates(calendarReference));
        Facts facts = Required.value(reads.execute(_ -> read(metroId, requestingJobId, capturedAt, routingIdentity,
                dates, true)), "booking snapshot");
        return new Loaded(new BookingSnapshot(facts.metroId(), facts.capturedAt(), calendarReference, facts.configurationFingerprint(),
                facts.routingIdentity(), facts.policy(), facts.rates(), facts.days()), facts.holds());
    }

    /** Operator flags do not remove commitments, dependencies, or capacity from snapshots. */
    public void flagExistingOvertime(BookingSnapshot snapshot, String requestedService) {
        for (var day : snapshot.days().values()) {
            var evaluation = new BookingEvaluation(Required.value(day), snapshot.rates(), requestedService, SearchDeadline::checkpoint);
            for (var route : day.baseline().routes().entrySet()) {
                if (evaluation.route(Required.value(route.getKey()), Required.value(route.getValue()), day.visits(), false).overtimeMinutes() == 0) continue;
                for (String id : route.getValue()) {
                    Visit visit = Required.value(day.visits().get(id));
                    if (!visit.reservation()) jdbc.update("UPDATE job SET \"manualFollowUpStatus\"='PENDING',\"manualFollowUpReason\"='EXISTING_OVERTIME' WHERE id=? AND status='SCHEDULED' AND (\"manualFollowUpReason\" IS NULL OR \"manualFollowUpReason\"='EXISTING_OVERTIME')", visit.jobId());
                }
            }
        }
    }

    /** Existing offers can cross midnight; their service dates need not be in today's new-booking horizon. */
    public Facts loadDates(String metroId, List<LocalDate> dates, Instant capturedAt, String routingIdentity) {
        if (dates.isEmpty() || new HashSet<>(dates).size() != dates.size()) throw new IllegalArgumentException("Invalid reservation dates");
        return Required.value(reads.execute(_ -> read(metroId, "", capturedAt, routingIdentity,
                new ArrayList<>(new TreeSet<>(dates)), false)), "reservation snapshot");
    }

    /** Re-read under the caller's locks; never opens a second transaction during commit. */
    public Facts locked(String metroId, List<LocalDate> dates, Instant capturedAt, String routingIdentity,
            String excludedJob, boolean pendingRequired) {
        if (!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Locked snapshot requires a transaction");
        return read(metroId, excludedJob, capturedAt, routingIdentity, dates, pendingRequired);
    }

    private Facts read(String metroId, String requestingJobId, Instant capturedAt, String routingIdentity, List<LocalDate> dates, boolean pendingRequired) {
        dev.waterflex.scheduler.DatabaseDeadline.apply(jdbc);
        // The snapshot holds the whole metro's technicians, so a metro shared by several clients books through the public API.
        MetroTenancy.requireSingleClient(jdbc, metroId);
        if (pendingRequired && !"PENDING".equals(dev.waterflex.scheduler.DatabaseFacts.query(jdbc, "SELECT status::text FROM job WHERE id=?", String.class, requestingJobId)))
            throw conflict("Job is no longer pending");
        Map<String, java.math.BigDecimal> settings = new TreeMap<>();
        jdbc.query("SELECT key,value FROM omaha_setting ORDER BY key", (org.springframework.jdbc.core.RowCallbackHandler) rs ->
                settings.put(dev.waterflex.scheduler.DatabaseFacts.string(rs, 1), dev.waterflex.scheduler.DatabaseFacts.decimal(rs, 2)));
        Rates rates = Rates.read(settings);
        var policy = PolicySettings.read(settings);
        SearchDeadline.policyLimit(policy.bookingDeadlineMs());
        Map<String, Set<String>> qualifications = new HashMap<>();
        jdbc.query("SELECT \"technicianId\",\"serviceId\" FROM technician_qualification ORDER BY \"technicianId\",\"serviceId\"",
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> qualifications.computeIfAbsent(dev.waterflex.scheduler.DatabaseFacts.string(rs, 1), _ -> new TreeSet<>()).add(dev.waterflex.scheduler.DatabaseFacts.string(rs, 2)));
        Map<LocalDate, Day> days = new TreeMap<>();
        Map<LocalDate, Map<String, ReservationState.Hold>> holds = new TreeMap<>();
        StringBuilder configuration = new StringBuilder();
        append(configuration, metroId); append(configuration, Monetary.COST_MODEL);
        settings.forEach((key, value) -> { append(configuration, Required.value(key)); append(configuration, Required.value(value.stripTrailingZeros().toPlainString())); });
        for (LocalDate date : dates) {
            LocalDate day = Required.value(date);
            dev.waterflex.scheduler.DatabaseDeadline.apply(jdbc);
            List<Base> bases = jdbc.query("SELECT t.id,t.active," + RouteEndpoints.COLUMNS + ",t.\"maxDailyMinutes\",t.\"maxOvertimeMinutes\",sd.version FROM technician t" + RouteEndpoints.JOINS
                            + " LEFT JOIN schedule_day sd ON sd.\"technicianId\"=t.id AND sd.\"serviceDate\"=? WHERE p.\"metroId\"=? OR (p.id IS NULL AND t.active=true AND EXISTS (SELECT 1 FROM technician_qualification q JOIN job j ON j.\"serviceId\"=q.\"serviceId\" WHERE q.\"technicianId\"=t.id AND j.id=?)) ORDER BY t.id",
                    (rs, _) -> {
                        Long version = dev.waterflex.scheduler.DatabaseFacts.nullableLong(rs, 11);
                        // A missing lock row means this technician/day has never been mutated.
                        return new Base(dev.waterflex.scheduler.DatabaseFacts.string(rs, 1), dev.waterflex.scheduler.DatabaseFacts.bool(rs, 2), RouteEndpoints.from(rs, 3),
                                dev.waterflex.scheduler.DatabaseFacts.integer(rs, 9), dev.waterflex.scheduler.DatabaseFacts.integer(rs, 10), version == null ? 0 : version);
                    }, stamp(day), stamp(day), stamp(day), metroId, requestingJobId);
            List<String> ids = Required.value(bases.stream().<String>map((Base base) -> base.id()).toList());
            List<String> activeIds = Required.value(bases.stream().filter((Base base) -> base.active()).<String>map((Base base) -> base.id()).toList());
            Map<String, WeeklyAvailability.Availability> availability = WeeklyAvailability.resolveAll(jdbc, activeIds, day);
            Map<String, List<TechRoute.Unavailable>> absences = absences(ids, day);
            Map<String, Technician> technicians = new TreeMap<>();
            for (Base base : bases) {
                if (!base.active()) continue;
                WeeklyAvailability.Shift shift = Required.value(availability.get(base.id()), "weekly availability").shift();
                if (shift == null) continue;
                Set<String> services = qualifications.get(base.id());
                List<TechRoute.Unavailable> unavailable = absences.get(base.id());
                // No qualification or absence rows is a valid empty relation, unlike missing shift facts.
                Technician tech = new Technician(base.id(), ScheduleCutoff.localMinute(day, shift.start(), false),
                        ScheduleCutoff.localMinute(day, shift.end(), true), base.maxDaily(), base.maxOvertime(),
                        services == null ? Required.value(Set.of()) : services,
                        unavailable == null ? Required.value(List.of()) : unavailable,
                        base.endpoints().departure(), base.endpoints().returnTo(), base.version());
                technicians.put(tech.id(), tech);
                fingerprint(configuration, day, tech);
            }
            Map<String, Visit> visits = new LinkedHashMap<>();
            Map<String, ReservationState.Hold> reservations = new TreeMap<>();
            Map<String, List<String>> routeIds = new TreeMap<>();
            technicians.keySet().forEach(id -> routeIds.put(id, new ArrayList<>()));
            boolean managedReservations = false;
            for (Stop stop : stops(ids, day, requestingJobId)) {
                managedReservations |= stop.managed();
                Visit visit = stop.visit();
                if (!technicians.containsKey(visit.originalTechnicianId())) throw conflict("An appointment or reservation has no active working technician");
                if (visits.putIfAbsent(visit.id(), visit) != null) throw conflict("Duplicate appointment/reservation identifier");
                Required.value(routeIds.get(visit.originalTechnicianId())).add(visit.id());
                ReservationState.Hold hold = stop.hold();
                if (hold != null) reservations.put(visit.id(), hold);
            }
            Saved saved = saved(metroId, day);
            Arrangement baseline = new Arrangement(routeIds);
            ReservationState prior = saved.state();
            if (managedReservations) {
                if (prior == null) throw conflict("Managed reservations have no common arrangement");
                baseline = restore(prior, technicians, visits, requestingJobId);
            }
            days.put(day, new Day(technicians, visits, baseline, saved.version(),
                    new Roads(Required.value(Map.of()), Required.value(Set.of()))));
            holds.put(day, reservations);
        }
        SearchDeadline.checkpoint();
        return new Facts(metroId, capturedAt, digest(Required.value(configuration.toString())), routingIdentity, policy, rates, days, holds);
    }

    private Map<String, List<TechRoute.Unavailable>> absences(List<String> technicians, LocalDate day) {
        Map<String, List<TechRoute.Unavailable>> result = new HashMap<>();
        if (technicians.isEmpty()) return result;
        List<Object> args = new ArrayList<>(); args.add(stamp(day)); args.addAll(technicians);
        jdbc.query("SELECT r.\"technicianId\",i.\"startMin\",i.\"endMin\" FROM time_off_interval i JOIN time_off_request r ON r.id=i.\"requestId\" WHERE r.status='APPROVED' AND i.\"serviceDate\"=? AND r.\"technicianId\" IN ("
                        + placeholders(technicians.size()) + ") ORDER BY r.\"technicianId\",i.\"startMin\",i.\"endMin\"",
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> result.computeIfAbsent(dev.waterflex.scheduler.DatabaseFacts.string(rs, 1), _ -> new ArrayList<>()).add(
                        new TechRoute.Unavailable(ScheduleCutoff.localMinute(day, dev.waterflex.scheduler.DatabaseFacts.integer(rs, 2), false), ScheduleCutoff.localMinute(day, dev.waterflex.scheduler.DatabaseFacts.integer(rs, 3), true))),
                Required.value(args.toArray(new @Nullable Object[0])));
        return result;
    }

    private List<Stop> stops(List<String> technicians, LocalDate day, String excludedJob) {
        if (technicians.isEmpty()) return Required.value(List.of());
        List<Object> args = new ArrayList<>();
        args.add(stamp(day)); args.addAll(technicians);
        args.add(stamp(day)); args.add(excludedJob); args.addAll(technicians);
        return Required.value(jdbc.query("SELECT a.id,a.\"jobId\",j.\"serviceId\",a.\"windowStart\",a.\"windowEnd\",j.\"durationMin\",ad.lat,ad.lng,a.\"technicianId\",a.\"plannedStart\",false,NULL::timestamp,NULL::text,NULL::boolean,false "
                        + "FROM appointment a JOIN job j ON j.id=a.\"jobId\" JOIN address ad ON ad.id=j.\"addressId\" WHERE a.\"serviceDate\"=? AND a.\"cancelledAt\" IS NULL AND a.\"technicianId\" IN (" + placeholders(technicians.size()) + ") UNION ALL "
                        + "SELECT h.id,h.\"jobId\",j.\"serviceId\",h.\"windowStart\",h.\"windowEnd\",j.\"durationMin\",h.\"locationLat\",h.\"locationLng\",h.\"technicianId\",h.\"plannedStart\",true,h.\"expiresAt\",h.\"offerToken\",o.\"overtimeAuthorized\",(s.\"searchDiagnostics\" IS NOT NULL OR EXISTS (SELECT 1 FROM reservation_dependency dependency WHERE dependency.\"holdId\"=h.id)) "
                        + "FROM slot_hold h JOIN job j ON j.id=h.\"jobId\" LEFT JOIN booking_offer o ON o.id=h.\"offerToken\" AND o.\"jobId\"=h.\"jobId\" LEFT JOIN booking_offer_set s ON s.id=h.\"offerSetId\" WHERE h.\"serviceDate\"=? AND h.\"jobId\"<>? AND h.\"releasedAt\" IS NULL AND h.\"expiresAt\">CURRENT_TIMESTAMP AND h.\"technicianId\" IN (" + placeholders(technicians.size()) + ") ORDER BY 10,1",
                (rs, _) -> {
                    boolean reserved = dev.waterflex.scheduler.DatabaseFacts.bool(rs, 11);
                    Visit visit = new Visit(dev.waterflex.scheduler.DatabaseFacts.string(rs, 1), dev.waterflex.scheduler.DatabaseFacts.string(rs, 2), dev.waterflex.scheduler.DatabaseFacts.string(rs, 3),
                            Required.value(dev.waterflex.scheduler.DatabaseFacts.timestamp(rs, 4).toInstant()), Required.value(dev.waterflex.scheduler.DatabaseFacts.timestamp(rs, 5).toInstant()),
                            dev.waterflex.scheduler.DatabaseFacts.integer(rs, 6), dev.waterflex.scheduler.DatabaseFacts.location(rs, 7, 8, HttpStatus.CONFLICT), dev.waterflex.scheduler.DatabaseFacts.string(rs, 9),
                            Required.value(dev.waterflex.scheduler.DatabaseFacts.timestamp(rs, 10).toInstant()), reserved);
                    ReservationState.Hold hold = reserved ? new ReservationState.Hold(visit.jobId(), dev.waterflex.scheduler.DatabaseFacts.string(rs, 13),
                            Required.value(dev.waterflex.scheduler.DatabaseFacts.timestamp(rs, 12).toInstant()), dev.waterflex.scheduler.DatabaseFacts.bool(rs, 14)) : null;
                    return new Stop(visit, hold, dev.waterflex.scheduler.DatabaseFacts.bool(rs, 15));
                }, Required.value(args.toArray(new @Nullable Object[0]))));
    }

    private Saved saved(String metroId, LocalDate day) {
        var rows = jdbc.query("SELECT version,state::text FROM reservation_arrangement WHERE \"metroId\"=? AND \"serviceDate\"=?",
                (rs, _) -> {
                    int version = dev.waterflex.scheduler.DatabaseFacts.integer(rs, 1);
                    String state = rs.getString(2);
                    if (version < 0 || (version != 0 && state == null)) throw conflict("Invalid reservation state/version");
                    return new Saved(version, state == null ? null : ReservationState.decode(json, state));
                }, metroId, stamp(day));
        if (rows.isEmpty()) return new Saved(0, null);
        if (rows.size() != 1) throw conflict("Duplicate reservation arrangement");
        return Required.value(rows.getFirst());
    }

    private static Arrangement restore(ReservationState state, Map<String, Technician> technicians, Map<String, Visit> visits, String requestingJob) {
        Map<String, List<String>> routes = new TreeMap<>();
        technicians.keySet().forEach(id -> routes.put(id, new ArrayList<>()));
        for (var entry : state.arrangement().routes().entrySet()) {
            if (entry.getValue().isEmpty()) continue;
            Technician tech = technicians.get(entry.getKey());
            if (tech == null || !Objects.equals(state.scheduleVersions().get(entry.getKey()), tech.scheduleVersion()))
                throw conflict("Reservation schedule versions changed");
            for (String id : entry.getValue()) {
                if (visits.containsKey(id)) Required.value(routes.get(entry.getKey())).add(id);
                else {
                    ReservationState.Hold hold = state.holds().get(id);
                    if (hold == null) throw conflict("A reserved appointment is missing");
                    if (!hold.jobId().equals(requestingJob) && hold.expiresAt().isAfter(Instant.now()))
                        throw conflict("A reserved hold changed without an arrangement update");
                }
            }
        }
        Arrangement restored = new Arrangement(routes);
        Set<String> present = new HashSet<>(); routes.values().forEach(present::addAll);
        if (!present.equals(visits.keySet())) throw conflict("Reservation coverage changed");
        return restored;
    }

    private static void fingerprint(StringBuilder target, LocalDate day, Technician technician) {
        for (Object value : List.of(day, technician.id(), technician.shiftStart(), technician.shiftEnd(), technician.maxDailyMinutes(),
                technician.maxOvertimeMinutes(), new TreeSet<>(technician.services()), technician.absences(), technician.departure(), technician.returnTo()))
            append(target, Required.value(value.toString()));
    }
    private static void append(StringBuilder target, String value) { target.append(value.length()).append(':').append(value); }
    private static String digest(String value) {
        try { return Required.value(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)))); }
        catch (java.security.NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
    private static String placeholders(int size) { return Required.value(String.join(",", Collections.nCopies(size, "?"))); }
    private static Timestamp stamp(LocalDate day) { return Required.value(Timestamp.from(day.atStartOfDay(ZoneOffset.UTC).toInstant())); }
    private static ResponseStatusException conflict(String detail) { return new ResponseStatusException(HttpStatus.CONFLICT, detail); }
}
