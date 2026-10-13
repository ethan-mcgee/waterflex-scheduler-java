package dev.waterflex.scheduler.api;

import dev.waterflex.scheduler.Required;
import dev.waterflex.scheduler.SearchDeadline;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Host cancellation reaches a request on this replica, whether its calculation is attached before or after the cancel. */
class RunningRequestsTest {
    private static final String ID = "3b1f6c1e-2a7d-4f0e-8c52-9a1d7e6b4c21";

    @Test void aCancelEndsTheAttachedDeadline() {
        var requests = new RunningRequests();
        var running = requests.start("acme", ID);
        var deadline = new SearchDeadline(Required.value(Duration.ofSeconds(5)));
        running.attach(deadline);
        assertTrue(requests.cancel("acme", ID));
        assertTrue(running.cancelled());
        assertThrows(SearchDeadline.Expired.class, deadline::requireTime);
    }

    @Test void aCancelBeforeTheCalculationStartsEndsItWhenItAttaches() {
        var requests = new RunningRequests();
        var running = requests.start("acme", ID);
        assertTrue(requests.cancel("acme", ID));
        var deadline = new SearchDeadline(Required.value(Duration.ofSeconds(5)));
        running.attach(deadline);
        assertThrows(SearchDeadline.Expired.class, deadline::requireTime);
    }

    @Test void onlyTheTenantsOwnRunningRequestIsFound() {
        var requests = new RunningRequests();
        var running = requests.start("acme", ID);
        assertFalse(requests.cancel("other", ID));
        assertFalse(requests.cancel("acme", "00000000-0000-4000-8000-000000000000"));
        assertFalse(running.cancelled());
        requests.finish("acme", ID, running);
        assertFalse(requests.cancel("acme", ID), "a finished request is not found");
        assertEquals(0, requests.size());
    }

    @Test void aSecondStartOfTheSameRequestIsRefusedAndAStaleFinishRemovesNothing() {
        var requests = new RunningRequests();
        var first = requests.start("acme", ID);
        assertThrows(RunningRequests.AlreadyRunning.class, () -> requests.start("acme", ID));
        requests.finish("acme", ID, first);
        var second = requests.start("acme", ID);
        requests.finish("acme", ID, first);
        assertEquals(1, requests.size(), "finishing the earlier run leaves the later one registered");
        requests.finish("acme", ID, second);
        assertEquals(0, requests.size());
    }

    @Test void theEndpointAnswersAcceptedNotFoundOrInvalid() {
        var requests = new RunningRequests();
        var cancellation = new RequestCancellation(requests);
        var running = requests.start("acme", ID);
        assertEquals(400, java.util.Objects.requireNonNull(cancellation.cancel("acme", Required.value(ID.toUpperCase(java.util.Locale.ROOT)))).status());
        assertEquals(400, java.util.Objects.requireNonNull(cancellation.cancel("acme", "not-a-uuid")).status());
        assertFalse(running.cancelled(), "an invalid ID cancels nothing");
        assertNull(cancellation.cancel("acme", ID));
        assertTrue(running.cancelled());
        var missing = java.util.Objects.requireNonNull(cancellation.cancel("acme", "00000000-0000-4000-8000-000000000000"));
        assertEquals(404, missing.status());
        assertTrue(missing.json().contains("\"NOT_FOUND\""));
    }
}
