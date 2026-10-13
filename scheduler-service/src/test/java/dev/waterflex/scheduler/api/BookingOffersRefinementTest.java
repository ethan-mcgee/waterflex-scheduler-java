package dev.waterflex.scheduler.api;

import dev.waterflex.scheduler.BookingCalculation;
import dev.waterflex.scheduler.CalculationFixture;
import dev.waterflex.scheduler.CalculationTransport;
import dev.waterflex.scheduler.HttpCalculation;
import dev.waterflex.scheduler.MetroRouting;
import dev.waterflex.scheduler.Required;
import dev.waterflex.scheduler.RoadClient;
import dev.waterflex.scheduler.SearchAdmission;
import java.time.Clock;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** A remote refinement that is unavailable or invalid keeps the insertion offers and says the search is incomplete. */
class BookingOffersRefinementTest {
    @Test void unavailableRefinementKeepsInsertionWitnessesWithExplicitIncompleteness() {
        var failing = failingRefinement(() -> new HttpCalculation.Unavailable("Injected optional transport failure", null));
        var searched = failing.offers.calculate(failing.roads, failing.input.snapshot(), failing.input.request());

        assertEquals(2, failing.stages.get(), "Insertion ran, then the refinement was attempted");
        assertFalse(searched.result().complete());
        assertEquals("REFINEMENT_UNAVAILABLE", searched.result().stopReason());
        assertFalse(searched.result().candidates().isEmpty(), "Insertion offers survive the failed refinement");
        BookingCalculation.validate(searched.snapshot(), failing.input.request(), searched.result());
        assertEquals(0, failing.offers.invalidRefinements());
    }

    @Test void invalidRefinementKeepsInsertionWitnessesAndIsCounted() {
        var failing = failingRefinement(() -> new IllegalArgumentException("Returned fairness mismatch"));
        var searched = failing.offers.calculate(failing.roads, failing.input.snapshot(), failing.input.request());

        assertEquals(2, failing.stages.get(), "Insertion ran, then the refinement was attempted");
        assertFalse(searched.result().complete());
        assertEquals("REFINEMENT_INVALID", searched.result().stopReason());
        assertFalse(searched.result().candidates().isEmpty(), "Insertion offers survive the invalid refinement");
        BookingCalculation.validate(searched.snapshot(), failing.input.request(), searched.result());
        assertEquals(1, failing.offers.invalidRefinements());
    }

    @Test void anInvalidInsertionStillFailsTheSearch() {
        var input = CalculationFixture.booking();
        var transport = mock(CalculationTransport.class, _ -> { throw new IllegalArgumentException("Returned booking metrics do not match caller validation"); });
        var fixture = fixture(input, transport, new AtomicInteger());
        assertThrows(IllegalArgumentException.class, () -> fixture.offers.calculate(fixture.roads, input.snapshot(), input.request()));
        assertEquals(0, fixture.offers.invalidRefinements(), "Only refinement failures are tolerated and counted");
    }

    private record Fixture(BookingCalculation.Input input, BookingOffers offers, RoadClient roads, AtomicInteger stages) { }

    private static Fixture failingRefinement(Supplier<RuntimeException> failure) {
        var input = CalculationFixture.booking();
        var stages = new AtomicInteger();
        var transport = mock(CalculationTransport.class, invocation -> {
            stages.incrementAndGet();
            BookingCalculation.Input stage = Required.value(invocation.getArgument(0));
            if (stage.stage() == BookingCalculation.Stage.REFINEMENT) throw Required.value(failure.get());
            return BookingCalculation.run(stage);
        });
        return fixture(input, transport, stages);
    }

    private static Fixture fixture(BookingCalculation.Input input, CalculationTransport transport, AtomicInteger stages) {
        var snapshot = input.snapshot();
        var roads = mock(RoadClient.class, invocation -> invocation.getMethod().getName().equals("activeIdentity")
                ? snapshot.routingIdentity() : org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation));
        var offers = new BookingOffers(mock(PublicApiStore.class), mock(BookingStore.class), mock(MetroRouting.class),
                mock(DailyPreparation.AddressLocator.class), transport, new SearchAdmission(2, 16), mock(JdbcTemplate.class),
                "BOUNDED", 1000, Required.value(Clock.systemUTC()));
        return new Fixture(input, offers, Required.value(roads), stages);
    }
}
