package dev.waterflex.scheduler.api;

import dev.waterflex.scheduler.MetroRouting;
import dev.waterflex.scheduler.Required;
import dev.waterflex.scheduler.RoadClient;
import dev.waterflex.scheduler.SearchAdmission;
import dev.waterflex.scheduler.SearchDeadline;
import dev.waterflex.scheduler.api.PublicRequests.DailyProposalRequest;
import dev.waterflex.scheduler.api.PublicRequests.RouteEvaluationRequest;
import dev.waterflex.scheduler.api.PublicResponses.*;
import dev.waterflex.scheduler.optimizer.DailyOperation;
import dev.waterflex.scheduler.optimizer.PlanVisit;
import dev.waterflex.scheduler.optimizer.RouteEvaluator;
import dev.waterflex.scheduler.optimizer.TechRoute;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * POST /api/v1/routes/evaluate. Times one metro day's routes exactly in the order the snapshot gives them, with the
 * snapshot's start and end points, and reports whether the day holds. Nothing is optimized, stored or claimed, so a
 * host can check a change to its own facts (a depot's location or endpoints, a technician's depot) before making it.
 */
@Service
public class RouteEvaluations {
    private static final Logger LOG = Required.value(LoggerFactory.getLogger(RouteEvaluations.class));

    private final MetroRouting routing;
    private final DailyPreparation.AddressLocator locator;
    private final SearchAdmission admission;

    @Autowired
    public RouteEvaluations(MetroRouting routing, AddressLocation locator, SearchAdmission admission) {
        this(routing, (DailyPreparation.AddressLocator) locator, admission);
    }

    public RouteEvaluations(MetroRouting routing, DailyPreparation.AddressLocator locator, SearchAdmission admission) {
        this.routing = routing; this.locator = locator; this.admission = admission;
    }

    public DailyProposals.Reply evaluate(String tenantId, String body) {
        RouteEvaluationRequest request;
        try { request = PublicRequests.read(body, RouteEvaluationRequest.class); }
        catch (IllegalArgumentException invalid) { return DailyProposals.Reply.of(400, new Problem(ErrorCode.INVALID_REQUEST, DailyProposals.invalidRequest(invalid))); }
        try {
            RouteEvaluation evaluation = DailyOperation.execute(admission, Required.value(Duration.ofSeconds(20)), () -> evaluate(request),
                    receipt -> LOG.info("Public route evaluation tenant={} {}", tenantId, receipt));
            return DailyProposals.Reply.of(200, evaluation);
        } catch (MetroRouting.UnknownMetro unknown) {
            return DailyProposals.Reply.of(422, new Problem(ErrorCode.INCOMPLETE_FACTS, Required.value(unknown.getMessage())));
        } catch (RoadClient.RoadUnavailable unavailable) {
            return DailyProposals.Reply.of(503, DailyProposals.routingUnavailable(unavailable));
        } catch (SearchAdmission.Busy busy) {
            return DailyProposals.Reply.of(429, new Problem(ErrorCode.BUSY, "Search capacity exhausted"));
        } catch (SearchDeadline.Expired expired) {
            return DailyProposals.Reply.of(503, new Problem(ErrorCode.CALCULATION_UNAVAILABLE, "The calculation deadline passed"));
        }
    }

    private RouteEvaluation evaluate(RouteEvaluationRequest request) {
        // Preparation takes a proposal request; the identity is local to this call and never stored.
        var prepared = DailyPreparation.prepare(new DailyProposalRequest(Required.value(UUID.randomUUID().toString()), request.serviceDate(), request.snapshot()),
                routing, locator);
        var plan = prepared.plan();
        var result = RouteEvaluator.evaluate(plan);
        // A day that does not hold cannot be timed as given: the evaluation stops at the first stop it cannot reach.
        if (!result.feasible()) return new RouteEvaluation(false, Required.value(List.of()), prepared.skipped(), result.costCents(), Math.toIntExact(result.overtimeMinutes()));
        List<PlannedRoute> routes = new ArrayList<>();
        for (TechRoute route : plan.getRoutes()) {
            List<PlannedStop> stops = new ArrayList<>();
            for (int index = 0; index < route.getVisits().size(); index++) {
                PlanVisit visit = Required.value(route.getVisits().get(index));
                Instant start = Required.value(result.arrivals().get(visit.getId()), "arrival for " + visit.getId());
                stops.add(new PlannedStop(visit.getId(), index, start, Required.value(start.plus(Duration.ofMinutes(visit.getDurationMinutes())))));
            }
            routes.add(new PlannedRoute(route.getId(), request.serviceDate(), Required.value(List.copyOf(stops))));
        }
        return new RouteEvaluation(true, Required.value(List.copyOf(routes)), prepared.skipped(), result.costCents(), Math.toIntExact(result.overtimeMinutes()));
    }
}
