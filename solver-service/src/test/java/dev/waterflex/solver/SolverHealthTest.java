package dev.waterflex.solver;
import dev.waterflex.scheduler.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class SolverHealthTest {
    private static MockHttpServletRequest http(String json,String requestId) {
        var request = new MockHttpServletRequest(); request.addHeader("Authorization","Bearer "+SolverReplayTest.TOKEN);
        request.addHeader(HttpCalculation.REQUEST_HEADER,requestId);
        request.setContent(json.getBytes(java.nio.charset.StandardCharsets.UTF_8)); return request;
    }
    @Test void healthReportsLoadAndGoesDownWhenASolveOutlivesEveryAllowanceUntilShutdownStopsIt() throws Exception {
        var admission = new SearchAdmission(2,4); var calculator = mock(EmbeddedCalculation.class);
        var now = new AtomicLong(1_000_000_000L);
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var request = ReplayFixture.bookingRequest();
        when(calculator.calculate(request)).thenAnswer(_ -> {
            entered.countDown(); assertTrue(release.await(5,TimeUnit.SECONDS));
            return CalculationProtocol.Response.of(request,1,BookingCalculation.run(ReplayFixture.booking()));
        });
        var controller = new SolveController(SolverReplayTest.TOKEN,admission,calculator,4,now::get);
        var idle = controller.health();
        assertEquals(200,idle.getStatusCode().value());
        var body = Required.value(idle.getBody());
        assertEquals("UP",body.get("status")); assertEquals(2,body.get("capacity")); assertEquals(0,body.get("active"));
        assertEquals(0,body.get("transient")); assertEquals(0L,body.get("stuck"));
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var worker = executor.submit(() -> { controller.solve("booking",http(CalculationJson.write(request),request.requestId()),new MockHttpServletResponse()); return true; });
            try {
                assertTrue(entered.await(3,TimeUnit.SECONDS));
                var busy = Required.value(controller.health().getBody());
                assertEquals(200,controller.health().getStatusCode().value());
                assertEquals(1,busy.get("active")); assertEquals(1,busy.get("transient"));
                now.addAndGet(SolveController.STUCK.toNanos() + 1);
                var wedged = controller.health();
                assertEquals(503,wedged.getStatusCode().value(),"a solve older than any allowance takes the replica out");
                assertEquals("DOWN",Required.value(wedged.getBody()).get("status")); assertEquals(1L,Required.value(wedged.getBody()).get("stuck"));
                controller.stopRunning();
            } finally { release.countDown(); }
            var failure = assertThrows(ExecutionException.class,() -> worker.get(3,TimeUnit.SECONDS));
            assertInstanceOf(SearchDeadline.Expired.class,failure.getCause(),"shutdown cancels the running solve");
        }
        assertEquals(200,controller.health().getStatusCode().value());
    }
}
