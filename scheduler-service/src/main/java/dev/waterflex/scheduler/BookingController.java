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
    private final BookingCoordinator coordinator;
    private final ReservationLifecycleService lifecycle;
    private final BookingSearchControl searches;
    public BookingController(BookingService booking, SearchAdmission admission, BookingCoordinator coordinator,
            ReservationLifecycleService lifecycle, BookingSearchControl searches, @org.springframework.beans.factory.annotation.Value("${booking.reservations.enabled:false}") boolean reservationsEnabled) {
        this.booking = booking; this.admission = admission; this.coordinator = coordinator; this.lifecycle = lifecycle; this.searches = searches;
        org.slf4j.LoggerFactory.getLogger(BookingController.class).debug("Legacy reservation rollout flag {}: policy-v2 always uses common reservations", reservationsEnabled);
    }
    public record JobRequest(String jobId, @Nullable Boolean refresh, @Nullable Long deadlineEpochMs, @Nullable String searchRequestId) {
        public JobRequest {
            jobId = RequestChecks.text(jobId, "jobId");
            if (searchRequestId != null) BookingSearchControl.token(searchRequestId);
            if (deadlineEpochMs != null && deadlineEpochMs <= 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid search deadline");
        }
        public JobRequest(String jobId, @Nullable Boolean refresh) { this(jobId, refresh, null, null); }
    }
    public record SearchCancellation(String jobId, String searchRequestId) { public SearchCancellation { jobId = RequestChecks.text(jobId, "jobId"); searchRequestId = RequestChecks.text(searchRequestId, "searchRequestId"); BookingSearchControl.token(searchRequestId); } }
    @PostMapping("/v1/offers/cancel-search")
    public Map<String, Boolean> cancelSearch(@RequestBody SearchCancellation request) { searches.cancel(request.searchRequestId(), request.jobId()); return Required.value(Map.of("success", true)); }
    @PostMapping("/v1/offers/acknowledge-search")
    public Map<String, Boolean> acknowledgeSearch(@RequestBody SearchCancellation request) { searches.acknowledge(request.searchRequestId(), request.jobId()); return Required.value(Map.of("success", true)); }

    public record SelectRequest(String jobId, String offerId) { public SelectRequest { jobId = RequestChecks.text(jobId, "jobId"); offerId = RequestChecks.text(offerId, "offerId"); } }
    public record ConfirmRequest(String holdId) { public ConfirmRequest { holdId = RequestChecks.text(holdId, "holdId"); } }
    public record CancelRequest(String appointment_id, String reason) { public CancelRequest { appointment_id = RequestChecks.text(appointment_id, "appointment_id"); reason = RequestChecks.text(reason, "reason"); } }
    public enum SearchOutcome { AVAILABLE, SEARCH_INCOMPLETE, NO_CANDIDATE_FOUND, ROUTING_UNAVAILABLE, SCHEDULE_CONFLICT, SERVICE_BUSY }
    public record SearchStatus(SearchOutcome outcome, boolean prescribedSearchCompleted, long elapsedMs, boolean retryable, long queueMs,
            SearchTelemetry.@Nullable Measurements measurements) {
        public SearchStatus(SearchOutcome outcome, boolean completed, long elapsedMs, boolean retryable, long queueMs) {
            this(outcome, completed, elapsedMs, retryable, queueMs, measurementsNow());
        }
        private static SearchTelemetry.@Nullable Measurements measurementsNow() {
            SearchDeadline current = SearchDeadline.current();
            return current == null ? null : current.telemetry().snapshot();
        }
    }
    public record SearchResponse(String jobId, List<BookingService.Offer> offers, SearchStatus search) { }

    @PostMapping("/v1/offers")
    public SearchResponse offers(@RequestBody JobRequest request) {
        long started = System.nanoTime();
        Long requestedDeadline = request.deadlineEpochMs();
        long remainingMs = requestedDeadline == null ? 5000 : Math.min(5000, requestedDeadline - System.currentTimeMillis());
        if (remainingMs <= 0) return searchResult(request.jobId(), Required.value(List.of()), SearchOutcome.SEARCH_INCOMPLETE, started, 0);
        SearchDeadline deadline = new SearchDeadline(Required.value(java.time.Duration.ofMillis(remainingMs)));
        try {
            return deadline.within(() -> {
                String token = request.searchRequestId();
                if (token == null) return search(request, deadline, started);
                try (var _ = searches.open(token, request.jobId(), deadline)) {
                    return search(request, deadline, started);
                }
            });
        } catch (SearchDeadline.Expired | QueryTimeoutException | org.springframework.transaction.TransactionTimedOutException | org.springframework.transaction.TransactionSystemException failure) {
            return searchResult(request.jobId(), Required.value(List.of()), SearchOutcome.SEARCH_INCOMPLETE, started, 0);
        } catch (SearchAdmission.Busy | org.springframework.dao.PessimisticLockingFailureException | org.springframework.transaction.CannotCreateTransactionException
                | org.springframework.dao.DataAccessResourceFailureException failure) {
            return searchResult(request.jobId(), Required.value(List.of()), SearchOutcome.SERVICE_BUSY, started, 0);
        }
    }
    private SearchResponse search(JobRequest request, SearchDeadline deadline, long started) {
        SearchAdmission.Lease admitted;
        try { admitted = admission.acquire(SearchAdmission.Kind.BOOKING, deadline); }
        catch (SearchAdmission.Busy | SearchDeadline.Expired exception) {
            return searchResult(request.jobId(), Required.value(List.of()), SearchOutcome.SERVICE_BUSY, started, deadline.elapsedMillis());
        }
        long queueMs = admitted.queueMillis();
        try (var lease = admitted) {
            org.slf4j.LoggerFactory.getLogger(BookingController.class).debug("Booking queue time {} ms", lease.queueMillis());
            BookingCoordinator.Result result = coordinator.offers(request.jobId(), Boolean.TRUE.equals(request.refresh()));
            SearchOutcome outcome = !result.offers().offers().isEmpty() ? SearchOutcome.AVAILABLE
                    : result.completed() ? SearchOutcome.NO_CANDIDATE_FOUND
                    : result.stopReason().equals("ROUTING_UNAVAILABLE") ? SearchOutcome.ROUTING_UNAVAILABLE : SearchOutcome.SEARCH_INCOMPLETE;
            return new SearchResponse(request.jobId(), result.offers().offers(), new SearchStatus(outcome, result.completed(),
                    (System.nanoTime() - started) / 1_000_000, outcome != SearchOutcome.AVAILABLE && outcome != SearchOutcome.NO_CANDIDATE_FOUND, queueMs));
        } catch (RoadClient.RoadUnavailable exception) {
            org.slf4j.LoggerFactory.getLogger(BookingController.class).warn("Booking routing failure for job {}", request.jobId(), exception);
            return searchResult(request.jobId(), Required.value(List.of()), SearchOutcome.ROUTING_UNAVAILABLE, started, queueMs);
        } catch (SearchAdmission.Busy | PessimisticLockingFailureException | org.springframework.transaction.CannotCreateTransactionException
                 | org.springframework.dao.DataAccessResourceFailureException exception) {
            return searchResult(request.jobId(), Required.value(List.of()), SearchOutcome.SERVICE_BUSY, started, queueMs);
        } catch (QueryTimeoutException | org.springframework.transaction.TransactionTimedOutException | org.springframework.transaction.TransactionSystemException | SearchDeadline.Expired exception) {
            return searchResult(request.jobId(), Required.value(List.of()), SearchOutcome.SEARCH_INCOMPLETE, started, queueMs);
        } catch (BookingSnapshot.Incomplete exception) {
            org.slf4j.LoggerFactory.getLogger(BookingController.class).warn("Incomplete booking snapshot for job {}", request.jobId(), exception);
            return searchResult(request.jobId(), Required.value(List.of()), SearchOutcome.SCHEDULE_CONFLICT, started, queueMs);
        } catch (ResponseStatusException exception) {
            if (exception.getStatusCode().value() != 409) throw exception;
            org.slf4j.LoggerFactory.getLogger(BookingController.class).warn("Booking conflict for job {}: {}", request.jobId(), exception.getReason());
            return searchResult(request.jobId(), Required.value(List.of()), SearchOutcome.SCHEDULE_CONFLICT, started, queueMs);
        }
    }

    private static SearchResponse searchResult(String jobId, List<BookingService.Offer> offers, SearchOutcome outcome, long started, long queueMs) {
        return new SearchResponse(jobId, offers, new SearchStatus(outcome, false,
                (System.nanoTime() - started) / 1_000_000, outcome != SearchOutcome.AVAILABLE, queueMs));
    }
    @PostMapping("/v1/offers/select")
    public BookingService.Selection select(@RequestBody SelectRequest request) {
        return coordinator.managedOffer(request.jobId(), request.offerId()) ? lifecycle.select(request.jobId(), request.offerId()) : booking.select(request.jobId(), request.offerId());
    }
    @PostMapping("/v1/offers/release")
    public Map<String, Boolean> release(@RequestBody SelectRequest request) {
        return coordinator.managedOffer(request.jobId(), request.offerId()) ? lifecycle.release(request.jobId(), request.offerId()) : booking.release(request.jobId(), request.offerId());
    }
    @PostMapping("/v1/holds/confirm")
    public BookingService.Confirmation confirm(@RequestBody ConfirmRequest request) {
        return coordinator.managedHold(request.holdId()) ? lifecycle.confirm(request.holdId()) : booking.confirm(request.holdId());
    }
    @PostMapping("/v1/appointments/cancel")
    public Map<String, Object> cancel(@RequestBody CancelRequest request) {
        return coordinator.managedAppointment(request.appointment_id()) ? lifecycle.cancel(request.appointment_id(), request.reason()) : booking.cancel(request.appointment_id(), request.reason());
    }
    @GetMapping("/health")
    public Map<String, String> health() { return Required.value(Map.of("status", "ok")); }

    @ExceptionHandler(RoadClient.RoadUnavailable.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public Map<String, String> roadUnavailable(RoadClient.RoadUnavailable e) { return Required.value(Map.of("detail", e.getMessage())); }
}
