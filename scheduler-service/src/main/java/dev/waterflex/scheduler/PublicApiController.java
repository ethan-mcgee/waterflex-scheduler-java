package dev.waterflex.scheduler;

import dev.waterflex.scheduler.api.BookingConfirm;
import dev.waterflex.scheduler.api.BookingHolds;
import dev.waterflex.scheduler.api.BookingOffers;
import dev.waterflex.scheduler.api.DailyCommits;
import dev.waterflex.scheduler.api.DailyProposals;
import dev.waterflex.scheduler.api.RouteEvaluations;
import dev.waterflex.scheduler.api.RouteGeometries;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public scheduling API for WaterFlex Software. Every route is under /api/v1 and tenant-authenticated. */
@RestController
public class PublicApiController {
    private final DailyProposals dailyProposals;
    private final DailyCommits dailyCommits;
    private final BookingOffers bookingOffers;
    private final BookingHolds bookingHolds;
    private final BookingConfirm bookingConfirm;
    private final RouteEvaluations routeEvaluations;
    private final RouteGeometries routeGeometries;

    public PublicApiController(DailyProposals dailyProposals, DailyCommits dailyCommits, BookingOffers bookingOffers, BookingHolds bookingHolds,
                               BookingConfirm bookingConfirm, RouteEvaluations routeEvaluations, RouteGeometries routeGeometries) {
        this.dailyProposals = dailyProposals;
        this.dailyCommits = dailyCommits;
        this.bookingOffers = bookingOffers;
        this.bookingHolds = bookingHolds;
        this.bookingConfirm = bookingConfirm;
        this.routeEvaluations = routeEvaluations;
        this.routeGeometries = routeGeometries;
    }

    /** Lets an integrator confirm which tenant a token belongs to. */
    @GetMapping("/api/v1/whoami")
    public dev.waterflex.scheduler.api.PublicResponses.WhoAmI whoami(HttpServletRequest request) {
        return new dev.waterflex.scheduler.api.PublicResponses.WhoAmI(TenantAuthentication.tenant(request));
    }

    @PostMapping("/api/v1/daily/proposals")
    public void createDailyProposal(HttpServletRequest request, HttpServletResponse response) throws IOException {
        reply(response, dailyProposals.create(TenantAuthentication.tenant(request), body(request)));
    }

    @PostMapping("/api/v1/repairs/proposals")
    public void createRepairProposal(HttpServletRequest request, HttpServletResponse response) throws IOException {
        reply(response, dailyProposals.repair(TenantAuthentication.tenant(request), body(request)));
    }

    @PostMapping("/api/v1/daily/proposals/{proposalId}/commit")
    public void commitDailyProposal(HttpServletRequest request, @PathVariable String proposalId, HttpServletResponse response) throws IOException {
        reply(response, dailyCommits.commit(TenantAuthentication.tenant(request), proposalId, body(request)));
    }

    @PostMapping("/api/v1/routes/evaluate")
    public void evaluateRoutes(HttpServletRequest request, HttpServletResponse response) throws IOException {
        reply(response, routeEvaluations.evaluate(TenantAuthentication.tenant(request), body(request)));
    }

    @PostMapping("/api/v1/routes/geometry")
    public void drawRoutes(HttpServletRequest request, HttpServletResponse response) throws IOException {
        reply(response, routeGeometries.draw(TenantAuthentication.tenant(request), body(request)));
    }

    @PostMapping("/api/v1/booking/offers")
    public void createBookingOffers(HttpServletRequest request, HttpServletResponse response) throws IOException {
        reply(response, bookingOffers.create(TenantAuthentication.tenant(request), body(request)));
    }

    @PostMapping("/api/v1/booking/offers/{offerId}/select")
    public void selectBookingOffer(HttpServletRequest request, @PathVariable String offerId, HttpServletResponse response) throws IOException {
        reply(response, bookingHolds.select(TenantAuthentication.tenant(request), offerId, body(request)));
    }

    @PostMapping("/api/v1/booking/offers/{offerId}/release")
    public void releaseBookingOffer(HttpServletRequest request, @PathVariable String offerId, HttpServletResponse response) throws IOException {
        reply(response, bookingHolds.release(TenantAuthentication.tenant(request), offerId, body(request)));
    }

    @PostMapping("/api/v1/booking/holds/{holdId}/confirm")
    public void confirmBookingHold(HttpServletRequest request, @PathVariable String holdId, HttpServletResponse response) throws IOException {
        reply(response, bookingConfirm.confirm(TenantAuthentication.tenant(request), holdId, body(request)));
    }

    /**
     * The request body exactly as sent, for the service's strict parser. Bodies are read and written here rather than
     * through message converters: the application's JSON converter is registered ahead of the plain-text one, so it
     * would refuse a JSON object bound to a String and would encode a JSON reply a second time.
     */
    private static String body(HttpServletRequest request) throws IOException {
        return new String(request.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }

    /** Writes the service's JSON exactly as produced; a message converter would encode it again as a JSON string. */
    private static void reply(HttpServletResponse response, DailyProposals.Reply reply) throws IOException {
        response.setStatus(reply.status());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        Integer retryAfter = reply.retryAfterSeconds();
        if (retryAfter != null) response.setHeader("Retry-After", Integer.toString(retryAfter));
        byte[] json = reply.json().getBytes(StandardCharsets.UTF_8);
        response.setContentLength(json.length);
        response.getOutputStream().write(json);
    }
}
