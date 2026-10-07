package dev.waterflex.scheduler;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class BookingRefinementTransportTest {
    @Test void transportOrValidationFailurePreservesInsertionWitnessesWithExplicitIncompleteness() {
        for (boolean corrupt : List.of(false,true)) {
            var input = CalculationFixture.booking(); var snapshot = input.snapshot();
            Map<LocalDate,Map<String,ReservationState.Hold>> holds = new TreeMap<>(); snapshot.days().keySet().forEach(date -> holds.put(date,Required.value(Map.of())));
            var stages = new AtomicInteger(); var loads = new AtomicInteger();
            var loader = mock(BookingSnapshotLoader.class,invocation -> {
                if (invocation.getMethod().getName().equals("load")) { loads.incrementAndGet(); return new BookingSnapshotLoader.Loaded(snapshot,holds); }
                return org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation);
            });
            var roads = mock(RoadClient.class,invocation -> invocation.getMethod().getName().equals("activeIdentity")
                    ? snapshot.routingIdentity() : org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation));
            var routing = mock(SnapshotRouting.class,_ -> snapshot);
            var transport = mock(CalculationTransport.class,invocation -> {
                stages.incrementAndGet(); BookingCalculation.Input stage = Required.value(invocation.getArgument(0));
                if (stage.stage() == BookingCalculation.Stage.REFINEMENT) {
                    if (corrupt) throw new IllegalArgumentException("Injected invalid remote proposal");
                    throw new HttpCalculation.Unavailable("Injected optional transport failure",null);
                }
                return BookingCalculation.run(stage);
            });
            var pipeline = new BookingSearchPipeline(loader,roads,routing,true,1000,new BookingOfferLimit("1")); pipeline.transport(transport);
            var prepared = new SearchDeadline(Required.value(Duration.ofSeconds(5))).within(() -> pipeline.prepare(snapshot.metroId(),input.request()));
            assertFalse(prepared.search().complete()); assertFalse(prepared.search().candidates().isEmpty());
            assertEquals(corrupt ? "REFINEMENT_INVALID" : "REFINEMENT_UNAVAILABLE",prepared.stopReason());
            assertEquals(1,prepared.reservations().offers().size()); assertFalse(prepared.reservations().offers().getFirst().overtimeAuthorized());
            BookingCalculation.validate(snapshot,input.request(),prepared.search());
            assertEquals(2,stages.get()); assertEquals(1,loads.get());
        }
    }
}
