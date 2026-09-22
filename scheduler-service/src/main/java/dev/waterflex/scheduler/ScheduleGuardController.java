package dev.waterflex.scheduler;

import org.jspecify.annotations.Nullable;

import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.time.LocalDate;
import java.util.Map;

@RestController
public class ScheduleGuardController {
    private final ScheduleGuardService service;
    public ScheduleGuardController(ScheduleGuardService service) { this.service = service; }
    public record AvailabilityRequest(String technicianId, String date, Boolean available, @Nullable Integer shiftStartMin, @Nullable Integer shiftEndMin) { public AvailabilityRequest { technicianId = RequestChecks.text(technicianId, "technicianId"); date = RequestChecks.date(date); if (available == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing available"); } }
    public record DeleteAvailabilityRequest(String technicianId, String date) { public DeleteAvailabilityRequest { technicianId = RequestChecks.text(technicianId, "technicianId"); date = RequestChecks.date(date); } }
    public record QualificationRequest(String technicianId, String serviceId, Boolean qualified) { public QualificationRequest { technicianId = RequestChecks.text(technicianId, "technicianId"); serviceId = RequestChecks.text(serviceId, "serviceId"); if (qualified == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing qualified"); } }

    @PostMapping("/v1/dispatch/availability")
    public Map<String, Object> availability(@RequestBody AvailabilityRequest request) {
        LocalDate day;
        try { day = LocalDate.parse(request.date()); }
        catch (Exception e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid date"); }
        service.availability(request.technicianId(), Required.value(day), request.available(), request.shiftStartMin(), request.shiftEndMin());
        return Required.value(Map.of("success", true));
    }

    @DeleteMapping("/v1/dispatch/availability")
    public Map<String, Object> deleteAvailability(@RequestBody DeleteAvailabilityRequest request) {
        LocalDate day;
        try { day = LocalDate.parse(request.date()); }
        catch (Exception e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid date"); }
        service.deleteAvailability(request.technicianId(), Required.value(day));
        return Required.value(Map.of("success", true));
    }

    @PostMapping("/v1/dispatch/qualification")
    public Map<String, Object> qualification(@RequestBody QualificationRequest request) {
        service.qualification(request.technicianId(), request.serviceId(), request.qualified());
        return Required.value(Map.of("success", true));
    }
}
