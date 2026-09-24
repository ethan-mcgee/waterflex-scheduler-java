package dev.waterflex.scheduler;

import dev.waterflex.scheduler.BookingSnapshot.*;
import dev.waterflex.scheduler.optimizer.DayPlan;
import java.net.URI;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

/** Explicit database gate: -Dtest=BookingSnapshotDatabaseIT, using migrated/seeded waterflex_test only. */
class BookingSnapshotDatabaseIT {
    @Test void snapshotLoadAndReservationStoreUseRealDatabaseVersionsAndRestartState() {
        String url = Required.value(System.getenv("JDBC_DATABASE_URL"), "isolated integration database URL");
        assertEquals("/waterflex_test", URI.create(url.substring("jdbc:".length())).getPath());
        DriverManagerDataSource source = new DriverManagerDataSource(url, "waterflex", "waterflex");
        JdbcTemplate jdbc = new JdbcTemplate(source);
        var manager = new DataSourceTransactionManager(source);
        var transaction = new TransactionTemplate(manager);
        String prefix = "snapshot-it-" + UUID.randomUUID();
        Instant captured = Required.value(Instant.now());
        LocalDate day = Required.value(BookingService.bookingDates(captured).getFirst());
        Timestamp date = stamp(day);
        Timestamp morning = Required.value(Timestamp.from(ScheduleCutoff.localMinute(day, 540, false)));
        Timestamp afternoon = Required.value(Timestamp.from(ScheduleCutoff.localMinute(day, 720, false)));
        Timestamp expiry = Required.value(Timestamp.from(captured.plusSeconds(600)));
        try {
            jdbc.update("INSERT INTO metro (id,name,timezone) VALUES (?,?,'America/Chicago')", prefix, prefix);
            jdbc.update("INSERT INTO dealership (id,name,\"updatedAt\") VALUES (?,?,CURRENT_TIMESTAMP)", prefix, prefix);
            jdbc.update("INSERT INTO depot (id,\"metroId\",\"dealershipId\",name,lat,lng) VALUES (?,?,?,?,43.735,7.420)", prefix, prefix, prefix, prefix);
            jdbc.update("INSERT INTO depot_endpoint_policy (\"depotId\",\"effectiveDate\",departure,\"returnTo\") VALUES (?,'1900-01-01','HOME','HOME')", prefix);
            for (String service : List.of(prefix + "-service", prefix + "-other-service"))
                jdbc.update("INSERT INTO service_catalog (id,code,name,\"estDurationMin\",\"updatedAt\") VALUES (?,?,?,30,CURRENT_TIMESTAMP)", service, service, service);
            for (String tech : List.of(prefix + "-a", prefix + "-b")) {
                jdbc.update("INSERT INTO technician (id,name,color,\"homeLat\",\"homeLng\",\"shiftStartMin\",\"shiftEndMin\",\"maxDailyMinutes\",\"maxOvertimeMinutes\",\"updatedAt\") VALUES (?,?,'#059669',43.735,7.420,480,1020,600,60,CURRENT_TIMESTAMP)", tech, tech);
                jdbc.update("INSERT INTO technician_depot_assignment (\"technicianId\",\"depotId\",\"effectiveDate\") VALUES (?,?,'1900-01-01')", tech, prefix);
                jdbc.update("INSERT INTO technician_availability_version (id,\"technicianId\",\"effectiveDate\") VALUES (?,?,'1900-01-01')", tech, tech);
                for (int weekday = 0; weekday < 7; weekday++) jdbc.update("INSERT INTO technician_availability_day (\"versionId\",\"dayOfWeek\",available,\"shiftStartMin\",\"shiftEndMin\") VALUES (?,?,true,480,1020)", tech, weekday);
                jdbc.update("INSERT INTO technician_qualification (\"technicianId\",\"serviceId\") VALUES (?,?)", tech, prefix + "-other-service");
                jdbc.update("INSERT INTO schedule_day (id,\"technicianId\",\"serviceDate\",version) VALUES (?,?,?,0)", tech, tech, date);
            }
            jdbc.update("INSERT INTO technician_qualification (\"technicianId\",\"serviceId\") VALUES (?,?)", prefix + "-a", prefix + "-service");
            jdbc.update("INSERT INTO customer (id,\"firstName\",\"lastName\",email,phone) VALUES (?,'Snapshot','Test','snapshot@example.invalid','0000000000')", prefix);
            jdbc.update("INSERT INTO address (id,\"customerId\",line1,city,state,\"postalCode\",lat,lng) VALUES (?,?,'Fixture','Monaco','MC','98000',43.735,7.420)", prefix, prefix);
            for (String job : List.of(prefix + "-request", prefix + "-confirmed", prefix + "-held"))
                jdbc.update("INSERT INTO job (id,\"customerId\",\"addressId\",\"serviceId\",\"durationMin\",\"updatedAt\") VALUES (?,?,?,?,30,CURRENT_TIMESTAMP)", job, prefix, prefix,
                        job.endsWith("-confirmed") ? prefix + "-other-service" : prefix + "-service");
            jdbc.update("UPDATE job SET status='SCHEDULED' WHERE id=?", prefix + "-confirmed");
            jdbc.update("INSERT INTO appointment (id,\"jobId\",\"technicianId\",\"serviceDate\",\"windowStart\",\"windowEnd\",\"plannedStart\",\"plannedEnd\",sequence,\"updatedAt\") VALUES (?,?,?,?,?,?::timestamp+INTERVAL '2 hours',?,?::timestamp+INTERVAL '30 minutes',0,CURRENT_TIMESTAMP)",
                    prefix + "-appointment", prefix + "-confirmed", prefix + "-a", date, morning, morning, morning, morning);
            for (String job : List.of(prefix + "-request", prefix + "-held")) {
                jdbc.update("INSERT INTO booking_offer (id,\"jobId\",\"serviceDate\",\"windowStart\",\"windowEnd\",\"expiresAt\") VALUES (?,?,?,?,?::timestamp+INTERVAL '2 hours',?)", job, job, date, afternoon, afternoon, expiry);
                jdbc.update("INSERT INTO slot_hold (id,\"offerToken\",\"jobId\",\"technicianId\",\"serviceDate\",\"windowStart\",\"windowEnd\",\"plannedStart\",\"plannedEnd\",\"insertPosition\",\"locationLat\",\"locationLng\",\"expiresAt\") VALUES (?,?,?,?,?,?,?::timestamp+INTERVAL '2 hours',?,?::timestamp+INTERVAL '30 minutes',1,43.735,7.420,?)",
                        job, job, job, prefix + "-a", date, afternoon, afternoon, afternoon, afternoon, expiry);
            }
            BookingSnapshotLoader loader = new BookingSnapshotLoader(jdbc, manager);
            var loaded = loader.load(prefix, prefix + "-request", captured, "fixture-roads");
            assertEquals(BookingService.bookingDates(captured).size(), loaded.snapshot().days().size());
            Day raw = Required.value(loaded.snapshot().days().get(day));
            assertEquals(Set.of(prefix + "-appointment", prefix + "-held"), raw.visits().keySet());
            assertEquals(Set.of(prefix + "-held"), Required.value(loaded.holds().get(day)).keySet());
            assertThrows(BookingSnapshot.Incomplete.class, () -> raw.evaluate(raw.baseline(), raw.visits(), loaded.snapshot().rates()));
            jdbc.update("UPDATE slot_hold SET \"locationLat\"=NULL WHERE id=?", prefix + "-held");
            assertThrows(ResponseStatusException.class, () -> loader.load(prefix, prefix + "-request", captured, "fixture-roads"));
            jdbc.update("UPDATE slot_hold SET \"locationLat\"=43.735 WHERE id=?", prefix + "-held");
            jdbc.update("UPDATE slot_hold SET \"releasedAt\"=CURRENT_TIMESTAMP WHERE id=?", prefix + "-request");
            Map<String, DayPlan.RoadLeg> legs = new HashMap<>();
            for (String tech : raw.technicians().keySet()) for (String visit : raw.visits().keySet()) {
                legs.put(tech + ">" + visit, new DayPlan.RoadLeg(0, 0));
                legs.put(visit + ">" + tech + ":return", new DayPlan.RoadLeg(0, 0));
                for (String other : raw.visits().keySet()) if (!visit.equals(other)) legs.put(visit + ">" + other, new DayPlan.RoadLeg(0, 0));
            }
            Day routed = new Day(raw.technicians(), raw.visits(), raw.baseline(), raw.reservationVersion(), new Roads(legs, Required.value(Set.of())));
            Arrangement proposal = new Arrangement(Required.value(Map.of(prefix + "-a", List.of(prefix + "-held"), prefix + "-b", List.of(prefix + "-appointment"))));
            ReservationStore store = new ReservationStore(jdbc);
            ReservationStore.Locked saved = Required.value(transaction.execute(_ -> {
                var lock = Required.value(store.lock(prefix, Required.value(List.of(day))).getFirst());
                return store.save(lock, routed, loaded.snapshot().rates(), proposal, raw.visits(), Required.value(loaded.holds().get(day)), loaded.snapshot().configurationFingerprint(), "fixture-roads");
            }));
            assertEquals(1, saved.version());
            assertEquals(prefix + "-a", Required.query(jdbc, "SELECT \"technicianId\" FROM appointment WHERE id=?", String.class, prefix + "-appointment"), "Reservation must not move confirmed appointments");
            assertEquals(1, Required.query(jdbc, "SELECT count(*) FROM reservation_dependency WHERE \"holdId\"=? AND \"technicianId\"=? AND \"serviceId\"=?", Integer.class, prefix + "-held", prefix + "-b", prefix + "-other-service"));
            ReservationStore restarted = new ReservationStore(jdbc);
            var recovered = transaction.execute(_ -> restarted.lock(prefix, Required.value(List.of(day))).getFirst());
            assertEquals(saved, recovered);
            var reloaded = loader.load(prefix, prefix + "-request", captured, "fixture-roads");
            assertEquals(proposal, Required.value(reloaded.snapshot().days().get(day)).baseline());
            jdbc.update("UPDATE schedule_day SET version=version+1 WHERE \"technicianId\"=? AND \"serviceDate\"=?", prefix + "-b", date);
            assertThrows(ResponseStatusException.class, () -> loader.load(prefix, prefix + "-request", captured, "fixture-roads"));
            jdbc.update("UPDATE slot_hold SET \"expiresAt\"=CURRENT_TIMESTAMP-INTERVAL '1 second' WHERE id=?", prefix + "-held");
            var expired = loader.load(prefix, prefix + "-request", captured, "fixture-roads");
            assertTrue(Required.value(expired.holds().get(day)).isEmpty());
            assertEquals(List.of(prefix + "-appointment"), Required.value(expired.snapshot().days().get(day)).baseline().routes().get(prefix + "-a"),
                    "Expired reservations must not retain a shadow reassignment");
        } finally {
            jdbc.update("DELETE FROM reservation_arrangement WHERE \"metroId\"=?", prefix);
            jdbc.update("DELETE FROM appointment WHERE id=?", prefix + "-appointment");
            for (String job : List.of(prefix + "-request", prefix + "-confirmed", prefix + "-held")) {
                jdbc.update("DELETE FROM slot_hold WHERE \"jobId\"=?", job);
                jdbc.update("DELETE FROM booking_offer WHERE \"jobId\"=?", job);
                jdbc.update("DELETE FROM job WHERE id=?", job);
            }
            jdbc.update("DELETE FROM address WHERE id=?", prefix);
            jdbc.update("DELETE FROM customer WHERE id=?", prefix);
            for (String tech : List.of(prefix + "-a", prefix + "-b")) {
                jdbc.update("DELETE FROM schedule_day WHERE \"technicianId\"=?", tech);
                jdbc.update("DELETE FROM technician_qualification WHERE \"technicianId\"=?", tech);
                jdbc.update("DELETE FROM technician WHERE id=?", tech);
            }
            jdbc.update("DELETE FROM service_catalog WHERE id IN (?,?)", prefix + "-service", prefix + "-other-service");
            jdbc.update("DELETE FROM depot WHERE id=?", prefix);
            jdbc.update("DELETE FROM dealership WHERE id=?", prefix);
            jdbc.update("DELETE FROM metro WHERE id=?", prefix);
        }
    }
    private static Timestamp stamp(LocalDate day) { return Required.value(Timestamp.from(day.atStartOfDay(ZoneOffset.UTC).toInstant())); }
}
