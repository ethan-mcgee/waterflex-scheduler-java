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
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** A remote refinement that is unavailable keeps the insertion offers and says the search is incomplete. */
class BookingOffersRefinementTest {
    @Test void unavailableRefinementKeepsInsertionWitnessesWithExplicitIncompleteness() {
        var input = CalculationFixture.booking();
        var snapshot = input.snapshot();
        var stages = new AtomicInteger();
        var transport = mock(CalculationTransport.class, invocation -> {
            stages.incrementAndGet();
            BookingCalculation.Input stage = Required.value(invocation.getArgument(0));
            if (stage.stage() == BookingCalculation.Stage.REFINEMENT)
                throw new HttpCalculation.Unavailable("Injected optional transport failure", null);
            return BookingCalculation.run(stage);
        });
        var roads = mock(RoadClient.class, invocation -> invocation.getMethod().getName().equals("activeIdentity")
                ? snapshot.routingIdentity() : org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation));
        var offers = new BookingOffers(mock(PublicApiStore.class), mock(BookingStore.class), mock(MetroRouting.class),
                mock(DailyPreparation.AddressLocator.class), transport, new SearchAdmission(2, 16), mock(JdbcTemplate.class),
                "BOUNDED", 1000, Required.value(Clock.systemUTC()));

        var searched = offers.calculate(roads, snapshot, input.request());

        assertEquals(2, stages.get(), "Insertion ran, then the refinement was attempted");
        assertFalse(searched.result().complete());
        assertEquals("REFINEMENT_UNAVAILABLE", searched.result().stopReason());
        assertFalse(searched.result().candidates().isEmpty(), "Insertion offers survive the failed refinement");
        BookingCalculation.validate(searched.snapshot(), input.request(), searched.result());
    }
}
