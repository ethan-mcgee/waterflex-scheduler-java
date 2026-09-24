package dev.waterflex.scheduler;

import dev.waterflex.scheduler.BookingSnapshot.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Read-only removal validation. The portal holds the job, configuration and day locks through apply. */
@RestController
public final class PurgeRoutesController {
    public record RequestedDay(String technicianId, String serviceDate) {
        public RequestedDay { technicianId = RequestChecks.text(technicianId, "technicianId");
            serviceDate = RequestChecks.date(serviceDate); }
    }
    public record Request(List<String> jobIds, List<RequestedDay> days) {
        public Request {
            if (jobIds == null || days == null || jobIds.isEmpty() || jobIds.size() > 10000 || days.size() > 1000)
                throw badRequest();
            for (String id : jobIds) RequestChecks.text(id, "jobId");
            for (RequestedDay day : days) if (day == null) throw badRequest();
            if (new HashSet<>(days).size() != days.size() || new HashSet<>(jobIds).size() != jobIds.size()) throw badRequest();
            jobIds = Required.value(List.copyOf(jobIds)); days = Required.value(List.copyOf(days));
        }
    }
    public record Stop(String id, Instant plannedStart, Instant plannedEnd) { }
    public record Segment(Instant departure, Instant returnedAt, List<String> appointmentIds) { }
    public record Prepared(String technicianId, LocalDate serviceDate, long version, @Nullable String routingIdentity,
                           List<Stop> stops, List<Segment> segments) { }
    private final JdbcTemplate jdbc;
    private final BookingSnapshotLoader loader;
    private final SnapshotRouting routing;
    private final RoadClient roads;
    public PurgeRoutesController(JdbcTemplate jdbc, BookingSnapshotLoader loader, SnapshotRouting routing, RoadClient roads) {
        this.jdbc = jdbc; this.loader = loader; this.routing = routing; this.roads = roads;
    }

    @PostMapping("/v1/purge/validate-routes")
    public List<Prepared> validate(@RequestBody Request request) {
        List<Prepared> result = new ArrayList<>();
        Set<String> removed = new HashSet<>(request.jobIds());
        String identity = roads.activeIdentity();
        for (RequestedDay requested : request.days()) {
            String technician = requested.technicianId(); LocalDate date = Required.value(LocalDate.parse(requested.serviceDate()));
            Timestamp stamp = Required.value(Timestamp.from(date.atStartOfDay(ZoneOffset.UTC).toInstant()));
            long version = Required.query(jdbc, "SELECT version FROM schedule_day WHERE \"technicianId\"=? AND \"serviceDate\"=?", Long.class, technician, stamp);
            List<String> remaining = jdbc.query("SELECT \"jobId\" FROM appointment WHERE \"technicianId\"=? AND \"serviceDate\"=? AND \"cancelledAt\" IS NULL",
                    (rs, _) -> Required.string(rs, 1), technician, stamp).stream().filter(id -> !removed.contains(id)).toList();
            if (remaining.isEmpty()) {
                result.add(new Prepared(technician, date, version, null, Required.value(List.of()), Required.value(List.of()))); continue;
            }
            if (ScheduleCutoff.frozen(date, Required.value(Instant.now()))) throw conflict("Cannot retime surviving appointments after the service cutoff");
            String metro = Required.query(jdbc, "SELECT p.\"metroId\" FROM technician t" + RouteEndpoints.JOINS + " WHERE t.id=?", String.class, stamp, stamp, technician);
            var facts = loader.loadDates(metro, Required.value(List.of(date)), Required.value(Instant.now()), identity);
            Day original = Required.value(facts.days().get(date), "purge service date");
            Technician tech = Required.value(original.technicians().get(technician), "purge technician");
            Map<String, Visit> visits = new TreeMap<>();
            List<String> ids = new ArrayList<>();
            for (String id : Required.value(original.actualArrangement().routes().get(technician), "purge route")) {
                Visit visit = Required.value(original.visits().get(id));
                if (!visit.reservation() && !removed.contains(visit.jobId())) { visits.put(id, visit); ids.add(id); }
            }
            if (visits.size() != remaining.size()) throw conflict("Surviving appointment coverage changed");
            Arrangement arrangement = new Arrangement(Required.value(Map.of(technician, ids)));
            Day day = new Day(Required.value(Map.of(technician, tech)), visits, arrangement, original.reservationVersion(), original.roads());
            day = routing.arrangements(date, day, visits, Required.value(List.of(arrangement)), identity);
            var evaluated = day.evaluate(arrangement, visits, facts.rates());
            if (!evaluated.feasible()) throw conflict("Removing these appointments requires a route repair");
            List<Stop> stops = new ArrayList<>();
            for (String id : ids) {
                Instant start = Required.value(evaluated.arrivals().get(id), "surviving arrival");
                stops.add(new Stop(Required.value(id), start, Required.value(start.plusSeconds(60L * Required.value(visits.get(id)).durationMinutes()))));
            }
            List<Segment> segments = Required.value(evaluated.segments().get(technician)).stream()
                    .<Segment>map(segment -> new Segment(segment.departure(), segment.returnedAt(), segment.visitIds())).toList();
            result.add(new Prepared(technician, date, version, identity, stops, Required.value(segments)));
        }
        return result;
    }
    private static ResponseStatusException badRequest() { return new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid purge validation request"); }
    private static ResponseStatusException conflict(String message) { return new ResponseStatusException(HttpStatus.CONFLICT, message); }
}
