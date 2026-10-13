package dev.waterflex.scheduler.api;

import dev.waterflex.scheduler.CalculationJson;
import dev.waterflex.scheduler.Required;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** A stored hold records where its job's coordinates came from; a hold stored before that has no source, never HOST. */
class BookingDayStateSourceTest {
    private static final Instant DAY = Required.value(Instant.parse("2026-10-12T13:00:00Z"));

    private static String stored(PublicTypes.@Nullable CoordinateSource source) {
        var hold = new BookingDayState.HoldState("job-1", "offer-1", "set-1", DAY, "service", DAY, Required.value(DAY.plusSeconds(7200)), 60,
                41.26, -95.94, DAY, "tech-1", source);
        var state = new BookingDayState(Required.value(Map.of("tech-1", new BookingDayState.TechnicianState(1L, Required.value(List.of()), Required.value(List.of("hold-1"))))),
                Required.value(Map.of("hold-1", hold)));
        return CalculationJson.write(state);
    }

    private static PublicTypes.@Nullable CoordinateSource read(String json) {
        return Required.value(PublicRequests.read(json, BookingDayState.class).holds().get("hold-1")).source();
    }

    @Test void aRecordedSourceIsKept() {
        assertEquals(PublicTypes.CoordinateSource.HOST, read(stored(PublicTypes.CoordinateSource.HOST)));
        assertEquals(PublicTypes.CoordinateSource.NOMINATIM, read(stored(PublicTypes.CoordinateSource.NOMINATIM)));
    }

    @Test void aHoldStoredBeforeSourcesHasNoneAndIsNotReadAsHost() {
        ObjectNode state = (ObjectNode) CalculationJson.tree(stored(PublicTypes.CoordinateSource.HOST));
        ((ObjectNode) state.path("holds").path("hold-1")).remove("source");
        assertNull(read(CalculationJson.write(state)));
    }

    @Test void anUnknownSourceIsRejected() {
        ObjectNode state = (ObjectNode) CalculationJson.tree(stored(PublicTypes.CoordinateSource.HOST));
        ((ObjectNode) state.path("holds").path("hold-1")).put("source", "GPS");
        assertThrows(IllegalArgumentException.class, () -> read(CalculationJson.write(state)));
    }

    @Test void movingAHoldToAnotherTechnicianKeepsItsSource() {
        var hold = new BookingDayState.HoldState("job-1", "offer-1", "set-1", DAY, "service", DAY, Required.value(DAY.plusSeconds(7200)), 60,
                41.26, -95.94, DAY, "tech-1", PublicTypes.CoordinateSource.NOMINATIM);
        assertEquals(PublicTypes.CoordinateSource.NOMINATIM, hold.on("tech-2").source());
        assertEquals("tech-2", hold.on("tech-2").technicianId());
    }
}
