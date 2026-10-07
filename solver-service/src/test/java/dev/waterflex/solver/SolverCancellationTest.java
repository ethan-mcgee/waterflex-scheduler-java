package dev.waterflex.solver;
import dev.waterflex.scheduler.*;
import java.time.Duration;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class SolverCancellationTest {
    private static MockHttpServletRequest http(String json) {
        var request = new MockHttpServletRequest(); request.addHeader("Authorization","Bearer "+SolverReplayTest.TOKEN);
        request.setContent(json.getBytes(java.nio.charset.StandardCharsets.UTF_8)); return request;
    }
    @Test void cancellationRetainsCapacityUntilActualBookingWorkerStops() throws Exception {
        var admission = new SearchAdmission(1,0); var calculator = mock(EmbeddedCalculation.class);
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var request = ReplayFixture.bookingRequest();
        when(calculator.calculate(request)).thenAnswer(_ -> {
            entered.countDown(); assertTrue(release.await(5,TimeUnit.SECONDS));
            return CalculationProtocol.Response.of(request,1,BookingCalculation.run(ReplayFixture.booking()));
        });
        var controller = new SolveController(SolverReplayTest.TOKEN,admission,calculator,1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var worker = executor.submit(() -> { controller.solve("booking",http(CalculationJson.write(request)),new MockHttpServletResponse()); return true; });
            try {
                assertTrue(entered.await(3,TimeUnit.SECONDS)); controller.cancel(request.requestId(),http(""));
                var duplicate = assertThrows(org.springframework.web.server.ResponseStatusException.class,
                        () -> controller.solve("booking",http(CalculationJson.write(request)),new MockHttpServletResponse()));
                assertEquals(409,duplicate.getStatusCode().value());
                var full = assertThrows(org.springframework.web.server.ResponseStatusException.class,
                        () -> controller.solve("booking",http(CalculationJson.write(ReplayFixture.bookingRequest())),new MockHttpServletResponse()));
                assertEquals(429,full.getStatusCode().value());
                assertThrows(SearchAdmission.Busy.class,() -> admission.acquire(SearchAdmission.Kind.BOOKING,new SearchDeadline(Required.value(Duration.ofMillis(100)))));
                assertFalse(worker.isDone());
            } finally { release.countDown(); }
            var failure = assertThrows(ExecutionException.class,() -> worker.get(3,TimeUnit.SECONDS)); assertInstanceOf(SearchDeadline.Expired.class,failure.getCause());
            try (var lease = admission.acquire(SearchAdmission.Kind.BOOKING,new SearchDeadline(Required.value(Duration.ofMillis(100))))) { assertNotNull(lease); }
        }
    }
    @Test void queuedDailyCancellationNeverStartsCalculationAndDuplicateIdCannotCompete() throws Exception {
        var admission = new SearchAdmission(1,16); var calculator = mock(EmbeddedCalculation.class);
        var controller = new SolveController(SolverReplayTest.TOKEN,admission,calculator,18);
        var request = ReplayFixture.dailyRequest();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor(); var lease = admission.acquire(SearchAdmission.Kind.BOOKING,new SearchDeadline(Required.value(Duration.ofSeconds(10))))) {
            assertNotNull(lease);
            var worker = executor.submit(() -> { controller.solve("daily",http(CalculationJson.write(request)),new MockHttpServletResponse()); return true; });
            long until = System.nanoTime()+2_000_000_000L;
            boolean cancelled = false;
            while (!cancelled && System.nanoTime() < until) {
                try { controller.cancel(request.requestId(),http("")); cancelled = true; }
                catch (org.springframework.web.server.ResponseStatusException absent) { Thread.onSpinWait(); }
            }
            assertTrue(cancelled); var failure = assertThrows(ExecutionException.class,() -> worker.get(2,TimeUnit.SECONDS));
            assertInstanceOf(SearchDeadline.Expired.class,failure.getCause()); verifyNoInteractions(calculator);
        }
    }
}
