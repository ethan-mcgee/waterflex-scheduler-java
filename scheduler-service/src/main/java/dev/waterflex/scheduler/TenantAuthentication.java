package dev.waterflex.scheduler;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import dev.waterflex.scheduler.api.PublicResponses;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Every public API call (/api/v1/**) must carry a tenant token. The tenant comes only from the token,
 * never from the request, and is exposed to handlers through {@link #tenant(HttpServletRequest)}.
 */
@Component
public final class TenantAuthentication extends OncePerRequestFilter {
    static final String PUBLIC_PREFIX = "/api/v1/";
    /** A token store refusal's Retry-After: a pooled connection is waited for 250 ms, so a stall shorter than this has passed. */
    static final int RETRY_AFTER_SECONDS = 1;
    private static final Logger LOG = Required.value(LoggerFactory.getLogger(TenantAuthentication.class));
    private static final String TENANT = TenantAuthentication.class.getName() + ".tenant";
    private final TenantTokens tokens;

    public TenantAuthentication(TenantTokens tokens) { this.tokens = tokens; }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(PUBLIC_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        String authorization = request.getHeader("Authorization");
        String tenant;
        try {
            tenant = authorization == null || !authorization.startsWith("Bearer ") ? null : tokens.tenantFor(Required.value(authorization.substring(7)));
        } catch (DataAccessResourceFailureException | TransientDataAccessException | RecoverableDataAccessException unreachable) {
            // The token store could not answer (no pooled connection in time, a stalled or restarting database). Nothing
            // was decided about the token, so the caller may retry; a query that is wrong is a bug and still fails as a 500.
            LOG.warn("Token store unavailable: {}", unreachable.toString());
            writeUnavailable(response);
            return;
        }
        if (tenant == null) {
            response.setHeader("WWW-Authenticate", "Bearer");
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Tenant authentication required");
            return;
        }
        request.setAttribute(TENANT, tenant);
        org.slf4j.MDC.put(RequestLogging.TENANT, tenant);
        chain.doFilter(request, response);
    }

    /** 503 SERVICE_UNAVAILABLE with the API's problem body, retried after {@link #RETRY_AFTER_SECONDS}. */
    static void writeUnavailable(HttpServletResponse response) throws IOException {
        byte[] json = CalculationJson.write(new PublicResponses.Problem(PublicResponses.ErrorCode.SERVICE_UNAVAILABLE,
                "The scheduler could not check the token; retry after the Retry-After seconds")).getBytes(StandardCharsets.UTF_8);
        response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        response.setHeader("Retry-After", String.valueOf(RETRY_AFTER_SECONDS));
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setContentLength(json.length);
        response.getOutputStream().write(json);
    }

    /** The authenticated tenant; fails closed if a handler is reached without authentication. */
    public static String tenant(HttpServletRequest request) {
        if (!(request.getAttribute(TENANT) instanceof String tenant)) throw new IllegalStateException("Public API handler reached without tenant authentication");
        return tenant;
    }
}
