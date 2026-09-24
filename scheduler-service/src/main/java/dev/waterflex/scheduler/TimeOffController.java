package dev.waterflex.scheduler;


import org.springframework.web.bind.annotation.*;
import java.util.Map;
import java.util.List;
import org.jspecify.annotations.Nullable;

@RestController
public class TimeOffController {
    private final TimeOffService service;
    public TimeOffController(TimeOffService service) { this.service = service; }
    public record Approval(@Nullable Boolean allowAdditionalOvertime,
                           @Nullable List<String> approvedRepairIds) {
        public Approval {
            if (Boolean.TRUE.equals(allowAdditionalOvertime)) {
                if (approvedRepairIds == null || approvedRepairIds.isEmpty() || approvedRepairIds.size() > 366)
                    throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, "Reviewed repair IDs required");
                for (String id : approvedRepairIds) RequestChecks.text(id, "approved repair ID");
                if (new java.util.HashSet<>(approvedRepairIds).size() != approvedRepairIds.size())
                    throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, "Duplicate reviewed repair ID");
            }
        }
    }
    @PostMapping("/v1/time-off/request")
    public Map<String, Object> submit(@RequestBody TimeOffService.Request request) { return service.submit(request); }
    @PostMapping("/v1/time-off/{id}/approve")
    public Map<String, Object> approve(@PathVariable String id, @RequestBody Approval request) {
        return service.approve(id, Boolean.TRUE.equals(request.allowAdditionalOvertime()),
                request.approvedRepairIds() == null ? dev.waterflex.scheduler.Required.value(java.util.List.of()) : dev.waterflex.scheduler.Required.value(request.approvedRepairIds()));
    }
    @PostMapping("/v1/time-off/{id}/retry")
    public Map<String, Object> retry(@PathVariable String id, @RequestBody dev.waterflex.scheduler.RequestChecks.Empty request) { return service.retry(id); }
    @PostMapping("/v1/time-off/{id}/deny")
    public Map<String, Object> deny(@PathVariable String id, @RequestBody dev.waterflex.scheduler.RequestChecks.Empty request) { return service.deny(id); }
}
