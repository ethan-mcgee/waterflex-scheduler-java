package dev.waterflex.scheduler;

import dev.waterflex.scheduler.api.BookingOffers;
import dev.waterflex.scheduler.api.RunningRequests;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.stereotype.Component;

/**
 * The scheduler's own meters, beside Spring's {@code http_server_requests_seconds} (each API operation by route and
 * status): admission load and rejections, routing service calls per metro, invalid refinements, host cancellations,
 * and rows the retention purge deleted. Every meter reads a counter the service keeps anyway.
 */
@Component
public class SchedulerMetrics implements MeterBinder {
    private final SearchAdmission admission;
    private final MetroRouting routing;
    private final BookingOffers offers;
    private final RunningRequests running;
    private final Retention retention;

    public SchedulerMetrics(SearchAdmission admission, MetroRouting routing, BookingOffers offers, RunningRequests running, Retention retention) {
        this.admission = admission; this.routing = routing; this.offers = offers; this.running = running; this.retention = retention;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        FunctionCounter.builder("scheduler.admission.rejections", admission, SearchAdmission::rejections)
                .description("Calculations refused because search capacity and its queue were full").register(registry);
        Gauge.builder("scheduler.admission.active", admission, current -> current.state().active())
                .description("Calculations running now").register(registry);
        Gauge.builder("scheduler.admission.queued", admission, current -> current.state().queuedBookings() + current.state().queuedBackground())
                .description("Calculations waiting for capacity").register(registry);
        for (String listed : routing.metros()) {
            String metro = Required.value(listed);
            RoadClient client = routing.client(metro);
            FunctionCounter.builder("scheduler.routing.leg.requests", client, roads -> roads.httpMeasurements().legRequests())
                    .tag("metro", metro).description("Batches of legs fetched from the routing service").register(registry);
            FunctionCounter.builder("scheduler.routing.pairs", client, roads -> roads.httpMeasurements().requestedPairs())
                    .tag("metro", metro).description("Origin and destination pairs fetched from the routing service").register(registry);
            FunctionCounter.builder("scheduler.routing.identity.requests", client, roads -> roads.httpMeasurements().identityRequests())
                    .tag("metro", metro).description("Routing identity checks").register(registry);
        }
        FunctionCounter.builder("scheduler.booking.refinement.invalid", offers, BookingOffers::invalidRefinements)
                .description("Remote refinements that failed validation; their insertion offers were published").register(registry);
        FunctionCounter.builder("scheduler.requests.cancelled", running, RunningRequests::cancellations)
                .description("Host cancellations that found their request running").register(registry);
        FunctionCounter.builder("scheduler.retention.deleted", retention, Retention::deleted)
                .description("Thin store rows deleted by the retention purge").register(registry);
    }
}
