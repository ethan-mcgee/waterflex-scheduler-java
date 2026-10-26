package dev.waterflex.scheduler;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

/**
 * One summary line per public API call, with the operation, status and total time, how that time was spent when the
 * call ran under a deadline (slot wait, routing wait, calculation engine, database, the commit phase inside the reserve, with the routing pairs and cache hits), and the request's log context:
 * {@code tenant} (set once the token is accepted), {@code requestId} (set once the body is read) and {@code operation}.
 * Every line logged while serving the call carries them, including the daily operation's receipt on its own thread.
 * Runs before tenant authentication, so a refused token is logged too, and clears the context afterwards.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public final class RequestLogging extends OncePerRequestFilter {
    private static final Logger LOG = Required.value(LoggerFactory.getLogger("dev.waterflex.scheduler.api.requests"));
    public static final String TENANT = "tenant", REQUEST_ID = "requestId", OPERATION = "operation";

    /** The measurements of the deadline that finished on this thread, read once by the call's summary line. */
    private static final ThreadLocal<SearchTelemetry.@org.jspecify.annotations.Nullable Measurements> FINISHED = new ThreadLocal<>();

    /**
     * Where each call's phase times are also recorded, as {@code scheduler.request.phase} timers tagged with the
     * operation (the matched route, so IDs in the path never become tags) and the phase.
     */
    private final io.micrometer.core.instrument.MeterRegistry registry;

    /** A filter whose phase timers go nowhere, for tests that read only the log line. */
    public RequestLogging() { this(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()); }

    @org.springframework.beans.factory.annotation.Autowired
    public RequestLogging(io.micrometer.core.instrument.MeterRegistry registry) {
        this.registry = registry;
        SearchDeadline.observe(FINISHED::set);
    }

    /** Records a finished deadline's phases; nothing for a call that ran under none. */
    void recordPhases(String operation, SearchTelemetry.@org.jspecify.annotations.Nullable Measurements measured) {
        if (measured == null) return;
        phase(operation, "queue", measured.admissionNanos());
        phase(operation, "routing", measured.routingNanos());
        phase(operation, "calculation", measured.calculationNanos());
        phase(operation, "db", measured.databaseNanos());
        Long commit = measured.commitNanos();
        if (commit != null) phase(operation, "commit", commit);
    }

    private void phase(String operation, String phase, long nanos) {
        io.micrometer.core.instrument.Timer.builder("scheduler.request.phase")
                .description("Time a public API call spent in one phase of its deadline")
                .tag("operation", operation).tag("phase", phase)
                .publishPercentileHistogram()
                .register(registry)
                .record(Math.max(0, nanos), java.util.concurrent.TimeUnit.NANOSECONDS);
    }

    /** Adds the host's request ID to the current call's log context. */
    public static void requestId(String requestId) { MDC.put(REQUEST_ID, requestId); }

    /** The summary line's breakdown of a finished deadline, or an empty string without one. */
    static String breakdown(SearchTelemetry.@org.jspecify.annotations.Nullable Measurements measured) {
        if (measured == null) return "";
        Long cpu = measured.foregroundCpuNanos();
        Long commit = measured.commitNanos();
        return " queueMs=" + measured.admissionNanos() / 1_000_000 + " routingMs=" + measured.routingNanos() / 1_000_000 + " calculationMs=" + measured.calculationNanos() / 1_000_000
                + " dbMs=" + measured.databaseNanos() / 1_000_000 + " dbCalls=" + measured.databaseExecutions()
                + " routingPairs=" + measured.routingPairs() + " cacheHits=" + (measured.memoryHits() + measured.persistentHits())
                + " fetched=" + measured.sharedRoutingPairs()
                + (commit == null ? "" : " commitMs=" + commit / 1_000_000)
                + (cpu == null ? "" : " cpuMs=" + cpu / 1_000_000);
    }

    /**
     * The status the client receives. An exception that leaves the chain before the response is committed is answered
     * 500 by the container after this filter returns, while the response still reads its default 200.
     */
    static int status(boolean threw, HttpServletResponse response) {
        return threw && !response.isCommitted() ? HttpServletResponse.SC_INTERNAL_SERVER_ERROR : response.getStatus();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(TenantAuthentication.PUBLIC_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        long started = System.nanoTime();
        MDC.put(OPERATION, request.getMethod() + " " + request.getRequestURI());
        FINISHED.remove();
        boolean threw = true;
        try {
            chain.doFilter(request, response);
            threw = false;
        } finally {
            // The matched route names the operation without the IDs in its path.
            Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
            String operation = request.getMethod() + " " + (pattern instanceof String route ? route : request.getRequestURI());
            MDC.put(OPERATION, operation);
            SearchTelemetry.Measurements measured = FINISHED.get();
            FINISHED.remove();
            LOG.info("api operation=\"{}\" status={} elapsedMs={}{}", operation, status(threw, response), (System.nanoTime() - started) / 1_000_000, breakdown(measured));
            recordPhases(operation, measured);
            MDC.remove(TENANT); MDC.remove(REQUEST_ID); MDC.remove(OPERATION);
        }
    }
}
