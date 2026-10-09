package dev.waterflex.scheduler;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Liveness for compose, CI and callers; it reads nothing. */
@RestController
public class HealthController {
    @GetMapping("/health")
    public Map<String, String> health() { return Required.value(Map.of("status", "ok")); }
}
