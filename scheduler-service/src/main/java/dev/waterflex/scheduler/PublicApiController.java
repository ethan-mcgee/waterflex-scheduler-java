package dev.waterflex.scheduler;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public scheduling API for WaterFlex Software. Every route is under /api/v1 and tenant-authenticated. */
@RestController
public class PublicApiController {
    /** Lets an integrator confirm which tenant a token belongs to. */
    @GetMapping("/api/v1/whoami")
    public dev.waterflex.scheduler.api.PublicResponses.WhoAmI whoami(HttpServletRequest request) {
        return new dev.waterflex.scheduler.api.PublicResponses.WhoAmI(TenantAuthentication.tenant(request));
    }
}
