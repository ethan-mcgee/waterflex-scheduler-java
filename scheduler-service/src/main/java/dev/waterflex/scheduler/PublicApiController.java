package dev.waterflex.scheduler;

import dev.waterflex.scheduler.api.DailyProposals;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Public scheduling API for WaterFlex Software. Every route is under /api/v1 and tenant-authenticated. */
@RestController
public class PublicApiController {
    private final DailyProposals dailyProposals;

    public PublicApiController(DailyProposals dailyProposals) { this.dailyProposals = dailyProposals; }

    /** Lets an integrator confirm which tenant a token belongs to. */
    @GetMapping("/api/v1/whoami")
    public dev.waterflex.scheduler.api.PublicResponses.WhoAmI whoami(HttpServletRequest request) {
        return new dev.waterflex.scheduler.api.PublicResponses.WhoAmI(TenantAuthentication.tenant(request));
    }

    /** The raw body is parsed strictly by the service, never by Spring's lenient default mapper. */
    @PostMapping("/api/v1/daily/proposals")
    public ResponseEntity<String> createDailyProposal(HttpServletRequest request, @RequestBody String body) {
        DailyProposals.Reply reply = dailyProposals.create(TenantAuthentication.tenant(request), body);
        var response = ResponseEntity.status(reply.status()).contentType(MediaType.APPLICATION_JSON);
        Integer retryAfter = reply.retryAfterSeconds();
        if (retryAfter != null) response.header("Retry-After", Integer.toString(retryAfter));
        return response.body(reply.json());
    }
}
