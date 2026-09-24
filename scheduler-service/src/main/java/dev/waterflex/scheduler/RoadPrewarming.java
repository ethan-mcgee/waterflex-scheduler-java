package dev.waterflex.scheduler;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Versioned, bounded, optional cache work. It cannot mutate schedules or reservations. */
@Component
public final class RoadPrewarming {
    private record Day(String id, String technicianId, LocalDate date, int version) { }
    private record Snapshot(Day day, RouteEndpoints endpoints, List<RoadClient.Point> stops) { }
    private record Completed(Snapshot snapshot, String identity, Instant expires) { }
    private final JdbcTemplate jdbc;
    private final RoadClient roads;
    private final SearchAdmission admission;
    private final TransactionTemplate reads;
    private final boolean enabled;
    private final Map<String, Completed> completed = new LinkedHashMap<>();
    private String cursor = "";

    public RoadPrewarming(JdbcTemplate jdbc, RoadClient roads, SearchAdmission admission,
                          PlatformTransactionManager transactions, @Value("${routing.prewarm.enabled:false}") boolean enabled) {
        this.jdbc = jdbc; this.roads = roads; this.admission = admission; this.enabled = enabled;
        reads = new TransactionTemplate(transactions);
        reads.setReadOnly(true); reads.setTimeout(2);
        reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        reads.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Scheduled(fixedDelayString = "${routing.prewarm.poll-ms:1000}")
    public void poll() {
        if (!enabled) return;
        var lease = admission.tryIdleBackground();
        if (lease == null) return;
        try (lease) {
            SearchDeadline deadline = new SearchDeadline(Required.value(Duration.ofSeconds(3)));
            deadline.within(() -> { warmOne(); return true; });
        } catch (RuntimeException failure) {
            org.slf4j.LoggerFactory.getLogger(RoadPrewarming.class).debug("Road prewarming deferred", failure);
        }
    }

    private void warmOne() {
        var days = Required.value(reads.execute(_ -> {
            SearchDeadline.database(jdbc);
            return jdbc.query("SELECT id,\"technicianId\",\"serviceDate\",version FROM schedule_day WHERE id>? AND \"serviceDate\">=CURRENT_DATE AND \"serviceDate\"<CURRENT_DATE+INTERVAL '21 days' ORDER BY id LIMIT 1",
                    (rs, _) -> new Day(Required.string(rs, 1), Required.string(rs, 2), Required.value(Required.timestamp(rs, 3).toLocalDateTime().toLocalDate()), Required.integer(rs, 4)), cursor);
        }));
        if (days.isEmpty()) { cursor = ""; return; }
        Day day = Required.value(days.getFirst()); cursor = day.id();
        if (ScheduleCutoff.frozen(day.date(), Required.value(Instant.now()))) return;
        Snapshot snapshot = Required.value(reads.execute(_ -> {
            SearchDeadline.database(jdbc);
            int version = version(day);
            RouteEndpoints endpoints = RouteEndpoints.forTechnician(jdbc, day.technicianId(), day.date());
            var stops = jdbc.query("SELECT ad.lat,ad.lng FROM appointment a JOIN job j ON j.id=a.\"jobId\" JOIN address ad ON ad.id=j.\"addressId\" WHERE a.\"technicianId\"=? AND a.\"serviceDate\"=? AND a.\"cancelledAt\" IS NULL ORDER BY a.sequence,a.id LIMIT 129",
                    (rs, _) -> Required.location(rs, 1, 2, HttpStatus.CONFLICT), day.technicianId(), stamp(day.date()));
            if (stops.size() > 128) throw new IllegalStateException("Prewarm route exceeds bounded snapshot size");
            return new Snapshot(new Day(day.id(), day.technicianId(), day.date(), version), endpoints, Required.value(List.copyOf(stops)));
        }));
        if (snapshot.stops().isEmpty()) return;
        if (admission.state().queuedBookings() > 0) return;
        String identity = roads.activeIdentity();
        Completed prior = completed.get(day.id());
        if (prior != null && prior.snapshot().equals(snapshot) && prior.identity().equals(identity) && prior.expires().isAfter(Instant.now())) return;
        var pairs = pairs(snapshot.endpoints(), snapshot.stops());
        for (int offset = 0; offset < pairs.size(); offset += 32) {
            if (admission.state().queuedBookings() > 0 || admission.state().active() > 1) return;
            SearchDeadline.checkpoint();
            boolean unchanged = Required.value(reads.execute(_ -> { SearchDeadline.database(jdbc); return version(day) == snapshot.day().version(); }));
            if (!unchanged) return;
            roads.sparse(Required.value(pairs.subList(offset, Math.min(offset + 32, pairs.size()))), identity);
        }
        boolean unchanged = Required.value(reads.execute(_ -> { SearchDeadline.database(jdbc); return version(day) == snapshot.day().version(); }));
        if (unchanged && identity.equals(roads.currentVersion())) {
            completed.put(day.id(), new Completed(snapshot, identity, Required.value(Instant.now().plusSeconds(1800))));
            while (completed.size() > 2048) completed.remove(completed.keySet().iterator().next());
        }
    }

    static List<RoadClient.Pair> pairs(RouteEndpoints endpoints, List<RoadClient.Point> stops) {
        List<RoadClient.Pair> result = new ArrayList<>();
        for (int i = 0; i < stops.size(); i++) {
            var point = Required.value(stops.get(i));
            result.add(new RoadClient.Pair("departure>" + i, endpoints.departure(), point));
            result.add(new RoadClient.Pair(i + ">return", point, endpoints.returnTo()));
            for (int j = Math.max(0, i - 2); j < Math.min(stops.size(), i + 3); j++) if (i != j)
                result.add(new RoadClient.Pair(i + ">" + j, point, Required.value(stops.get(j))));
        }
        return Required.value(List.copyOf(result));
    }
    private int version(Day day) { return Required.query(jdbc, "SELECT version FROM schedule_day WHERE id=?", Integer.class, day.id()); }
    private static Timestamp stamp(LocalDate day) { return Required.value(Timestamp.from(day.atStartOfDay(ZoneOffset.UTC).toInstant())); }
}
