package dev.waterflex.scheduler;

import org.jspecify.annotations.Nullable;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.List;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class BookingController {
    private final BookingService booking;
    private final SearchAdmission admission;
    public BookingController(BookingService booking, SearchAdmission admission) { this.booking = booking; this.admission = admission; }
    public record JobRequest(String jobId, @Nullable Boolean refresh, @Nullable Long deadlineEpochMs) {
        public JobRequest {
            jobId = RequestChecks.text(jobId, "jobId");
            if (deadlineEpochMs != null && deadlineEpochMs <= 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid search deadline");
        }
        public JobRequest(String jobId, @Nullable Boolean refresh) { this(jobId, refresh, null); }
    }
    public record SelectRequest(String jobId, String offerId) { public SelectRequest { jobId = RequestChecks.text(jobId, "jobId"); offerId = RequestChecks.text(offerId, "offerId"); } }
    public record ConfirmRequest(String holdId) { public ConfirmRequest { holdId = RequestChecks.text(holdId, "holdId"); } }
    public record CancelRequest(String appointment_id, String reason) { public CancelRequest { appointment_id = RequestChecks.text(appointment_id, "appointment_id"); reason = RequestChecks.text(reason, "reason"); } }
    public enum SearchOutcome { AVAILABLE, SEARCH_INCOMPLETE, NO_CANDIDATE_FOUND, ROUTING_UNAVAILABLE, SCHEDULE_CONFLICT, SERVICE_BUSY }
    public record SearchStatus(SearchOutcome outcome, boolean prescribedSearchCompleted, long elapsedMs, boolean retryable, long queueMs) { }
    public record SearchResponse(String jobId, List<BookingService.Offer> offers, SearchStatus search) { }

    @PostMapping("/v1/offers")
    public SearchResponse offers(@RequestBody JobRequest request) {
        long started = System.nanoTime();
        Long requestedDeadline = request.deadlineEpochMs();
        long remainingMs = requestedDeadline == null ? 5000 : Math.min(5000, requestedDeadline - System.currentTimeMillis());
        if (remainingMs <= 0) return searchResult(request.jobId(), Required.value(List.of()), SearchOutcome.SEARCH_INCOMPLETE, started, 0);
        SearchDeadline deadline = new SearchDeadline(Required.value(java.time.Duration.ofMillis(remainingMs)));
        SearchAdmission.Lease admitted;
        try { admitted = admission.acquire(SearchAdmission.Kind.BOOKING, deadline); }
        catch (SearchAdmission.Busy | SearchDeadline.Expired exception) {
            return searchResult(request.jobId(), Required.value(List.of()), SearchOutcome.SERVICE_BUSY, started, deadline.elapsedMillis());
        }
        long queueMs = admitted.queueMillis();
        try (var lease = admitted) {
            org.slf4j.LoggerFactory.getLogger(BookingController.class).debug("Booking queue time {} ms", lease.queueMillis());
            BookingService.Offers result = deadline.within(() -> booking.offers(request.jobId(), Boolean.TRUE.equals(request.refresh())));
            // The bounded scarcity neighborhood is not implemented yet. An insertion miss
            // cannot establish that the prescribed search completed.
            SearchOutcome outcome = result.offers().isEmpty() ? SearchOutcome.SEARCH_INCOMPLETE : SearchOutcome.AVAILABLE;
            return searchResult(request.jobId(), result.offers(), outcome, started, queueMs);
        } catch (RoadClient.RoadUnavailable exception) {
            org.slf4j.LoggerFactory.getLogger(BookingController.class).warn("Booking routing failure for job {}", request.jobId(), exception);
            return searchResult(request.jobId(), Required.value(List.of()), SearchOutcome.ROUTING_UNAVAILABLE, started, queueMs);
        } catch (PessimisticLockingFailureException | org.springframework.transaction.CannotCreateTransactionException
                 | org.springframework.jdbc.CannotGetJdbcConnectionException exception) {
            return searchResult(request.jobId(), Required.value(List.of()), SearchOutcome.SERVICE_BUSY, started, queueMs);
        } catch (QueryTimeoutException | org.springframework.transaction.TransactionTimedOutException | SearchDeadline.Expired exception) {
            return searchResult(request.jobId(), Required.value(List.of()), SearchOutcome.SEARCH_INCOMPLETE, started, queueMs);
        } catch (ResponseStatusException exception) {
            if (exception.getStatusCode().value() != 409) throw exception;
            return searchResult(request.jobId(), Required.value(List.of()), SearchOutcome.SCHEDULE_CONFLICT, started, queueMs);
        }
    }

    private static SearchResponse searchResult(String jobId, List<BookingService.Offer> offers, SearchOutcome outcome, long started, long queueMs) {
        return new SearchResponse(jobId, offers, new SearchStatus(outcome, false,
                (System.nanoTime() - started) / 1_000_000, outcome != SearchOutcome.AVAILABLE, queueMs));
    }
    @PostMapping("/v1/offers/select")
    public BookingService.Selection select(@RequestBody SelectRequest request) { return booking.select(request.jobId(), request.offerId()); }
    @PostMapping("/v1/offers/release")
    public Map<String, Boolean> release(@RequestBody SelectRequest request) { return booking.release(request.jobId(), request.offerId()); }
    @PostMapping("/v1/holds/confirm")
    public BookingService.Confirmation confirm(@RequestBody ConfirmRequest request) { return booking.confirm(request.holdId()); }
    @PostMapping("/v1/appointments/cancel")
    public Map<String, Object> cancel(@RequestBody CancelRequest request) { return booking.cancel(request.appointment_id(), request.reason()); }
    @GetMapping("/health")
    public Map<String, String> health() { return Required.value(Map.of("status", "ok")); }

    @ExceptionHandler(RoadClient.RoadUnavailable.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public Map<String, String> roadUnavailable(RoadClient.RoadUnavailable e) { return Required.value(Map.of("detail", e.getMessage())); }
}
