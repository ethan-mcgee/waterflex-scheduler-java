package dev.waterflex.scheduler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.waterflex.scheduler.BookingSnapshot.Arrangement;
import dev.waterflex.scheduler.optimizer.RouteEvaluator;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReservationStateTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test void restartRoundTripPreservesArrangementTimingsAndOvertimeAuthorization() {
        ReservationState state = state();
        ReservationState restored = ReservationState.decode(json, state.encode(json));
        assertEquals(state, restored);
        assertTrue(Required.value(restored.holds().get("hold")).overtimeAuthorized());
        assertThrows(UnsupportedOperationException.class, () -> restored.scheduleVersions().clear());
        assertThrows(UnsupportedOperationException.class, () -> Required.value(restored.segments().get("tech")).clear());
    }

    @Test void actualNullAndMalformedPersistedFieldsNeverBecomeDefaults() throws Exception {
        for (String field : List.of("format", "configurationFingerprint", "routingIdentity", "scheduleVersions", "routes", "holds", "segments")) {
            ObjectNode root = root();
            root.putNull(field);
            assertThrows(ResponseStatusException.class, () -> ReservationState.decode(json, Required.value(root.toString())), field);
        }
        for (String bad : List.of("null", "{}", "[]", "{", "\"state\""))
            assertThrows(ResponseStatusException.class, () -> ReservationState.decode(json, Required.value(bad)));
        ObjectNode missingAuthorization = root();
        ((ObjectNode) missingAuthorization.at("/holds/hold")).remove("overtimeAuthorized");
        assertThrows(ResponseStatusException.class, () -> ReservationState.decode(json, Required.value(missingAuthorization.toString())));
        ObjectNode stringAuthorization = root();
        ((ObjectNode) stringAuthorization.at("/holds/hold")).put("overtimeAuthorized", "false");
        assertThrows(ResponseStatusException.class, () -> ReservationState.decode(json, Required.value(stringAuthorization.toString())));
        ObjectNode missingVersion = root();
        ((ObjectNode) missingVersion.path("scheduleVersions")).remove("tech");
        assertThrows(ResponseStatusException.class, () -> ReservationState.decode(json, Required.value(missingVersion.toString())));
        ObjectNode negativeVersion = root();
        ((ObjectNode) negativeVersion.path("scheduleVersions")).put("tech", -1);
        assertThrows(ResponseStatusException.class, () -> ReservationState.decode(json, Required.value(negativeVersion.toString())));
    }

    @Test void incompleteOrReorderedSegmentCoverageAndInvalidTimesAreRejected() throws Exception {
        ObjectNode missing = root();
        ((ObjectNode) missing.path("segments")).putArray("tech");
        assertThrows(ResponseStatusException.class, () -> ReservationState.decode(json, Required.value(missing.toString())));
        ObjectNode reordered = root();
        ((ObjectNode) reordered.at("/segments/tech/0")).putArray("visitIds").add("hold").add("appointment");
        assertThrows(ResponseStatusException.class, () -> ReservationState.decode(json, Required.value(reordered.toString())));
        ObjectNode backwards = root();
        ((ObjectNode) backwards.at("/segments/tech/0")).put("returnedAt", "2026-10-26T12:00:00Z");
        assertThrows(ResponseStatusException.class, () -> ReservationState.decode(json, Required.value(backwards.toString())));
        ObjectNode blank = root();
        ((ObjectNode) blank.path("routes")).putArray("tech").add("");
        assertThrows(ResponseStatusException.class, () -> ReservationState.decode(json, Required.value(blank.toString())));
    }

    @Test void reservationLocksCannotRunOutsideCommitTransaction() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ReservationStore store = new ReservationStore(jdbc);
        assertThrows(IllegalStateException.class, () -> store.lock("metro", Required.value(List.of(LocalDate.parse("2026-10-26")))));
        verifyNoInteractions(jdbc);
    }

    private ObjectNode root() throws Exception { return (ObjectNode) Required.value(json.readTree(state().encode(json))); }
    private static ReservationState state() {
        Instant departure = Required.value(Instant.parse("2026-10-26T14:00:00Z"));
        List<String> visits = Required.value(List.of("appointment", "hold"));
        var segment = new RouteEvaluator.WorkingSegment(departure, Required.value(departure.plusSeconds(7200)), visits);
        return new ReservationState("configuration", "roads", Required.value(Map.of("tech", 2L)),
                new Arrangement(Required.value(Map.of("tech", visits))),
                Required.value(Map.<String, ReservationState.Hold>of("hold", new ReservationState.Hold("job", "offer", Required.value(departure.plusSeconds(600)), true))),
                Required.value(Map.<String, List<RouteEvaluator.WorkingSegment>>of("tech", List.<RouteEvaluator.WorkingSegment>of(segment))));
    }
}
