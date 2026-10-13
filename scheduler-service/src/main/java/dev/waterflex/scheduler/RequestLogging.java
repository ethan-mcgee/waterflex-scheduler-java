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
 * One summary line per public API call, with the operation, status and total time, and the request's log context:
 * {@code tenant} (set once the token is accepted), {@code requestId} (set once the body is read) and {@code operation}.
 * Every line logged while serving the call carries them, including the daily operation's receipt on its own thread.
 * Runs before tenant authentication, so a refused token is logged too, and clears the context afterwards.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public final class RequestLogging extends OncePerRequestFilter {
    private static final Logger LOG = Required.value(LoggerFactory.getLogger("dev.waterflex.scheduler.api.requests"));
    public static final String TENANT = "tenant", REQUEST_ID = "requestId", OPERATION = "operation";

    /** Adds the host's request ID to the current call's log context. */
    public static void requestId(String requestId) { MDC.put(REQUEST_ID, requestId); }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(TenantAuthentication.PUBLIC_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        long started = System.nanoTime();
        MDC.put(OPERATION, request.getMethod() + " " + request.getRequestURI());
        try {
            chain.doFilter(request, response);
        } finally {
            // The matched route names the operation without the IDs in its path.
            Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
            String operation = request.getMethod() + " " + (pattern instanceof String route ? route : request.getRequestURI());
            MDC.put(OPERATION, operation);
            LOG.info("api operation=\"{}\" status={} elapsedMs={}", operation, response.getStatus(), (System.nanoTime() - started) / 1_000_000);
            MDC.remove(TENANT); MDC.remove(REQUEST_ID); MDC.remove(OPERATION);
        }
    }
}
