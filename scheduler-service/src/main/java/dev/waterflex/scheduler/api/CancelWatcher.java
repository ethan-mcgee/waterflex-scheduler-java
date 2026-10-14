package dev.waterflex.scheduler.api;

import dev.waterflex.scheduler.Required;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Stops calculations running on this replica whose cancellation the host sent to another replica. Behind a load
 * balancer {@code DELETE /api/v1/requests/{requestId}} may reach any replica; one that does not run the request records
 * the cancel on its claim, and this watcher reads those flags for the requests running here, twice a second by default
 * ({@code scheduler.cancel.poll-ms}). It reads nothing while no request runs here.
 */
@Component
public class CancelWatcher {
    private static final Logger LOG = Required.value(LoggerFactory.getLogger(CancelWatcher.class));
    private final RunningRequests running;
    private final PublicApiStore store;

    public CancelWatcher(RunningRequests running, PublicApiStore store) { this.running = running; this.store = store; }

    @Scheduled(fixedDelayString = "${scheduler.cancel.poll-ms:500}", initialDelayString = "${scheduler.cancel.poll-ms:500}")
    public void poll() {
        try { check(); }
        catch (RuntimeException failure) { LOG.warn("Could not read recorded cancellations; looking again shortly", failure); }
    }

    /** Cancels the requests running here whose cancellation was recorded; returns how many it stopped. */
    int check() {
        List<RunningRequests.Key> here = running.keys();
        if (here.isEmpty()) return 0;
        int stopped = 0;
        for (RunningRequests.Key key : store.cancelRequested(here)) {
            if (running.cancel(key.tenantId(), key.requestId())) {
                stopped++;
                LOG.info("Cancelled request {} of tenant {}: the host cancelled it through another replica", key.requestId(), key.tenantId());
            }
        }
        return stopped;
    }
}
