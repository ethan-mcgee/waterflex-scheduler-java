package dev.waterflex.scheduler.api;

import dev.waterflex.scheduler.BookingCalculation;
import dev.waterflex.scheduler.BookingSnapshot;
import dev.waterflex.scheduler.CalculationJson;
import dev.waterflex.scheduler.Required;
import dev.waterflex.scheduler.RoadClient;
import dev.waterflex.scheduler.RoadPoint;
import dev.waterflex.scheduler.SnapshotRouting;
import dev.waterflex.scheduler.api.PublicRequests.BookingOffersRequest;
import dev.waterflex.scheduler.optimizer.SchedulingPolicy;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** Booking search facts built from a public request: horizon coverage, mapping, skips and contradictions. */
class RequestBookingTest {
    private static final Instant CAPTURED = Required.value(Instant.parse("2026-10-11T12:00:00Z"));
    private static final LocalDate DAY = Required.value(LocalDate.parse("2026-10-12"));
    private static final DailyPreparation.AddressLocator NO_GEOCODING = _ -> null;

    /** The spec's booking example, with coordinates for appt-8 and any change applied. */
    private static BookingOffersRequest request(Consumer<ObjectNode> change) {
        ObjectNode request = object(CalculationJson.tree(PublicApiContractTest.example("BookingOffersRequest")));
        object(appointments(request).get(1)).putObject("location").put("lat", 41.2587).put("lng", -95.9378);
        change.accept(request);
        return PublicRequests.read(CalculationJson.write(request), BookingOffersRequest.class);
    }

    private static ObjectNode object(@Nullable JsonNode node) {
        assertInstanceOf(ObjectNode.class, node);
        return (ObjectNode) Required.value(node);
    }

    /** A lambda parameter carries no nullness annotation, so the helpers accept one and check it. */
    private static ObjectNode snapshot(@Nullable ObjectNode request) { return object(Required.value(request).get("snapshot")); }

    private static ArrayNode appointments(@Nullable ObjectNode request) { return (ArrayNode) Required.value(snapshot(request).get("appointments")); }

    private static RequestBooking.Built build(BookingOffersRequest request) {
        return RequestBooking.build(request, SchedulingPolicy.Rules.defaults(), "omaha-map-v7", CAPTURED, NO_GEOCODING);
    }

    @Test void everyHorizonDateIsCoveredAndTheHostFactsMapExactly() {
        var built = build(request(request -> object(request.get("horizon")).put("lastDate", "2026-10-14")));
        BookingSnapshot snapshot = built.snapshot();
        assertEquals(Set.of(DAY, Required.value(DAY.plusDays(1)), Required.value(DAY.plusDays(2))), snapshot.days().keySet());
        assertEquals(snapshot.days().keySet(), snapshot.horizon());
        BookingSnapshot.Day empty = Required.value(snapshot.days().get(DAY.plusDays(1)));
        assertTrue(empty.technicians().isEmpty() && empty.visits().isEmpty(), "a date with no technician-day offers nothing");
        BookingSnapshot.Day day = Required.value(snapshot.days().get(DAY));
        BookingSnapshot.Technician tech2 = Required.value(day.technicians().get("tech-2"));
        assertEquals(Instant.parse("2026-10-12T13:00:00Z"), tech2.shiftStart());
        assertEquals(Instant.parse("2026-10-12T22:00:00Z"), tech2.shiftEnd());
        assertEquals(540, tech2.maxDailyMinutes());
        assertEquals(0, tech2.maxOvertimeMinutes(), "overtime is never assigned");
        assertEquals(Set.of("softener-service"), tech2.services());
        assertEquals(1, tech2.absences().size());
        assertEquals(new RoadPoint(41.2864, -96.2345), tech2.departure());
        assertEquals(RequestBooking.version(Required.value(Instant.parse("2026-10-10T16:30:00Z"))), tech2.scheduleVersion());
        assertEquals(Map.of("tech-1", List.of("appt-7", "appt-8"), "tech-2", List.of()), day.baseline().routes());
        assertEquals(day.actualArrangement(), day.baseline());
        BookingSnapshot.Visit appt8 = Required.value(day.visits().get("appt-8"));
        assertEquals("tech-1", appt8.originalTechnicianId());
        assertEquals(Instant.parse("2026-10-12T17:30:00Z"), appt8.plannedStart());
        assertFalse(appt8.reservation());
        assertEquals(new RoadPoint(41.2587, -95.9378), appt8.location());
        assertEquals(new dev.waterflex.scheduler.BoundedBookingSearch.Request("job-311", "softener-install", 90, new RoadPoint(41.2350, -96.0420)), built.request());
        assertEquals(new java.math.BigDecimal("0.02"), snapshot.policy().fairnessAllowance(), "the client's fairness budget");
        assertEquals(List.of(), built.skipped());
    }

    @Test void theFactsSearchAndValidateInTheBookingEngine() {
        var built = build(request(_ -> { }));
        RoadClient roads = mock(RoadClient.class, invocation -> {
            if (!invocation.getMethod().getName().equals("sparse")) throw new AssertionError("Unexpected routing call " + invocation.getMethod().getName());
            List<RoadClient.Pair> pairs = Required.value(invocation.getArgument(0));
            Map<String, RoadClient.Leg> legs = new HashMap<>();
            for (RoadClient.Pair pair : pairs) legs.put(pair.id(), pair.origin().equals(pair.destination()) ? new RoadClient.Leg(0, 0) : new RoadClient.Leg(600, 8000));
            return legs;
        });
        BookingSnapshot routed = new SnapshotRouting(roads).insertion(built.snapshot(), built.request());
        var input = new BookingCalculation.Input(routed, built.request(), BookingCalculation.Stage.INSERTION, "INSERTION",
                Required.value(Set.of()), null, 0);
        var result = BookingCalculation.run(input).result();
        assertTrue(result.complete());
        assertFalse(result.candidates().isEmpty());
        for (var candidate : result.candidates()) assertEquals("tech-1", candidate.technicianId(), "only tech-1 installs softeners");
        BookingCalculation.validate(routed, built.request(), result);
        var decoded = dev.waterflex.scheduler.BookingDataset.parse(dev.waterflex.scheduler.BookingDataset.encode(input, dev.waterflex.scheduler.BookingDataset.Encoding.SPARSE));
        assertEquals(routed.horizon(), decoded.snapshot().horizon(), "the remote solver receives the host horizon");
        assertEquals(routed.days(), decoded.snapshot().days());
    }

    @Test void daysMustCoverExactlyTheDeclaredHorizon() {
        BookingSnapshot snapshot = build(request(request -> object(request.get("horizon")).put("lastDate", "2026-10-13"))).snapshot();
        assertThrows(BookingSnapshot.Incomplete.class, () -> new BookingSnapshot(snapshot.metroId(), snapshot.capturedAt(), snapshot.calendarReference(),
                snapshot.configurationFingerprint(), snapshot.routingIdentity(), snapshot.policy(), snapshot.rates(), snapshot.days(), Required.value(Set.of(DAY))));
        assertThrows(BookingSnapshot.Incomplete.class, () -> new BookingSnapshot(snapshot.metroId(), snapshot.capturedAt(), snapshot.calendarReference(),
                snapshot.configurationFingerprint(), snapshot.routingIdentity(), snapshot.policy(), snapshot.rates(), snapshot.days()),
                "the portal constructor still requires the scheduler calendar");
    }

    @Test void anUnlocatableTechnicianDayIsLeftOutAndAnUnlocatableJobIsRefused() {
        var built = build(request(request -> object(appointments(request).get(1)).putObject("location").putObject("address")
                .put("line1", "1200 Example St").put("city", "Omaha").put("state", "NE").put("postalCode", "68102")));
        BookingSnapshot.Day day = Required.value(built.snapshot().days().get(DAY));
        assertEquals(Set.of("tech-2"), day.technicians().keySet());
        assertTrue(day.visits().isEmpty());
        assertEquals(1, built.skipped().size());
        var skipped = Required.value(built.skipped().getFirst());
        assertEquals("tech-1", skipped.technicianId());
        assertTrue(skipped.message().contains("appt-8"), skipped.message());
        assertThrows(RequestBooking.JobUnlocatable.class, () -> build(request(request -> object(request.get("job")).putObject("location").putObject("address")
                .put("line1", "1 Main St").put("city", "Omaha").put("state", "NE").put("postalCode", "68102"))));
    }

    @Test void sequenceAndPlannedStartMustAgree() {
        var contradictory = request(request -> object(appointments(request).get(1)).put("plannedStart", "2026-10-12T08:00:00-05:00"));
        var failure = assertThrows(RequestBooking.Contradictory.class, () -> build(contradictory));
        assertTrue(Required.value(failure.getMessage()).contains("tech-1"));
    }

    @Test void theHorizonIsCappedAndTheJobCannotReuseAnotherIdentity() {
        assertDoesNotThrow(() -> request(request -> object(request.get("horizon")).put("lastDate", "2026-11-01")), "21 dates");
        assertThrows(IllegalArgumentException.class, () -> request(request -> object(request.get("horizon")).put("lastDate", "2026-11-02")), "22 dates");
        assertThrows(IllegalArgumentException.class, () -> request(request -> object(request.get("job")).put("id", "appt-7")));
        assertThrows(IllegalArgumentException.class, () -> request(request -> object(request.get("job")).put("id", "tech-1")));
        assertThrows(IllegalArgumentException.class, () -> request(request -> object(request.get("horizon")).put("firstDate", "2026-10-13")),
                "a technician-day outside the horizon");
    }

    @Test void theScheduleVersionIsTheExactLastModifiedInstant() {
        assertEquals(1_760_216_657_123_456_789L, RequestBooking.version(Required.value(Instant.parse("2025-10-11T21:04:17.123456789Z"))));
        assertThrows(IllegalArgumentException.class, () -> RequestBooking.version(Required.value(Instant.parse("1969-12-31T23:59:59Z"))));
    }
}
