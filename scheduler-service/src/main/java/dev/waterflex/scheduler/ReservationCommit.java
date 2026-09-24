package dev.waterflex.scheduler;

import dev.waterflex.scheduler.BookingSnapshot.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** Short database-only commit. Search, routing and proposal construction happen before entry. */
@Component
public final class ReservationCommit {
    private record LockKey(String technician, LocalDate date) { }
    private final JdbcTemplate jdbc;
    private final BookingSnapshotLoader loader;
    private final ReservationStore store;
    private final TransactionTemplate transaction;
    public ReservationCommit(JdbcTemplate jdbc, BookingSnapshotLoader loader, ReservationStore store, PlatformTransactionManager manager) {
        this.jdbc = jdbc; this.loader = loader; this.store = store;
        transaction = new TransactionTemplate(manager); transaction.setTimeout(5);
    }

    public <T> T commit(BookingSnapshotLoader.Facts expected, String jobId, String excludedJob, boolean pendingRequired,
            Map<LocalDate, ReservationTransition.Prepared> proposals, boolean applyConfirmed, Supplier<T> mutation) {
        return commit(expected, jobId, excludedJob, pendingRequired, proposals, applyConfirmed, false, mutation);
    }

    public <T> T cancellation(BookingSnapshotLoader.Facts expected, String jobId,
            Map<LocalDate, ReservationTransition.Prepared> proposals, Supplier<T> mutation) {
        return commit(expected, jobId, "", false, proposals, true, true, mutation);
    }

    private <T> T commit(BookingSnapshotLoader.Facts expected, String jobId, String excludedJob, boolean pendingRequired,
            Map<LocalDate, ReservationTransition.Prepared> proposals, boolean applyConfirmed, boolean keepAssignments, Supplier<T> mutation) {
        SearchDeadline.beginCommit();
        return Required.value(transaction.execute(_ -> {
            SearchDeadline.database(jdbc);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void beforeCommit(boolean readOnly) { SearchDeadline.checkpoint(); }
            });
            if (jdbc.query("SELECT id FROM job WHERE id=? FOR UPDATE", (rs, _) -> Required.string(rs, 1), jobId).size() != 1)
                throw conflict("Job no longer exists");
            Set<LockKey> keys = new TreeSet<>(Comparator.comparing((LockKey key) -> key.technician()).thenComparing(key -> key.date()));
            expected.days().forEach((date, day) -> day.technicians().keySet().forEach(id -> keys.add(new LockKey(Required.value(id), Required.value(date)))));
            Set<String> techniciansToLock = new TreeSet<>();
            keys.forEach(key -> techniciansToLock.add(key.technician()));
            if (!techniciansToLock.isEmpty()) {
                SearchDeadline.database(jdbc);
                List<Object> technicianArguments = new ArrayList<>(techniciansToLock);
                if (jdbc.query("SELECT id FROM technician WHERE id IN (" + String.join(",", Collections.nCopies(techniciansToLock.size(), "?")) + ") ORDER BY id FOR SHARE",
                        (rs, _) -> Required.string(rs, 1), technicianArguments.toArray(new @Nullable Object[0])).size() != techniciansToLock.size())
                    throw conflict("Technician no longer exists");
            }
            jdbc.queryForList("SELECT key FROM omaha_setting ORDER BY key FOR SHARE");
            if (!keys.isEmpty()) {
                SearchDeadline.database(jdbc);
                List<@Nullable Object[]> inserts = new ArrayList<>();
                List<Object> arguments = new ArrayList<>();
                for (LockKey key : keys) {
                    inserts.add(new @Nullable Object[]{UUID.randomUUID().toString(), key.technician(), stamp(key.date())});
                    arguments.add(key.technician()); arguments.add(stamp(key.date()));
                }
                jdbc.batchUpdate("INSERT INTO schedule_day (id,\"technicianId\",\"serviceDate\",version) VALUES (?,?,?,0) ON CONFLICT (\"technicianId\",\"serviceDate\") DO NOTHING", inserts);
                if (jdbc.query("SELECT version FROM schedule_day WHERE (\"technicianId\",\"serviceDate\") IN ("
                        + String.join(",", Collections.nCopies(keys.size(), "(?,?)")) + ") ORDER BY \"technicianId\",\"serviceDate\" FOR UPDATE",
                        (rs, _) -> Required.integer(rs, 1), arguments.toArray(new @Nullable Object[0])).size() != keys.size())
                    throw conflict("Schedule lock coverage changed");
            }
            List<LocalDate> dates = new ArrayList<>(new TreeSet<>(expected.days().keySet()));
            List<ReservationStore.Locked> headers = store.lock(expected.metroId(), dates);
            var current = loader.locked(expected.metroId(), dates, expected.capturedAt(), expected.routingIdentity(), excludedJob, pendingRequired);
            unchanged(expected, current);
            if (!expected.days().keySet().containsAll(proposals.keySet())) throw conflict("Proposal dates are outside locked snapshot");
            for (var entry : proposals.entrySet()) {
                if (applyConfirmed && ScheduleCutoff.frozen(Required.value(entry.getKey()), Required.value(Instant.now())))
                    throw conflict("Service date is frozen");
                var proposal = Required.value(entry.getValue());
                ReservationState.validate(proposal.day(), expected.rates(), proposal.day().baseline(), proposal.day().visits(),
                        proposal.holds(), expected.configurationFingerprint(), expected.routingIdentity());
            }
            T result = mutation.get();
            for (ReservationStore.Locked header : headers) {
                var proposal = proposals.get(header.day());
                if (proposal == null) continue;
                Day day = proposal.day();
                var evaluated = day.evaluate(day.baseline(), day.visits(), expected.rates());
                if (!evaluated.feasible()) throw conflict("Reservation proposal is no longer feasible");
                Arrangement actualArrangement = keepAssignments ? day.actualArrangement() : day.baseline();
                var actual = keepAssignments ? dev.waterflex.scheduler.optimizer.RouteEvaluator.evaluate(day.plan(actualArrangement, day.visits(), expected.rates(), true)) : evaluated;
                if (!actual.feasible()) throw conflict("Remaining confirmed routes require repair");
                Map<String, Visit> facts = new TreeMap<>();
                for (var route : day.baseline().routes().entrySet()) {
                    int sequence = 0;
                    for (String id : route.getValue()) {
                        Visit visit = Required.value(day.visits().get(id), "committed visit");
                        Instant arrival = Required.value(evaluated.arrivals().get(id), "committed arrival");
                        String assigned = Required.value(route.getKey());
                        if (visit.reservation()) {
                            if (jdbc.update("UPDATE slot_hold SET \"technicianId\"=?,\"insertPosition\"=?,\"plannedStart\"=?,\"plannedEnd\"=? WHERE id=? AND \"releasedAt\" IS NULL AND \"expiresAt\">clock_timestamp()",
                                    route.getKey(), sequence, stamp(arrival), stamp(Required.value(arrival.plusSeconds(visit.durationMinutes() * 60L))), id) != 1)
                                throw conflict("Reservation changed during commit");
                        } else if (applyConfirmed) {
                            if (keepAssignments) {
                                assigned = visit.originalTechnicianId();
                                arrival = Required.value(actual.arrivals().get(id), "confirmed arrival");
                            }
                            int actualSequence = Required.value(actualArrangement.routes().get(assigned)).indexOf(id);
                            if (actualSequence < 0) throw conflict("Confirmed assignment is missing");
                            if (jdbc.update("UPDATE appointment SET \"technicianId\"=?,sequence=?,\"plannedStart\"=?,\"plannedEnd\"=?,\"updatedAt\"=CURRENT_TIMESTAMP WHERE id=? AND \"cancelledAt\" IS NULL",
                                    assigned, actualSequence, stamp(arrival), stamp(Required.value(arrival.plusSeconds(visit.durationMinutes() * 60L))), id) != 1)
                                throw conflict("Appointment changed during commit");
                        }
                        facts.put(id, applyConfirmed || visit.reservation() ? new Visit(visit.id(), visit.jobId(), visit.serviceId(), visit.windowStart(), visit.windowEnd(),
                                visit.durationMinutes(), visit.location(), assigned, arrival, visit.reservation()) : visit);
                        sequence++;
                    }
                }
                Map<String, Technician> technicians = new TreeMap<>();
                for (Technician technician : day.technicians().values()) {
                    long version = technician.scheduleVersion();
                    if (applyConfirmed) {
                        if (jdbc.update("UPDATE schedule_day SET version=version+1 WHERE \"technicianId\"=? AND \"serviceDate\"=? AND version=?",
                                technician.id(), stamp(header.day()), version) != 1) throw conflict("Schedule changed during commit");
                        version = Math.incrementExact(version);
                    }
                    technicians.put(technician.id(), new Technician(technician.id(), technician.shiftStart(), technician.shiftEnd(), technician.maxDailyMinutes(),
                            technician.maxOvertimeMinutes(), technician.services(), technician.absences(), technician.departure(), technician.returnTo(), version));
                }
                Day committed = new Day(technicians, facts, day.baseline(), day.reservationVersion(), day.roads());
                store.save(header, committed, expected.rates(), committed.baseline(), facts, proposal.holds(), expected.configurationFingerprint(), expected.routingIdentity());
            }
            SearchDeadline.checkpoint();
            return result;
        }), "reservation commit result");
    }

    private static void unchanged(BookingSnapshotLoader.Facts expected, BookingSnapshotLoader.Facts current) {
        if (!expected.configurationFingerprint().equals(current.configurationFingerprint()) || !expected.rates().equals(current.rates())
                || !expected.policy().equals(current.policy()) || !expected.holds().equals(current.holds()) || !expected.days().keySet().equals(current.days().keySet()))
            throw conflict("Scheduling configuration or reservations changed");
        expected.days().forEach((date, before) -> {
            Day after = Required.value(current.days().get(date), "locked date");
            if (!before.technicians().equals(after.technicians()) || !before.visits().equals(after.visits())
                    || !before.baseline().equals(after.baseline()) || before.reservationVersion() != after.reservationVersion())
                throw conflict("Schedule or reservation arrangement changed");
        });
    }
    private static Timestamp stamp(LocalDate day) { return stamp(Required.value(day.atStartOfDay(ZoneOffset.UTC).toInstant())); }
    private static Timestamp stamp(Instant value) { return Required.value(Timestamp.from(value)); }
    private static ResponseStatusException conflict(String message) { return new ResponseStatusException(HttpStatus.CONFLICT, message); }
}
