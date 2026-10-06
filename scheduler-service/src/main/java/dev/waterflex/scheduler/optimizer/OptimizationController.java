package dev.waterflex.scheduler.optimizer;


import org.springframework.web.bind.annotation.*;
import dev.waterflex.scheduler.SearchAdmission;
import dev.waterflex.scheduler.SearchDeadline;
import java.util.Map;

@RestController
public class OptimizationController {
    private final OptimizationService service;
    public OptimizationController(OptimizationService service) { this.service = service; }
    @PostMapping(value = "/v1/optimize/day/preview", produces = "application/json")
    public byte[] preview(@RequestBody OptimizationService.Request request) {
        try { return service.previewJson(request); }
        catch (SearchAdmission.Busy | SearchDeadline.Expired exception) {
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                    "Optimization service temporarily busy; retry", exception);
        }
    }
    @PostMapping("/v1/optimize/runs/{id}/apply")
    public Map<String, Object> apply(@PathVariable String id, @RequestBody dev.waterflex.scheduler.RequestChecks.Empty request) { return service.apply(id); }
    @GetMapping("/v1/optimize/runs/{id}")
    public Map<String, Object> get(@PathVariable String id) { return service.response(id); }
    @GetMapping("/v1/optimize/runs")
    public Map<String, Object> history(@RequestParam("metro_id") String metroId, @RequestParam String date) {
        return service.history(metroId, date);
    }
}
