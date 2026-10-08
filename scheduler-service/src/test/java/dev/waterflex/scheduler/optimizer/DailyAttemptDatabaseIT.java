package dev.waterflex.scheduler.optimizer;

import dev.waterflex.scheduler.Required;
import dev.waterflex.scheduler.RoadClient;
import dev.waterflex.scheduler.RoadPoint;
import dev.waterflex.scheduler.SearchAdmission;
import dev.waterflex.scheduler.SearchDeadline;
import java.net.URI;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Committed, isolated fixtures: each worker and competing writer uses its own database connection. */
class DailyAttemptDatabaseIT {
    @Test void overnightRecordsRealPreviewFailuresAndKeepsTheFailedScheduleUnchanged() {
        try (var f = new Fixture(true)) {
            var before=f.jdbc.queryForList("SELECT * FROM appointment WHERE id=?",f.id+"-appointment");
            f.matrixHook.set(() -> { throw new IllegalStateException("Injected routing preparation failure"); });
            var overnight=new OvernightOptimization(f.jdbc,f.manager,request -> f.service.preview(Required.value(request)),f.clock);
            overnight.runDay(f.id,f.day);
            assertEquals(OvernightOptimization.State.FAILED,overnight.attempts(f.id,f.day).getFirst().state());
            assertEquals(before,f.jdbc.queryForList("SELECT * FROM appointment WHERE id=?",f.id+"-appointment"));
            assertEquals(0,f.runCount());
            assertEquals("FAILED",dev.waterflex.scheduler.DatabaseFacts.query(f.jdbc,"SELECT state FROM daily_calculation_attempt WHERE \"requestKey\"=?",String.class,overnight.attempts(f.id,f.day).getFirst().previewKey()));
            overnight.runDay(f.id,Required.value(f.day.plusDays(1)));
            assertEquals(OvernightOptimization.State.SKIPPED,overnight.attempts(f.id,Required.value(f.day.plusDays(1))).getFirst().state());
            assertEquals(before,f.jdbc.queryForList("SELECT * FROM appointment WHERE id=?",f.id+"-appointment"));
        }
    }
    @Test void overnightReplicasClaimEachMetroDayOncePerNight() throws Exception {
        try (var f = new Fixture(true)) {
            var calls=new java.util.concurrent.atomic.AtomicInteger();
            var bothStarted=new java.util.concurrent.CountDownLatch(2);
            var night=Required.value(LocalDate.of(2026,10,8));
            java.util.function.Supplier<OvernightOptimization> replica=() -> new OvernightOptimization(f.jdbc,f.manager,request -> {
                assertNotNull(request); calls.incrementAndGet();
                return Required.value(Map.of("status","PREVIEW","run_id",f.id+"-preview"));
            },f.clock);
            var first=replica.get(); var second=replica.get();
            try (var pool=java.util.concurrent.Executors.newFixedThreadPool(2)) {
                var a=pool.submit(() -> { bothStarted.countDown(); bothStarted.await(); first.runDay(f.id,f.day,night); return true; });
                var b=pool.submit(() -> { bothStarted.countDown(); bothStarted.await(); second.runDay(f.id,f.day,night); return true; });
                assertTrue(Required.value(a.get())); assertTrue(Required.value(b.get()));
            }
            assertEquals(1,calls.get());
            assertEquals(1,first.attempts(f.id,f.day).size());
            assertEquals(OvernightOptimization.State.SUCCEEDED,first.attempts(f.id,f.day).getFirst().state());
            assertEquals(1L,Required.value(first.metrics().get("claimedByOtherReplicaSinceProcessStart"))+Required.value(second.metrics().get("claimedByOtherReplicaSinceProcessStart")));
            second.runDay(f.id,f.day,Required.value(night.plusDays(1)));
            assertEquals(2,calls.get());
            assertEquals(2,first.attempts(f.id,f.day).size());
            f.jdbc.update("DELETE FROM overnight_optimization_attempt WHERE \"metroId\"=?",f.id);
        }
    }
    @Test void overnightFailuresAndCancellationAreDurableAndDoNotChangeAppointmentsOrStopOtherDays() {
        try (var f = new Fixture(true)) {
            var before=f.jdbc.queryForList("SELECT * FROM appointment WHERE id=?",f.id+"-appointment");
            var calls=new java.util.concurrent.atomic.AtomicInteger();
            var overnight=new OvernightOptimization(f.jdbc,f.manager,request -> {
                assertNotNull(request);
                return switch(calls.incrementAndGet()) {
                    case 1 -> throw new IllegalStateException("Injected calculation failure");
                    case 2 -> throw new dev.waterflex.scheduler.SearchDeadline.Expired();
                    case 3 -> Required.value(Map.of("status","SKIPPED","reason","No appointments"));
                    default -> Required.value(Map.of("status","PREVIEW","run_id",f.id+"-preview"));
                };
            },Required.value(java.time.Clock.fixed(Required.value(f.now.get()),ZoneId.of("UTC"))));
            var days=Required.value(List.of(f.day,Required.value(f.day.plusDays(1)),Required.value(f.day.plusDays(2)),Required.value(f.day.plusDays(3))));
            overnight.run(Required.value(List.of(f.id)),days);
            assertEquals(4,calls.get());
            assertEquals(OvernightOptimization.State.FAILED,overnight.attempts(f.id,f.day).getFirst().state());
            assertEquals(OvernightOptimization.FailureCode.CALCULATION_FAILED,Required.value(overnight.attempts(f.id,f.day).getFirst().failure()).code());
            assertEquals(OvernightOptimization.State.CANCELLED,overnight.attempts(f.id,Required.value(f.day.plusDays(1))).getFirst().state());
            assertEquals(OvernightOptimization.State.SKIPPED,overnight.attempts(f.id,Required.value(f.day.plusDays(2))).getFirst().state());
            assertEquals(OvernightOptimization.State.SUCCEEDED,overnight.attempts(f.id,Required.value(f.day.plusDays(3))).getFirst().state());
            assertEquals(before,f.jdbc.queryForList("SELECT * FROM appointment WHERE id=?",f.id+"-appointment"));
            assertTrue(Required.value(overnight.metrics().get("totalAttempted"))>=4);
            f.jdbc.update("UPDATE overnight_optimization_attempt SET \"failureContext\"='{\"schemaVersion\":1,\"code\":null,\"exceptionClass\":\"test\",\"stage\":\"PREVIEW\"}'::jsonb WHERE \"metroId\"=? AND state='FAILED'",f.id);
            assertThrows(RuntimeException.class,() -> overnight.attempts(f.id,f.day));
            f.jdbc.update("DELETE FROM overnight_optimization_attempt WHERE \"metroId\"=?",f.id);
        }
    }
    private static final String HASH = DailyAttempts.fingerprint("fixture request");

    @Test void duplicateClaimsConflictsExpiredOwnersAndTerminalFailuresNeverRerun() throws Exception {
        try (var f = new Fixture(false)) {
            String key = f.id + "-claim";
            var start = new CountDownLatch(1);
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                Callable<DailyAttempts.Claim> claimant = () -> { await(start); return f.claim(key, HASH); };
                var one = executor.submit(claimant); var two = executor.submit(claimant); start.countDown();
                var a = one.get(5, TimeUnit.SECONDS); var b = two.get(5, TimeUnit.SECONDS);
                assertEquals(a.id(), b.id());
                assertEquals(1, (a.rejection() == null ? 1 : 0) + (b.rejection() == null ? 1 : 0));
                DailyAttempts.Claim owner = a.rejection() == null ? a : b;
                assertNotNull(f.claim(key, DailyAttempts.fingerprint("other inputs")).rejection());
                var impostor = new DailyAttempts.Claim(owner.id(), owner.key(), "different-owner", null, null);
                assertThrows(ResponseStatusException.class, () -> f.tx.executeWithoutResult(_ -> f.attempts.complete(impostor, Required.value(Map.of("status", "SKIPPED")))));
                f.jdbc.update("UPDATE daily_calculation_attempt SET \"expiresAt\"=clock_timestamp()-interval '1 second' WHERE id=?", owner.id());
                assertNotNull(f.claim(key, HASH).rejection());
                assertEquals("ABANDONED", f.state(key));
                assertThrows(ResponseStatusException.class, () -> f.tx.executeWithoutResult(_ -> f.attempts.complete(owner, Required.value(Map.of("status", "SKIPPED")))));
                assertEquals(1, dev.waterflex.scheduler.DatabaseFacts.query(f.jdbc, "SELECT count(*) FROM daily_calculation_attempt WHERE \"requestKey\"=?", Integer.class, key));
            }
            var failed = f.claim(f.id + "-failure", HASH);
            f.tx.executeWithoutResult(_ -> f.attempts.failed(failed, "FAILED", "FIXTURE_FAILURE"));
            assertNotNull(f.claim(failed.key(), HASH).rejection()); assertEquals("FAILED", f.state(failed.key()));
            assertThrows(org.springframework.dao.DataIntegrityViolationException.class, () -> f.jdbc.update("UPDATE daily_calculation_attempt SET state='SUCCEEDED',\"failureReason\"=NULL WHERE id=?", failed.id()));
        }
    }

    @Test void completedResponsesAreReusedAcrossServiceInstancesWithoutRoutingOrAdmission() {
        try (var f = new Fixture(false)) {
            String key = f.id + "-reuse";
            var first = f.service.preview(new OptimizationService.Request(f.id, Required.value(f.day.toString()), key));
            assertEquals("SUCCEEDED", f.state(key));
            assertNotNull(first.get("run_id"));
            reset(f.roads);
            f.now.set(Required.value(f.day.atTime(6, 0).atZone(ZoneId.of("America/Chicago")).toInstant()));
            var second = f.newService().preview(new OptimizationService.Request(f.id, Required.value(f.day.toString()), key));
            assertEquals(first.get("run_id"), second.get("run_id")); verifyNoInteractions(f.roads);
            assertEquals(0, f.admission.state().active());
            assertThrows(ResponseStatusException.class, () -> f.service.preview(new OptimizationService.Request(f.id, Required.value(f.day.plusDays(1).toString()), key)));
            assertEquals(1, dev.waterflex.scheduler.DatabaseFacts.query(f.jdbc, "SELECT count(*) FROM optimization_run WHERE \"metroId\"=?", Integer.class, f.id));
            f.jdbc.update("UPDATE daily_calculation_attempt SET \"resultJson\"=jsonb_set(\"resultJson\",'{route_summary_after}','null'::jsonb) WHERE \"requestKey\"=?", key);
            assertThrows(ResponseStatusException.class, () -> f.service.preview(new OptimizationService.Request(f.id, Required.value(f.day.toString()), key)));
            verifyNoInteractions(f.roads);
        }
    }

    @Test void repairWithoutShiftHasADurableReusableResultAndExplicitNewKeys() {
        try (var f = new Fixture(false)) {
            f.addTechnician(f.id + "-no-shift", false);
            String key = f.id + "-repair";
            var first = f.service.previewRepair(f.id, f.day, f.id + "-no-shift", 480, 600, key);
            assertEquals("NO_SHIFT", first.get("status")); assertEquals("SUCCEEDED", f.state(key));
            reset(f.roads);
            assertEquals(first, f.newService().previewRepair(f.id, f.day, f.id + "-no-shift", 480, 600, key));
            verifyNoInteractions(f.roads);
            assertThrows(ResponseStatusException.class, () -> f.service.previewRepair(f.id, f.day, f.id + "-no-shift", 481, 600, key));
        }
    }

    @Test void freshRepairCapturesItsOwnVersionInitializationAndCanApplyIndependently() {
        try (var f = new Fixture(true)) {
            // This can be the first real repair solve in a cold JVM. Initialization consumes the cap too.
            f.fixtureSearchMillis.set(1000);
            assertEquals(0, dev.waterflex.scheduler.DatabaseFacts.query(f.jdbc, "SELECT count(*) FROM schedule_day WHERE \"technicianId\" LIKE ?", Integer.class, f.id + "%"));
            String key = f.id + "-fresh-repair";
            var result = f.service.previewRepair(f.id, f.day, f.id + "-far", 480, 1020, key);
            assertEquals("REPAIR_PREVIEW", result.get("status"), result.toString()); assertEquals("SUCCEEDED", f.state(key)); assertTrue(f.solves.get() > 0);
            assertEquals(2, dev.waterflex.scheduler.DatabaseFacts.query(f.jdbc, "SELECT count(*) FROM schedule_day WHERE \"technicianId\" LIKE ?", Integer.class, f.id + "%"));
            String run = Required.value((String) result.get("run_id"));
            var applied = Required.value(f.tx.execute(_ -> f.service.applyRepair(run, f.id + "-far", f.day, 480, 1020)));
            assertEquals("APPLIED", applied.get("status"));
            assertEquals(f.id + "-near", dev.waterflex.scheduler.DatabaseFacts.query(f.jdbc, "SELECT \"technicianId\" FROM appointment WHERE id=?", String.class, f.id + "-appointment"));
        }
    }

    @Test void routingRunsOutsideSnapshotTransactionAndChangedInputsPreventCalculation() {
        try (var f = new Fixture(true)) {
            f.matrixHook.set(() -> f.jdbc.update("UPDATE appointment SET sequence=sequence+1 WHERE id=?", f.id + "-appointment"));
            String key = f.id + "-routing-stale";
            assertThrows(ResponseStatusException.class, () -> f.service.preview(new OptimizationService.Request(f.id, Required.value(f.day.toString()), key)));
            assertEquals("STALE", f.state(key)); assertEquals(0, f.solves.get()); assertEquals(0, f.runCount());
            assertThrows(ResponseStatusException.class, () -> f.newService().preview(new OptimizationService.Request(f.id, Required.value(f.day.toString()), key)));
            assertEquals(0, f.solves.get());
        }
    }

    @Test void simultaneousBookingAndPolicyHoldEndpointAndRoutingChangesRejectCalculatedResults() {
        for (String change : new String[] {"booking", "policy", "hold", "endpoint", "routing"}) {
            try (var f = new Fixture(true)) {
                f.solveHook.set(() -> {
                    switch (change) {
                        case "booking" -> f.jdbc.update("UPDATE appointment SET \"plannedStart\"=\"plannedStart\"+interval '1 minute' WHERE id=?", f.id + "-appointment");
                        case "policy" -> f.jdbc.update("UPDATE technician SET \"maxDailyMinutes\"=\"maxDailyMinutes\"+1 WHERE id=?", f.id + "-far");
                        case "hold" -> f.jdbc.update("INSERT INTO slot_hold (id,\"offerToken\",\"jobId\",\"technicianId\",\"serviceDate\",\"windowStart\",\"windowEnd\",\"plannedStart\",\"plannedEnd\",\"insertPosition\",\"expiresAt\") SELECT ?,?,a.\"jobId\",a.\"technicianId\",a.\"serviceDate\",a.\"windowStart\",a.\"windowEnd\",a.\"plannedStart\",a.\"plannedEnd\",0,clock_timestamp()+interval '1 hour' FROM appointment a WHERE a.id=?", f.id + "-obligation", f.id, f.id + "-appointment");
                        case "endpoint" -> f.jdbc.update("UPDATE technician SET \"homeLat\"=\"homeLat\"+0.01 WHERE id=?", f.id + "-far");
                        case "routing" -> f.routing.set("fixture-routing-v2");
                        default -> throw new AssertionError(change);
                    }
                });
                String key = f.id + "-" + change;
                assertThrows(ResponseStatusException.class, () -> f.service.preview(new OptimizationService.Request(f.id, Required.value(f.day.toString()), key)), change);
                assertTrue(f.solves.get() > 0, change); assertEquals("STALE", f.state(key), change); assertEquals(0, f.runCount(), change);
            }
        }
    }

    @Test void duplicateActiveRequestCannotTakeOwnershipAndCrashLeavesInspectableFailure() throws Exception {
        try (var f = new Fixture(true); var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
            f.solveHook.set(() -> { entered.countDown(); await(release); throw new IllegalStateException("worker fixture crash"); });
            String key = f.id + "-concurrent";
            var first = executor.submit(() -> f.service.preview(new OptimizationService.Request(f.id, Required.value(f.day.toString()), key)));
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                assertNotNull(dev.waterflex.scheduler.DatabaseFacts.query(f.jdbc, "SELECT \"snapshotRevision\" FROM daily_calculation_attempt WHERE \"requestKey\"=?", String.class, key));
                assertThrows(ResponseStatusException.class, () -> f.newService().preview(new OptimizationService.Request(f.id, Required.value(f.day.toString()), key)));
                assertEquals(1, f.admission.state().active());
            } finally { release.countDown(); }
            assertThrows(ExecutionException.class, () -> first.get(5, TimeUnit.SECONDS));
            assertEquals("FAILED", f.state(key)); assertEquals(1, f.solves.get()); assertEquals(0, f.runCount());
            assertThrows(ResponseStatusException.class, () -> f.service.preview(new OptimizationService.Request(f.id, Required.value(f.day.toString()), key)));
            assertEquals(1, f.solves.get());
        }
    }

    @Test void cutoffCrossingBeforePersistenceRetainsStaleAttempt() {
        try (var f = new Fixture(true)) {
            Instant cutoff = Required.value(f.day.atTime(6, 0).atZone(ZoneId.of("America/Chicago")).toInstant());
            f.now.set(Required.value(cutoff.minusSeconds(1)));
            f.solveHook.set(() -> f.now.set(cutoff));
            String key = f.id + "-cutoff";
            assertThrows(ResponseStatusException.class, () -> f.service.preview(new OptimizationService.Request(f.id, Required.value(f.day.toString()), key)));
            assertEquals("STALE", f.state(key)); assertEquals(0, f.runCount());
        }
    }

    @Test void concurrentApplyRevalidatesAndOnlyOneCanCommit() throws Exception {
        try (var f = new Fixture(true); var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            f.fixtureSearchMillis.set(1000); // the apply tests need a found improvement, not a timing race
            var preview = f.service.preview(new OptimizationService.Request(f.id, Required.value(f.day.toString()), f.id + "-apply"));
            assertEquals("PREVIEW", preview.get("status"), () -> "preview skipped: " + preview.get("reason"));
            String run = Required.value((String) preview.get("run_id"));
            var start = new CountDownLatch(1);
            Callable<String> apply = () -> {
                await(start);
                try { return Required.value(f.tx.execute(_ -> (String) f.newService().apply(run).get("status"))); }
                catch (ResponseStatusException conflict) { assertEquals(409, conflict.getStatusCode().value()); return "CONFLICT"; }
            };
            var one = executor.submit(apply); var two = executor.submit(apply); start.countDown();
            assertEquals(Set.of("APPLIED", "CONFLICT"), Set.of(one.get(10, TimeUnit.SECONDS), two.get(10, TimeUnit.SECONDS)));
            assertEquals("APPLIED", dev.waterflex.scheduler.DatabaseFacts.query(f.jdbc, "SELECT status FROM optimization_run WHERE id=?", String.class, run));
        }
    }

    @Test void applyRoutesBeforeLockingScheduleDays() throws Exception {
        try (var f = new Fixture(true); var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            f.fixtureSearchMillis.set(1000); // the apply tests need a found improvement, not a timing race
            var preview = f.service.preview(new OptimizationService.Request(f.id, Required.value(f.day.toString()), f.id + "-route-first"));
            assertEquals("PREVIEW", preview.get("status"), () -> "preview skipped: " + preview.get("reason"));
            String run = Required.value((String) preview.get("run_id"));
            // Another connection must be able to lock every schedule day while apply is waiting on routing.
            f.matrixHook.set(() -> {
                try { executor.submit(() -> f.tx.execute(_ -> { f.jdbc.queryForList("SELECT version FROM schedule_day WHERE \"technicianId\" LIKE ? FOR UPDATE NOWAIT", f.id + "%"); return Boolean.TRUE; })).get(5, TimeUnit.SECONDS); }
                catch (Exception failure) { throw new AssertionError("Schedule days were locked during routing", failure); }
            });
            assertEquals("APPLIED", f.tx.execute(_ -> f.newService().apply(run).get("status")));
            assertNull(f.matrixHook.get());
        }
    }

    @Test void inputChangeBetweenCaptureAndDayLocksRejectsApply() throws Exception {
        try (var f = new Fixture(true); var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            f.fixtureSearchMillis.set(1000); // the apply tests need a found improvement, not a timing race
            var preview = f.service.preview(new OptimizationService.Request(f.id, Required.value(f.day.toString()), f.id + "-changed-input"));
            assertEquals("PREVIEW", preview.get("status"), () -> "preview skipped: " + preview.get("reason"));
            String run = Required.value((String) preview.get("run_id"));
            f.matrixHook.set(() -> {
                try { executor.submit(() -> f.jdbc.update("UPDATE appointment SET sequence=sequence+1 WHERE id=?", f.id + "-appointment")).get(5, TimeUnit.SECONDS); }
                catch (Exception failure) { throw new AssertionError(failure); }
            });
            var conflict = assertThrows(ResponseStatusException.class, () -> f.tx.execute(_ -> f.newService().apply(run)));
            assertEquals(409, conflict.getStatusCode().value());
            assertEquals("PREVIEW", dev.waterflex.scheduler.DatabaseFacts.query(f.jdbc, "SELECT status FROM optimization_run WHERE id=?", String.class, run));
        }
    }

    @Test void finalFenceBlocksAWriterUntilProposalAndAttemptCommitTogether() throws Exception {
        try (var f = new Fixture(true); var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
            f.persistHook.set(() -> { entered.countDown(); await(release); });
            String key = f.id + "-fence";
            var preview = executor.submit(() -> f.service.preview(new OptimizationService.Request(f.id, Required.value(f.day.toString()), key)));
            Future<Integer> writer;
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                writer = executor.submit(() -> f.jdbc.update("UPDATE schedule_day SET version=version+1 WHERE \"technicianId\"=?", f.id + "-far"));
                long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                int waiting;
                do {
                    waiting = dev.waterflex.scheduler.DatabaseFacts.query(f.jdbc, "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type='Lock' AND query LIKE 'UPDATE schedule_day SET version=version+1%'", Integer.class);
                    if (waiting == 0) Thread.sleep(10);
                } while (waiting == 0 && System.nanoTime() < until);
                assertEquals(1, waiting); assertFalse(writer.isDone());
                assertEquals("CLAIMED", f.state(key)); assertEquals(0, f.runCount());
            } finally { release.countDown(); }
            var result = Required.value(preview.get(8, TimeUnit.SECONDS)); assertEquals("SUCCEEDED", f.state(key));
            assertEquals(1, writer.get(8, TimeUnit.SECONDS));
            String run = Required.value((String) result.get("run_id"));
            assertThrows(ResponseStatusException.class, () -> f.tx.executeWithoutResult(_ -> f.service.apply(run)));
        }
    }

    private static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(8, TimeUnit.SECONDS)); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
    }

    private static final class Fixture implements AutoCloseable {
        final String id = "daily-attempt-it-" + UUID.randomUUID();
        final LocalDate day = Required.value(LocalDate.now().plusDays(30));
        final JdbcTemplate jdbc;
        final DataSourceTransactionManager manager;
        final TransactionTemplate tx;
        final DailyAttempts attempts;
        final RoadClient roads;
        final DailySolver solver;
        final SearchAdmission admission = new SearchAdmission(2, 16);
        final AtomicReference<String> routing = new AtomicReference<>("fixture-routing-v1");
        final AtomicReference<@Nullable Runnable> matrixHook = new AtomicReference<>(), solveHook = new AtomicReference<>();
        final AtomicReference<@Nullable Runnable> persistHook = new AtomicReference<>();
        final AtomicInteger solves = new AtomicInteger();
        final AtomicInteger fixtureSearchMillis = new AtomicInteger(100);
        final AtomicReference<Instant> now = new AtomicReference<>(Required.value(Instant.now()));
        final Clock clock = new Clock() {
            @Override public ZoneId getZone() { return Required.value(ZoneOffset.UTC); }
            @Override public Clock withZone(@Nullable ZoneId zone) { return this; }
            @Override public Instant instant() { return Required.value(now.get()); }
        };
        final OptimizationService service;
        Fixture(boolean appointment) {
            String url = Required.value(System.getenv("JDBC_DATABASE_URL"), "isolated integration database URL");
            assertEquals("/waterflex_test", URI.create(url.substring(5)).getPath());
            var source = new DriverManagerDataSource(url, "waterflex", "waterflex");
            jdbc = new JdbcTemplate(source) {
                @Override public int update(String sql, @Nullable Object @Nullable ... args) {
                    if (sql.startsWith("INSERT INTO optimization_run")) {
                        Runnable hook = persistHook.getAndSet(null); if (hook != null) hook.run();
                    }
                    return super.update(sql, args);
                }
            };
            manager = new DataSourceTransactionManager(source); tx = new TransactionTemplate(manager);
            attempts = new DailyAttempts(jdbc, OptimizationService.jsonMapper());
            jdbc.update("INSERT INTO metro (id,name,timezone) VALUES (?,?,'America/Chicago')", id, id);
            roads = mock(RoadClient.class, invocation -> {
                if (invocation.getMethod().getName().equals("activeIdentity")) { assertFalse(TransactionSynchronizationManager.isActualTransactionActive()); return routing.get(); }
                if (invocation.getMethod().getName().equals("currentVersion")) return routing.get();
                if (!invocation.getMethod().getName().equals("matrix")) return org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation);
                // apply routes inside its transaction but before locking schedule days.
                if (DailyOperation.current() != null) assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
                Runnable hook = matrixHook.getAndSet(null); if (hook != null) hook.run();
                Map<String, RoadPoint> points = Required.value(invocation.getArgument(0));
                Map<String, RoadClient.Leg> matrix = new HashMap<>();
                for (String from : points.keySet()) for (String to : points.keySet()) if (!from.equals(to)) {
                    boolean far = from.contains("-far") || to.contains("-far");
                    matrix.put(from + ">" + to, new RoadClient.Leg(far ? 600 : 60, far ? 10000 : 100));
                }
                return matrix;
            });
            DailySolver actual = new DailySolver("TABU", 17);
            solver = mock(DailySolver.class, invocation -> {
                if (invocation.getMethod().getName().equals("diagnostics")) return actual.diagnostics(Required.value(invocation.getArgument(0)));
                if (!invocation.getMethod().getName().equals("solve")) return org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation);
                assertFalse(TransactionSynchronizationManager.isActualTransactionActive()); solves.incrementAndGet();
                Runnable hook = solveHook.getAndSet(null); if (hook != null) hook.run();
                return actual.solve(Required.value(invocation.getArgument(0)), Required.value(Duration.ofMillis(fixtureSearchMillis.get())));
            });
            if (appointment) {
                jdbc.update("INSERT INTO dealership (id,name,\"updatedAt\") VALUES (?,?,CURRENT_TIMESTAMP)", id, id);
                jdbc.update("INSERT INTO depot (id,\"metroId\",\"dealershipId\",name,lat,lng) VALUES (?,?,?,?,43.7,7.4)", id, id, id, id);
                jdbc.update("INSERT INTO depot_endpoint_policy (\"depotId\",\"effectiveDate\",departure,\"returnTo\") VALUES (?,'1900-01-01','HOME','HOME')", id);
                jdbc.update("INSERT INTO service_catalog (id,code,name,\"estDurationMin\",\"updatedAt\") VALUES (?,?,?,30,CURRENT_TIMESTAMP)", id, id, id);
                addTechnician(id + "-far", true); addTechnician(id + "-near", true);
                jdbc.update("INSERT INTO customer (id,\"firstName\",\"lastName\",email,phone) VALUES (?,'Daily','Fixture','daily@example.invalid','0000000000')", id);
                jdbc.update("INSERT INTO address (id,\"customerId\",line1,city,state,\"postalCode\",lat,lng) VALUES (?,?,'Fixture','Monaco','MC','98000',43.7,7.4)", id, id);
                jdbc.update("INSERT INTO job (id,\"customerId\",\"addressId\",\"serviceId\",\"durationMin\",status,\"updatedAt\") VALUES (?,?,?,?,30,'SCHEDULED',CURRENT_TIMESTAMP)", id, id, id, id);
                jdbc.update("INSERT INTO appointment (id,\"jobId\",\"technicianId\",\"serviceDate\",\"windowStart\",\"windowEnd\",\"plannedStart\",\"plannedEnd\",sequence,\"updatedAt\") VALUES (?,?,?,?,?,?,?,?,0,CURRENT_TIMESTAMP)",
                        id + "-appointment", id, id + "-far", Timestamp.valueOf(day.atStartOfDay()), Timestamp.from(local(10)), Timestamp.from(local(14)), Timestamp.from(local(10)), Timestamp.from(local(10).plusSeconds(1800)));
            }
            service = newService();
        }
        private Instant local(int hour) { return Required.value(day.atTime(hour, 0).atZone(ZoneId.of("America/Chicago")).toInstant()); }
        void addTechnician(String tech, boolean available) {
            jdbc.update("INSERT INTO technician (id,name,color,\"homeLat\",\"homeLng\",\"shiftStartMin\",\"shiftEndMin\",\"maxDailyMinutes\",\"maxOvertimeMinutes\",\"updatedAt\") VALUES (?,?,'#000000',43.7,7.4,480,1020,600,0,CURRENT_TIMESTAMP)", tech, tech);
            jdbc.update("INSERT INTO technician_availability_version (id,\"technicianId\",\"effectiveDate\") VALUES (?,?,'1900-01-01')", tech, tech);
            for (int d = 0; d < 7; d++) jdbc.update("INSERT INTO technician_availability_day (\"versionId\",\"dayOfWeek\",available,\"shiftStartMin\",\"shiftEndMin\") VALUES (?,?,?,?,?)", tech, d, available, available ? 480 : null, available ? 1020 : null);
            if (available) {
                jdbc.update("INSERT INTO technician_depot_assignment (\"technicianId\",\"effectiveDate\",\"depotId\") VALUES (?,'1900-01-01',?)", tech, id);
                jdbc.update("INSERT INTO technician_qualification (\"technicianId\",\"serviceId\") VALUES (?,?)", tech, id);
            }
        }
        OptimizationService newService() { return new OptimizationService(jdbc, roads, solver, admission, manager, clock); }
        DailyAttempts.Claim claim(String key, String fingerprint) {
            return new SearchDeadline(Required.value(Duration.ofSeconds(20))).within(() -> Required.value(tx.execute(_ -> attempts.claim(key, fingerprint, _ -> null))));
        }
        String state(String key) { return dev.waterflex.scheduler.DatabaseFacts.query(jdbc, "SELECT state FROM daily_calculation_attempt WHERE \"requestKey\"=?", String.class, key); }
        int runCount() { return dev.waterflex.scheduler.DatabaseFacts.query(jdbc, "SELECT count(*) FROM optimization_run WHERE \"metroId\"=?", Integer.class, id); }
        @Override public void close() {
            tx.executeWithoutResult(_ -> {
                jdbc.update("DELETE FROM daily_calculation_attempt WHERE \"requestKey\" IN (SELECT \"previewKey\" FROM overnight_optimization_attempt WHERE \"metroId\"=?)",id);
                jdbc.update("DELETE FROM overnight_optimization_attempt WHERE \"metroId\"=?",id);
                jdbc.update("DELETE FROM daily_calculation_attempt WHERE \"requestKey\" LIKE ?", id + "%");
                jdbc.update("DELETE FROM optimization_change WHERE \"runId\" IN (SELECT id FROM optimization_run WHERE \"metroId\"=?)", id);
                jdbc.update("DELETE FROM optimization_run WHERE \"metroId\"=?", id);
                jdbc.update("DELETE FROM slot_hold WHERE id=?", id + "-obligation");
                jdbc.update("DELETE FROM appointment WHERE id=?", id + "-appointment");
                jdbc.update("DELETE FROM job WHERE id=?", id); jdbc.update("DELETE FROM address WHERE id=?", id); jdbc.update("DELETE FROM customer WHERE id=?", id);
                jdbc.update("DELETE FROM schedule_day WHERE \"technicianId\" LIKE ?", id + "%");
                jdbc.update("DELETE FROM technician_qualification WHERE \"technicianId\" LIKE ?", id + "%");
                jdbc.update("DELETE FROM technician WHERE id LIKE ?", id + "%");
                jdbc.update("DELETE FROM service_catalog WHERE id=?", id); jdbc.update("DELETE FROM depot_endpoint_policy WHERE \"depotId\"=?", id);
                jdbc.update("DELETE FROM depot WHERE id=?", id); jdbc.update("DELETE FROM dealership WHERE id=?", id); jdbc.update("DELETE FROM metro WHERE id=?", id);
            });
        }
    }
}
