package dev.waterflex.scheduler;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Names the contract version on every /api/v1 answer (spec 1.11.0), so a host's bug report says which contract answered.
 * It runs before {@link TenantAuthentication}, so a 401 carries it too.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public final class ApiVersionHeader extends OncePerRequestFilter {
    public static final String HEADER = "X-Scheduler-Version";
    /** The contract this build serves; PublicApiContractTest keeps it equal to docs/api/openapi-v1.yaml's info.version. */
    public static final String SPEC_VERSION = "1.11.0";

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(TenantAuthentication.PUBLIC_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        response.setHeader(HEADER, SPEC_VERSION);
        chain.doFilter(request, response);
    }
}
