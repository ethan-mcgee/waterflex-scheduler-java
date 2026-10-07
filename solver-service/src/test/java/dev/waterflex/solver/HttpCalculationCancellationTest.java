package dev.waterflex.solver;
import dev.waterflex.scheduler.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class HttpCalculationCancellationTest {
    @Test void callerCancellationSendsDeleteAndNeverStartsAnotherSolve() throws Exception {
        var entered=new CountDownLatch(1); var release=new CountDownLatch(1); var deleted=new CountDownLatch(1); var calls=new AtomicInteger();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        try (var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            server.setExecutor(executor);
            server.createContext("/v1/solve/booking",exchange -> {
                calls.incrementAndGet(); exchange.getRequestBody().readAllBytes(); entered.countDown();
                try { release.await(5,TimeUnit.SECONDS); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                exchange.close();
            });
            server.createContext("/v1/solves/",exchange -> { assertEquals("DELETE",exchange.getRequestMethod()); deleted.countDown(); exchange.sendResponseHeaders(202,-1); exchange.close(); });
            server.start();
            try {
                var adapter=new HttpCalculation("http://127.0.0.1:"+server.getAddress().getPort(),SolverReplayTest.TOKEN);
                var deadline=new SearchDeadline(Required.value(Duration.ofSeconds(5))); var request=ReplayFixture.bookingRequest();
                var result=executor.submit(() -> deadline.within(() -> adapter.calculate(request)));
                assertTrue(entered.await(2,TimeUnit.SECONDS)); deadline.cancel();
                var failure=assertThrows(ExecutionException.class,() -> result.get(2,TimeUnit.SECONDS)); assertInstanceOf(SearchDeadline.Expired.class,failure.getCause());
                assertTrue(deleted.await(2,TimeUnit.SECONDS)); assertEquals(1,calls.get());
            } finally { release.countDown(); server.stop(0); }
        }
    }
}
