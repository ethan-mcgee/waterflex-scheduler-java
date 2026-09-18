package dev.waterflex.scheduler;

import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.time.LocalDate;
import java.util.Map;

@RestController
public class ScheduleGuardController {
    private final ScheduleGuardService service;
    public ScheduleGuardController(ScheduleGuardService service) { this.service = service; }
    public record AvailabilityRequest(String technicianId, String date, Boolean available, Integer shiftStartMin, Integer shiftEndMin) { }
    public record QualificationRequest(String technicianId, String serviceId, Boolean qualified) { }

    @PostMapping("/v1/dispatch/availability")
    public Map<String, Object> availability(@RequestBody AvailabilityRequest request) {
        if (request.technicianId() == null || request.available() == null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid availability edit");
        LocalDate day;
        try { day = LocalDate.parse(request.date()); }
        catch (Exception e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid date"); }
        service.availability(request.technicianId(), day, request.available(), request.shiftStartMin(), request.shiftEndMin());
        return Map.of("success", true);
    }

    @PostMapping("/v1/dispatch/qualification")
    public Map<String, Object> qualification(@RequestBody QualificationRequest request) {
        if (request.technicianId() == null || request.serviceId() == null || request.qualified() == null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid qualification edit");
        service.qualification(request.technicianId(), request.serviceId(), request.qualified());
        return Map.of("success", true);
    }
}
