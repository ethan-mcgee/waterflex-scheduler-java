package dev.waterflex.scheduler.optimizer;

import dev.waterflex.scheduler.Required;

import dev.waterflex.scheduler.RoadClient;
import org.junit.jupiter.api.Test;
import org.jspecify.annotations.NonNull;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class OptimizationServiceTest {
    @Test void preparedHttpBodyPreservesIsoInstantsAndIntentionalNulls() throws Exception {
        var mapper = OptimizationService.jsonMapper();
        java.util.Map<String, @org.jspecify.annotations.Nullable Object> response = new java.util.LinkedHashMap<>();
        response.put("created_at",Instant.parse("2030-01-01T00:00:00Z")); response.put("applied_at",null);
        response.put("reason","CSR review: café");
        var encoded = Required.value(mapper.readTree(mapper.writeValueAsBytes(response)));
        assertEquals("2030-01-01T00:00:00Z",encoded.path("created_at").textValue());
        assertTrue(encoded.path("applied_at").isNull());
        assertEquals("CSR review: café",encoded.path("reason").textValue());
    }
    @Test void delayedValidationCannotCommitAfterDailyCallerTimesOut() throws Exception {
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var rolledBack = new java.util.concurrent.CountDownLatch(1);
        var committed = new java.util.concurrent.atomic.AtomicBoolean();
        var receipts = new java.util.concurrent.LinkedBlockingQueue<DailyOperation.Receipt>();
        var manager = new org.springframework.transaction.support.AbstractPlatformTransactionManager() {
            private static final long serialVersionUID = 1L;
            @Override protected Object doGetTransaction() { return new Object(); }
            @Override protected void doBegin(Object transaction, org.springframework.transaction.TransactionDefinition definition) { }
            @Override protected void doCommit(org.springframework.transaction.support.DefaultTransactionStatus status) { committed.set(true); }
            @Override protected void doRollback(org.springframework.transaction.support.DefaultTransactionStatus status) { rolledBack.countDown(); }
        };
        var admission = new dev.waterflex.scheduler.SearchAdmission(1,16);
        var service = new OptimizationService(mock(JdbcTemplate.class),mock(RoadClient.class),mock(DailySolver.class),admission,manager);
        var caller = Thread.ofVirtual().start(() -> assertThrows(dev.waterflex.scheduler.SearchDeadline.Expired.class,
                () -> DailyOperation.execute(admission,Required.value(java.time.Duration.ofSeconds(2)),() -> service.inPreviewTransaction(() -> {
                    entered.countDown();
                    try { assertTrue(release.await(5,java.util.concurrent.TimeUnit.SECONDS)); }
                    catch (InterruptedException failure) { throw new AssertionError(failure); }
                    return "late validation";
                }),receipts::add)));
        try {
            assertTrue(entered.await(5,java.util.concurrent.TimeUnit.SECONDS)); caller.join(5000);
            assertTrue(!caller.isAlive()); assertEquals(1,admission.state().active());
            assertTrue(!committed.get());
        } finally { release.countDown(); }
        assertTrue(rolledBack.await(5,java.util.concurrent.TimeUnit.SECONDS));
        var receipt = Required.value(receipts.poll(5,java.util.concurrent.TimeUnit.SECONDS));
        assertEquals("CANCELLED_OR_EXPIRED",receipt.outcome()); assertTrue(!receipt.lateResult());
        assertTrue(!committed.get()); assertEquals(0,admission.state().active());
    }
    @Test
    void frozenPreviewIsAScheduleConflict() {
        OptimizationService service = new OptimizationService(
                mock(JdbcTemplate.class), mock(RoadClient.class), mock(DailySolver.class),
                new dev.waterflex.scheduler.SearchAdmission(2, 16), new org.springframework.transaction.support.AbstractPlatformTransactionManager() {
                    private static final long serialVersionUID = 1L;
                    @Override protected Object doGetTransaction() { return new Object(); }
                    @Override protected void doBegin(Object transaction, org.springframework.transaction.TransactionDefinition definition) { }
                    @Override protected void doCommit(org.springframework.transaction.support.DefaultTransactionStatus status) { }
                    @Override protected void doRollback(org.springframework.transaction.support.DefaultTransactionStatus status) { }
                });

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.preview(new OptimizationService.Request("metro", "2000-01-01")));

        assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
    }

    @Test
    void overnightRunIncludesTodayAndNextNineEligibleWeekdays() {
        List<@NonNull LocalDate> expected = Required.value(List.of(
                Required.value(LocalDate.parse("2026-09-21")),
                Required.value(LocalDate.parse("2026-09-22")),
                Required.value(LocalDate.parse("2026-09-23")),
                Required.value(LocalDate.parse("2026-09-24")),
                Required.value(LocalDate.parse("2026-09-25")),
                Required.value(LocalDate.parse("2026-09-26")),
                Required.value(LocalDate.parse("2026-09-27")),
                Required.value(LocalDate.parse("2026-09-28")),
                Required.value(LocalDate.parse("2026-09-29")),
                Required.value(LocalDate.parse("2026-09-30")),
                Required.value(LocalDate.parse("2026-10-01")),
                Required.value(LocalDate.parse("2026-10-02"))
        ));
        assertTrue(expected.equals(OptimizationService.overnightDates(
                Required.value(Instant.parse("2026-09-21T07:00:00Z")))));
    }
}
