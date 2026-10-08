package dev.waterflex.scheduler;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public scheduling API for WaterFlex Software. Every route is under /api/v1 and tenant-authenticated. */
@RestController
public class PublicApiController {
    /** Lets an integrator confirm which tenant a token belongs to. */
    @GetMapping("/api/v1/whoami")
    public Map<String, String> whoami(HttpServletRequest request) {
        return Required.value(Map.of("tenantId", TenantAuthentication.tenant(request)));
    }
}
