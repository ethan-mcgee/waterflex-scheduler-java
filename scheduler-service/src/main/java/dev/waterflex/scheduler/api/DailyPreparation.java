package dev.waterflex.scheduler.api;

import dev.waterflex.scheduler.MetroRouting;
import dev.waterflex.scheduler.RoadClient;
import dev.waterflex.scheduler.api.PublicRequests.DailyProposalRequest;
import dev.waterflex.scheduler.optimizer.DayPlan;
import java.util.HashMap;
import java.util.Map;

/** Routes a daily request through its metro's routing service and builds the solver input. No database access. */
public final class DailyPreparation {
    private DailyPreparation() { }

    /** The solver input and the routing identity its legs came from, which a proposal records. */
    public record Prepared(DayPlan plan, String routingIdentity) { }

    /**
     * Fails with {@link MetroRouting.UnknownMetro} before any geocoding when the metro has no routing service, and
     * with {@link RoadClient.RoadUnavailable} when routing is down or its identity changes while the legs are fetched.
     */
    public static Prepared prepare(DailyProposalRequest request, MetroRouting routing, RequestDay.Locator locator) {
        RoadClient roads = routing.client(request.snapshot().metroId());
        RequestDay day = RequestDay.of(request, locator);
        String identity = roads.activeIdentity();
        Map<String, DayPlan.RoadLeg> reachable = new HashMap<>();
        roads.matrix(day.points(), identity).forEach((pair, leg) -> reachable.put(pair, new DayPlan.RoadLeg(leg.seconds(), leg.meters())));
        // The database path rejects a plan whose routing identity moved during capture; so does this one.
        if (!identity.equals(roads.activeIdentity())) throw new RoadClient.RoadUnavailable("Routing identity changed");
        return new Prepared(day.plan(reachable), identity);
    }
}
