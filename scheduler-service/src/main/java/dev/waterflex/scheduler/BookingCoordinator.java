package dev.waterflex.scheduler;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Public booking orchestration using immutable search facts and durable common reservations. */
@Service
public final class BookingCoordinator {
    public record Result(BookingService.Offers offers, boolean completed, String stopReason) { }
    private record Active(String id, @Nullable String diagnostics) { }
    private final JdbcTemplate jdbc;
    private final BookingService booking;
    private final BookingSearchPipeline search;
    private final ReservationCommit commits;
    private final ReservationLifecycleService lifecycle;
    private final ObjectMapper json = new ObjectMapper();
    public BookingCoordinator(JdbcTemplate jdbc, BookingService booking, BookingSearchPipeline search,
            ReservationCommit commits, ReservationLifecycleService lifecycle) {
        this.jdbc = jdbc; this.booking = booking; this.search = search; this.commits = commits; this.lifecycle = lifecycle;
    }

    public Result offers(String jobId, boolean refresh) {
        var context = booking.searchContext(jobId);
        var active = jdbc.query("SELECT id,\"searchDiagnostics\"::text FROM booking_offer_set WHERE \"jobId\"=? AND \"expiresAt\">clock_timestamp() AND \"supersededAt\" IS NULL ORDER BY \"createdAt\" DESC LIMIT 1",
                (rs, _) -> new Active(Required.string(rs, 1), rs.getString(2)), jobId);
        if (!active.isEmpty()) {
            Active current = Required.value(active.getFirst());
            var saved = saved(jobId, current.id());
            if (!refresh) return new Result(saved, completed(current.diagnostics()), "RESERVED_OFFERS");
            if (!saved.offers().isEmpty()) lifecycle.release(jobId, Required.value(saved.offers().getFirst()).offerId());
            SearchDeadline.beginExploration();
        }
        // One stale retry at most, using the same request deadline and admission lease.
        for (int attempt = 0; attempt < 2; attempt++) {
            SearchDeadline.beginExploration();
            var prepared = search.prepare(context.metroId(), context.request());
            var bundle = prepared.reservations();
            org.slf4j.LoggerFactory.getLogger(BookingCoordinator.class).info("Booking search diagnostics for job {}: {}", jobId, diagnostics(prepared));
            if (bundle.offers().isEmpty()) return new Result(new BookingService.Offers(jobId, Required.value(List.of())),
                    prepared.search().complete() && bundle.completed(), prepared.stopReason());
            var raw = prepared.loaded().snapshot();
            var facts = new BookingSnapshotLoader.Facts(raw.metroId(), raw.capturedAt(), raw.configurationFingerprint(), raw.routingIdentity(),
                    raw.policy(), raw.rates(), raw.days(), prepared.loaded().holds());
            Map<LocalDate, ReservationTransition.Prepared> proposals = new TreeMap<>();
            bundle.dates().forEach((date, day) -> proposals.put(date, new ReservationTransition.Prepared(day.day(), day.state().holds(), day.validation())));
            String setId = Required.value(UUID.randomUUID().toString());
            try {
                return commits.commit(facts, jobId, jobId, true, proposals, false, () -> {
                    var lockedContext = booking.searchContext(jobId);
                    if (!context.equals(lockedContext)) throw conflict("Job or service location changed");
                    jdbc.update("UPDATE booking_offer_set s SET \"supersededAt\"=CURRENT_TIMESTAMP WHERE s.\"jobId\"=? AND s.\"supersededAt\" IS NULL AND NOT EXISTS (SELECT 1 FROM booking_offer o WHERE o.\"offerSetId\"=s.id)", jobId);
                    if (Required.query(jdbc, "SELECT count(*) FROM booking_offer_set WHERE \"jobId\"=? AND \"expiresAt\">clock_timestamp() AND \"supersededAt\" IS NULL", Integer.class, jobId) > 0)
                        throw conflict("Another search reserved offers for this job");
                    jdbc.update("UPDATE booking_offer_set SET \"supersededAt\"=CURRENT_TIMESTAMP WHERE \"jobId\"=? AND \"supersededAt\" IS NULL", jobId);
                    jdbc.update("INSERT INTO booking_offer_set (id,\"jobId\",\"expiresAt\",\"searchDiagnostics\") VALUES (?,?,?,?::jsonb)",
                            setId, jobId, stamp(prepared.expiresAt()), diagnostics(prepared));
                    SearchDeadline.reservedSet(setId);
                    List<BookingService.Offer> offers = new ArrayList<>();
                    for (ReservationOffers.Reserved reserved : bundle.offers()) {
                        var candidate = reserved.candidate();
                        var date = candidate.window().day();
                        var day = Required.value(bundle.dates().get(date));
                        var original = Required.value(prepared.routed().days().get(date));
                        var baseline = original.evaluate(original.baseline(), original.visits(), raw.rates());
                        var proposed = candidate.validation();
                        long regularDelta = proposed.paidMinutes() - proposed.overtimeMinutes() - baseline.paidMinutes() + baseline.overtimeMinutes();
                        Instant arrival = Required.value(day.validation().arrivals().get(reserved.holdId()), "reserved arrival");
                        int position = Required.value(day.day().baseline().routes().get(candidate.technicianId())).indexOf(reserved.holdId());
                        if (position < 0) throw conflict("Reserved placeholder is unassigned");
                        jdbc.update("INSERT INTO booking_offer (id,\"jobId\",\"serviceDate\",\"windowStart\",\"windowEnd\",\"expiresAt\",\"incrementalRegularMinutes\",\"incrementalOvertimeMinutes\",\"incrementalRoadMeters\",\"incrementalCostDollars\",\"offerSetId\",\"overtimeAuthorized\") VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                                reserved.offerId(), jobId, stamp(date), stamp(candidate.window().start()), stamp(candidate.window().end()), stamp(prepared.expiresAt()),
                                regularDelta, candidate.overtimeDelta(), proposed.meters() - baseline.meters(), candidate.costDeltaCents() / 100.0, setId, reserved.overtimeAuthorized());
                        jdbc.update("INSERT INTO slot_hold (id,\"offerToken\",\"jobId\",\"technicianId\",\"serviceDate\",\"windowStart\",\"windowEnd\",\"plannedStart\",\"plannedEnd\",\"insertPosition\",\"locationLat\",\"locationLng\",\"expiresAt\",\"offerSetId\") VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                                reserved.holdId(), reserved.offerId(), jobId, candidate.technicianId(), stamp(date), stamp(candidate.window().start()), stamp(candidate.window().end()), stamp(arrival),
                                stamp(Required.value(arrival.plusSeconds(context.request().durationMinutes() * 60L))), position, context.request().location().lat(), context.request().location().lng(), stamp(prepared.expiresAt()), setId);
                        offers.add(new BookingService.Offer(reserved.offerId(), Required.value(date.toString()), candidate.window().start(), candidate.window().end(), prepared.expiresAt()));
                    }
                    return new Result(new BookingService.Offers(jobId, offers), prepared.search().complete() && bundle.completed(), prepared.stopReason());
                });
            } catch (ResponseStatusException exception) {
                if (exception.getStatusCode().value() != 409 || attempt == 1) throw exception;
            }
        }
        throw new IllegalStateException("Booking retry did not terminate");
    }

    public boolean managedOffer(String jobId, String offerId) {
        return Required.query(jdbc, "SELECT EXISTS (SELECT 1 FROM slot_hold h LEFT JOIN booking_offer_set s ON s.id=h.\"offerSetId\" WHERE h.\"jobId\"=? AND h.\"offerToken\"=? AND (s.\"searchDiagnostics\" IS NOT NULL OR EXISTS (SELECT 1 FROM reservation_dependency d WHERE d.\"holdId\"=h.id)))", Boolean.class, jobId, offerId);
    }
    public boolean requiresCommonArrangement() {
        return Required.query(jdbc, "SELECT EXISTS (SELECT 1 FROM reservation_dependency d JOIN slot_hold h ON h.id=d.\"holdId\" WHERE h.\"releasedAt\" IS NULL AND h.\"expiresAt\">clock_timestamp())", Boolean.class);
    }
    public boolean managedHold(String holdId) {
        return Required.query(jdbc, "SELECT EXISTS (SELECT 1 FROM slot_hold h LEFT JOIN booking_offer_set s ON s.id=h.\"offerSetId\" WHERE h.id=? AND (s.\"searchDiagnostics\" IS NOT NULL OR EXISTS (SELECT 1 FROM reservation_dependency d WHERE d.\"holdId\"=h.id)))", Boolean.class, holdId);
    }
    public boolean managedAppointment(String appointmentId) {
        return Required.query(jdbc, "SELECT EXISTS (SELECT 1 FROM appointment a JOIN LATERAL (SELECT \"depotId\" FROM technician_depot_assignment WHERE \"technicianId\"=a.\"technicianId\" AND \"effectiveDate\"<=a.\"serviceDate\" ORDER BY \"effectiveDate\" DESC LIMIT 1) d ON true JOIN depot p ON p.id=d.\"depotId\" JOIN reservation_arrangement r ON r.\"metroId\"=p.\"metroId\" AND r.\"serviceDate\"=a.\"serviceDate\" JOIN reservation_dependency dependency ON dependency.\"arrangementId\"=r.id JOIN slot_hold h ON h.id=dependency.\"holdId\" WHERE a.id=? AND h.\"releasedAt\" IS NULL AND h.\"expiresAt\">clock_timestamp())", Boolean.class, appointmentId);
    }

    private BookingService.Offers saved(String jobId, String setId) {
        return new BookingService.Offers(jobId, Required.value(jdbc.query("SELECT id,\"serviceDate\",\"windowStart\",\"windowEnd\",\"expiresAt\" FROM booking_offer WHERE \"offerSetId\"=? ORDER BY \"windowStart\",id",
                (rs, _) -> new BookingService.Offer(Required.string(rs, 1), Required.value(Required.timestamp(rs, 2).toInstant().atZone(ZoneOffset.UTC).toLocalDate().toString()),
                        Required.value(Required.timestamp(rs, 3).toInstant()), Required.value(Required.timestamp(rs, 4).toInstant()), Required.value(Required.timestamp(rs, 5).toInstant())), setId)));
    }
    private boolean completed(@Nullable String encoded) {
        if (encoded == null) return false;
        try {
            var node = SavedJson.object(Required.value(json.readTree(encoded)));
            if (SavedJson.integer(node, "format") != 1 || !node.path("completed").isBoolean()) throw conflict("Malformed saved search diagnostics");
            return node.path("completed").asBoolean();
        } catch (java.io.IOException exception) { throw conflict("Malformed saved search diagnostics"); }
    }
    private String diagnostics(BookingSearchPipeline.Prepared prepared) {
        Map<String, Object> data = new LinkedHashMap<>();
        var result = prepared.search();
        data.put("format", 1); data.put("completed", result.complete() && prepared.reservations().completed());
        data.put("stopReason", prepared.stopReason()); data.put("configurationFingerprint", prepared.routed().configurationFingerprint());
        data.put("routingIdentity", prepared.routed().routingIdentity()); data.put("capturedAt", prepared.routed().capturedAt().toString());
        data.put("snapshotAgeMs", java.time.Duration.between(prepared.routed().capturedAt(), Instant.now()).toMillis());
        data.put("routingPairs", prepared.routed().days().values().stream().mapToInt(day -> day.roads().legs().size() + day.roads().unreachable().size()).sum());
        data.put("evaluatedRoutes", prepared.evaluatedRoutes()); data.put("reusedRoutes", prepared.reusedRoutes());
        SearchDeadline deadline = SearchDeadline.current();
        if (deadline != null) data.put("elapsedMs", deadline.elapsedMillis());
        data.put("distinctRegularWindows", result.distinctRegularWindows()); data.put("confirmedRegularMinutes", result.confirmedRegularMinutes());
        data.put("regularCapacityMinutes", result.regularCapacityMinutes()); data.put("overtimeAuthorized", result.overtimeAuthorized());
        data.put("limits", BoundedBookingSearch.Limits.defaults());
        List<Map<String, Object>> coverage = new ArrayList<>();
        for (var item : result.coverage()) {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("date", item.window().day().toString()); value.put("windowStart", item.window().start().toString()); value.put("windowEnd", item.window().end().toString());
            value.put("routes", item.routesExamined()); value.put("arrangements", item.arrangementsExamined()); value.put("moves", item.movesGenerated());
            value.put("candidateEvaluations", item.candidateEvaluations()); value.put("completed", item.complete()); value.put("stopReason", item.stopReason()); coverage.add(value);
        }
        data.put("coverage", coverage);
        Map<String, String> sources = new TreeMap<>(); prepared.reservations().offers().forEach(offer -> sources.put(offer.offerId(), offer.candidate().source()));
        data.put("offerSources", sources);
        try { return Required.value(json.writeValueAsString(data)); }
        catch (java.io.IOException exception) { throw new IllegalStateException("Search diagnostics could not be serialized", exception); }
    }
    private static Timestamp stamp(LocalDate day) { return stamp(Required.value(day.atStartOfDay(ZoneOffset.UTC).toInstant())); }
    private static Timestamp stamp(Instant value) { return Required.value(Timestamp.from(value)); }
    private static ResponseStatusException conflict(String message) { return new ResponseStatusException(HttpStatus.CONFLICT, message); }
}
