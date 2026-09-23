package dev.waterflex.scheduler;


import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
public class TimeOffController {
    private final TimeOffService service;
    public TimeOffController(TimeOffService service) { this.service = service; }
    @PostMapping("/v1/time-off/request")
    public Map<String, Object> submit(@RequestBody TimeOffService.Request request) { return service.submit(request); }
    @PostMapping("/v1/time-off/{id}/approve")
    public Map<String, Object> approve(@PathVariable String id, @RequestBody dev.waterflex.scheduler.RequestChecks.Empty request) { return service.approve(id); }
    @PostMapping("/v1/time-off/{id}/retry")
    public Map<String, Object> retry(@PathVariable String id, @RequestBody dev.waterflex.scheduler.RequestChecks.Empty request) { return service.retry(id); }
    @PostMapping("/v1/time-off/{id}/deny")
    public Map<String, Object> deny(@PathVariable String id, @RequestBody dev.waterflex.scheduler.RequestChecks.Empty request) { return service.deny(id); }
}
