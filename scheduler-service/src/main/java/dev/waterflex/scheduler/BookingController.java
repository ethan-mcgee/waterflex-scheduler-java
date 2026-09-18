package dev.waterflex.scheduler;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@RestController
public class BookingController {
    private final BookingService booking;
    public BookingController(BookingService booking) { this.booking = booking; }
    public record JobRequest(String jobId, Boolean refresh) { }
    public record SelectRequest(String jobId, String offerId) { }
    public record ConfirmRequest(String holdId) { }
    public record CancelRequest(String appointment_id, String reason) { }

    @PostMapping("/v1/offers")
    public BookingService.Offers offers(@RequestBody JobRequest request) { return booking.offers(request.jobId(), Boolean.TRUE.equals(request.refresh())); }
    @PostMapping("/v1/offers/select")
    public BookingService.Selection select(@RequestBody SelectRequest request) { return booking.select(request.jobId(), request.offerId()); }
    @PostMapping("/v1/holds/confirm")
    public BookingService.Confirmation confirm(@RequestBody ConfirmRequest request) { return booking.confirm(request.holdId()); }
    @PostMapping("/v1/appointments/cancel")
    public Map<String, Object> cancel(@RequestBody CancelRequest request) { return booking.cancel(request.appointment_id(), request.reason()); }
    @GetMapping("/health")
    public Map<String, String> health() { return Map.of("status", "ok"); }

    @ExceptionHandler(RoadClient.RoadUnavailable.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public Map<String, String> roadUnavailable(RoadClient.RoadUnavailable e) { return Map.of("detail", e.getMessage()); }
}
