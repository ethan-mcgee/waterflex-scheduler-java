package dev.waterflex.scheduler;

import dev.waterflex.scheduler.api.PublicResponses.ErrorCode;
import dev.waterflex.scheduler.api.PublicResponses.Problem;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.util.DisconnectedClientHelper;

/**
 * Every /api/v1 refusal is a Problem body (spec 1.11.0), including the ones no handler writes: an unknown path, a wrong
 * method, and a failure that escapes a handler. Handlers release their requestId's claim before such a failure leaves
 * them, so a 500 or 503 written here stored nothing. Paths outside /api/v1 ({@code /health}, the management port) keep
 * Spring Boot's own answers: a handler here rethrows, and the next resolver answers as before.
 */
@RestControllerAdvice
public final class PublicApiErrors {
    /** A lost database is retried like a token store that did not answer (see {@link TenantAuthentication}). */
    static final int DATABASE_RETRY_AFTER_SECONDS = TenantAuthentication.RETRY_AFTER_SECONDS;
    private static final Logger LOG = Required.value(LoggerFactory.getLogger(PublicApiErrors.class));

    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    public void notFound(Exception missing, HttpServletRequest request, HttpServletResponse response) throws Exception {
        if (!isPublic(request)) throw missing;
        write(response, 404, new Problem(ErrorCode.NOT_FOUND, "No operation at " + request.getMethod() + " " + request.getRequestURI()), null);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public void wrongMethod(HttpRequestMethodNotSupportedException refused, HttpServletRequest request, HttpServletResponse response) throws Exception {
        if (!isPublic(request)) throw refused;
        Set<String> allowed = new TreeSet<>();
        @Nullable Set<HttpMethod> supported = refused.getSupportedHttpMethods();
        if (supported != null) for (HttpMethod method : supported) allowed.add(method.name());
        if (!allowed.isEmpty()) response.setHeader("Allow", String.join(", ", allowed));
        String use = allowed.isEmpty() ? "" : "; use " + String.join(" or ", allowed);
        write(response, 405, new Problem(ErrorCode.INVALID_REQUEST, request.getMethod() + " is not allowed on " + request.getRequestURI() + use), null);
    }

    @ExceptionHandler(RoadClient.RoadUnavailable.class)
    public void roadUnavailable(RoadClient.RoadUnavailable unavailable, HttpServletRequest request, HttpServletResponse response) throws Exception {
        if (!isPublic(request)) throw unavailable;
        LOG.warn("Road routing unavailable outside a handler's own refusal", unavailable);
        write(response, 503, new Problem(ErrorCode.ROUTING_UNAVAILABLE, "Road routing unavailable"), null);
    }

    @ExceptionHandler({DataAccessResourceFailureException.class, TransientDataAccessException.class, RecoverableDataAccessException.class})
    public void databaseUnavailable(Exception unreachable, HttpServletRequest request, HttpServletResponse response) throws Exception {
        if (!isPublic(request)) throw unreachable;
        LOG.warn("Database unavailable during {} {}: {}", request.getMethod(), request.getRequestURI(), unreachable.toString());
        write(response, 503, new Problem(ErrorCode.SERVICE_UNAVAILABLE,
                "The scheduler could not reach its database; send the same call again after the Retry-After seconds"), DATABASE_RETRY_AFTER_SECONDS);
    }

    @ExceptionHandler(Exception.class)
    public void failed(Exception failure, HttpServletRequest request, HttpServletResponse response) throws Exception {
        if (!isPublic(request) || DisconnectedClientHelper.isClientDisconnectedException(failure)) throw failure;
        LOG.error("Public API call {} {} failed", request.getMethod(), request.getRequestURI(), failure);
        write(response, 500, new Problem(ErrorCode.INTERNAL_ERROR,
                "The scheduler failed while handling this call; nothing was stored. Report it with the requestId rather than retrying"), null);
    }

    static boolean isPublic(HttpServletRequest request) {
        return request.getRequestURI().startsWith(TenantAuthentication.PUBLIC_PREFIX);
    }

    /** Writes the Problem's JSON as produced, so no message converter encodes it a second time. */
    static void write(HttpServletResponse response, int status, Problem problem, @Nullable Integer retryAfterSeconds) throws IOException {
        byte[] json = CalculationJson.write(problem).getBytes(StandardCharsets.UTF_8);
        response.setStatus(status);
        if (retryAfterSeconds != null) response.setHeader("Retry-After", retryAfterSeconds.toString());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setContentLength(json.length);
        response.getOutputStream().write(json);
    }
}
