package dev.waterflex.scheduler;

import org.jspecify.annotations.Nullable;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
public class BookingController {
    private final BookingService booking;
    public BookingController(BookingService booking) { this.booking = booking; }
    public record JobRequest(String jobId, @Nullable Boolean refresh) { public JobRequest { jobId = RequestChecks.text(jobId, "jobId"); } }
    public record SelectRequest(String jobId, String offerId) { public SelectRequest { jobId = RequestChecks.text(jobId, "jobId"); offerId = RequestChecks.text(offerId, "offerId"); } }
    public record ConfirmRequest(String holdId) { public ConfirmRequest { holdId = RequestChecks.text(holdId, "holdId"); } }
    public record CancelRequest(String appointment_id, String reason) { public CancelRequest { appointment_id = RequestChecks.text(appointment_id, "appointment_id"); reason = RequestChecks.text(reason, "reason"); } }

    @PostMapping("/v1/offers")
    public BookingService.Offers offers(@RequestBody JobRequest request) { return booking.offers(request.jobId(), Boolean.TRUE.equals(request.refresh())); }
    @PostMapping("/v1/offers/select")
    public BookingService.Selection select(@RequestBody SelectRequest request) { return booking.select(request.jobId(), request.offerId()); }
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
