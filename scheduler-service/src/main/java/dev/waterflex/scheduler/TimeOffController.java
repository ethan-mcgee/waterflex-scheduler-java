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
    public Map<String, Object> approve(@PathVariable String id) { return service.approve(id); }
}
