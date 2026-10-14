package dev.waterflex.scheduler;

import dev.waterflex.scheduler.api.BookingOffers;
import dev.waterflex.scheduler.api.DailyQueueWorker;
import dev.waterflex.scheduler.api.RunningRequests;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.stereotype.Component;

/**
 * The scheduler's own meters, beside Spring's {@code http_server_requests_seconds} (each API operation by route and
 * status): admission load and rejections, routing service calls per metro, invalid refinements, host cancellations,
 * rows the retention purge deleted, and this replica's work from the asynchronous daily queue. Every meter reads a counter the service keeps anyway.
 * {@code scheduler.replica.info} is always 1 and carries the replica's role, so dashboards can split every other meter
 * by pool.
 */
@Component
public class SchedulerMetrics implements MeterBinder {
    private final SearchAdmission admission;
    private final MetroRouting routing;
    private final BookingOffers offers;
    private final RunningRequests running;
    private final Retention retention;
    private final DailyQueueWorker queue;
    private final ReplicaRole role;

    public SchedulerMetrics(SearchAdmission admission, MetroRouting routing, BookingOffers offers, RunningRequests running, Retention retention,
                            DailyQueueWorker queue, ReplicaRole role) {
        this.admission = admission; this.routing = routing; this.offers = offers; this.running = running; this.retention = retention; this.queue = queue;
        this.role = role;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder("scheduler.replica.info", role, _ -> 1).tag("role", role.label())
                .description("This replica's role (booking, daily or all); always 1").register(registry);
        Gauge.builder("scheduler.admission.background.limit", admission, SearchAdmission::backgroundLimit)
                .description("Background calculations (daily and repair) this replica runs at once").register(registry);
        FunctionCounter.builder("scheduler.admission.rejections", admission, SearchAdmission::rejections)
                .description("Calculations refused because search capacity and its queue were full").register(registry);
        FunctionCounter.builder("scheduler.admission.evictions", admission, SearchAdmission::evictions)
                .description("Queued bookings that gave their place to a booking of a client with fewer waiting").register(registry);
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
        FunctionCounter.builder("scheduler.booking.refinement.shed", offers, BookingOffers::shedRefinements)
                .description("Optional booking refinements skipped because other booking requests were waiting")
                .register(registry);
        FunctionCounter.builder("scheduler.booking.refinement.invalid", offers, BookingOffers::invalidRefinements)
                .description("Remote refinements that failed validation; their insertion offers were published").register(registry);
        FunctionCounter.builder("scheduler.requests.cancelled", running, RunningRequests::cancellations)
                .description("Host cancellations that found their request running").register(registry);
        FunctionCounter.builder("scheduler.daily.queue.started", queue, DailyQueueWorker::started)
                .description("Queued daily and repair proposals this replica started").register(registry);
        FunctionCounter.builder("scheduler.daily.queue.expired", queue, DailyQueueWorker::expired)
                .description("Queued proposals that waited longer than the queue allows and ended without running").register(registry);
        FunctionCounter.builder("scheduler.daily.queue.failed", queue, DailyQueueWorker::failed)
                .description("Queued proposals that failed unexpectedly here and were queued again").register(registry);
        FunctionCounter.builder("scheduler.retention.deleted", retention, Retention::deleted)
                .description("Thin store rows deleted by the retention purge").register(registry);
    }
}
