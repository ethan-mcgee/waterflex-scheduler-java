package dev.waterflex.scheduler;

import dev.waterflex.scheduler.api.DailyCommits;
import dev.waterflex.scheduler.api.DailyProposals;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Public scheduling API for WaterFlex Software. Every route is under /api/v1 and tenant-authenticated. */
@RestController
public class PublicApiController {
    private final DailyProposals dailyProposals;
    private final DailyCommits dailyCommits;

    public PublicApiController(DailyProposals dailyProposals, DailyCommits dailyCommits) {
        this.dailyProposals = dailyProposals;
        this.dailyCommits = dailyCommits;
    }

    /** Lets an integrator confirm which tenant a token belongs to. */
    @GetMapping("/api/v1/whoami")
    public dev.waterflex.scheduler.api.PublicResponses.WhoAmI whoami(HttpServletRequest request) {
        return new dev.waterflex.scheduler.api.PublicResponses.WhoAmI(TenantAuthentication.tenant(request));
    }

    /** The raw body is parsed strictly by the service, never by Spring's lenient default mapper. */
    @PostMapping("/api/v1/daily/proposals")
    public ResponseEntity<String> createDailyProposal(HttpServletRequest request, @RequestBody String body) {
        return reply(dailyProposals.create(TenantAuthentication.tenant(request), body));
    }

    @PostMapping("/api/v1/daily/proposals/{proposalId}/commit")
    public ResponseEntity<String> commitDailyProposal(HttpServletRequest request, @PathVariable String proposalId, @RequestBody String body) {
        return reply(dailyCommits.commit(TenantAuthentication.tenant(request), proposalId, body));
    }

    private static ResponseEntity<String> reply(DailyProposals.Reply reply) {
        var response = ResponseEntity.status(reply.status()).contentType(MediaType.APPLICATION_JSON);
        Integer retryAfter = reply.retryAfterSeconds();
        if (retryAfter != null) response.header("Retry-After", Integer.toString(retryAfter));
        return response.body(reply.json());
    }
}
