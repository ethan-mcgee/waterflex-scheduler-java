package dev.waterflex.scheduler;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Every public API call (/api/v1/**) must carry a tenant token. The tenant comes only from the token,
 * never from the request, and is exposed to handlers through {@link #tenant(HttpServletRequest)}.
 */
@Component
public final class TenantAuthentication extends OncePerRequestFilter {
    static final String PUBLIC_PREFIX = "/api/v1/";
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
        String tenant = authorization == null || !authorization.startsWith("Bearer ") ? null : tokens.tenantFor(Required.value(authorization.substring(7)));
        if (tenant == null) {
            response.setHeader("WWW-Authenticate", "Bearer");
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Tenant authentication required");
            return;
        }
        request.setAttribute(TENANT, tenant);
        chain.doFilter(request, response);
    }

    /** The authenticated tenant; fails closed if a handler is reached without authentication. */
    public static String tenant(HttpServletRequest request) {
        if (!(request.getAttribute(TENANT) instanceof String tenant)) throw new IllegalStateException("Public API handler reached without tenant authentication");
        return tenant;
    }
}
