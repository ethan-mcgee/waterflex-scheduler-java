package dev.waterflex.routing;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Every routing computation requires the shared service token; only /health stays open for readiness and identity checks. */
@Component
public final class RoutingAuthentication extends OncePerRequestFilter {
    private final byte[] token;

    public RoutingAuthentication(@Value("${routing.auth-token}") String token) {
        if (token.length() < 32 || token.chars().anyMatch(Character::isWhitespace))
            throw new IllegalArgumentException("Routing authentication token must contain at least 32 non-whitespace characters");
        this.token = Required.value(token.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/internal/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        String authorization = request.getHeader("Authorization");
        if (authorization == null || !authorization.startsWith("Bearer ")
                || !MessageDigest.isEqual(token, authorization.substring(7).getBytes(StandardCharsets.UTF_8))) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Routing authentication required");
            return;
        }
        chain.doFilter(request, response);
    }
}
