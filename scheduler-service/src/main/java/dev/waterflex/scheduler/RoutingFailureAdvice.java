package dev.waterflex.scheduler;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Operator mutations roll back and expose a retryable routing failure instead of an opaque 500. */
@RestControllerAdvice
public final class RoutingFailureAdvice {
    public record Failure(String classification, boolean retryable, String detail) { }
    @ExceptionHandler(RoadClient.RoadUnavailable.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public Failure unavailable(RoadClient.RoadUnavailable failure) {
        org.slf4j.LoggerFactory.getLogger(RoutingFailureAdvice.class).warn("Operator road routing unavailable", failure);
        return new Failure("ROUTING_UNAVAILABLE", true, "Road routing is temporarily unavailable. Retry the operation.");
    }
}
