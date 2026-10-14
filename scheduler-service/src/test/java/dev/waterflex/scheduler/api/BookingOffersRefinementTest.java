package dev.waterflex.scheduler.api;

import dev.waterflex.scheduler.BookingCalculation;
import dev.waterflex.scheduler.CalculationFixture;
import dev.waterflex.scheduler.CalculationTransport;
import dev.waterflex.scheduler.HttpCalculation;
import dev.waterflex.scheduler.MetroRouting;
import dev.waterflex.scheduler.Required;
import dev.waterflex.scheduler.RoadClient;
import dev.waterflex.scheduler.SearchAdmission;
import dev.waterflex.scheduler.BookingSnapshot;
import dev.waterflex.scheduler.BoundedBookingSearch;
import dev.waterflex.scheduler.SearchDeadline;
import dev.waterflex.scheduler.api.PublicResponses.Refinement;
import dev.waterflex.scheduler.optimizer.SchedulingPolicy;
import java.util.function.UnaryOperator;
import java.time.Clock;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * A refinement that is unavailable or invalid keeps the insertion offers. The public searchComplete is false only when the
 * deadline or a failure stopped a pass the offers need, and the refinement outcome says how the second pass ended.
 */
class BookingOffersRefinementTest {
    /** Above the fixture's distinct regular windows: the refinement is required. */
    private static final int REQUIRED = 1000;
    /** Below them: the insertion pass found enough windows and the refinement is optional. */
    private static final int OPTIONAL = 0;

    @Test void unavailableRefinementKeepsInsertionWitnessesWithExplicitIncompleteness() {
        var failing = failingRefinement(REQUIRED, () -> new HttpCalculation.Unavailable("Injected optional transport failure", null));
        var searched = failing.offers.calculate(failing.roads, failing.input.snapshot(), failing.input.request());

        assertEquals(2, failing.stages.get(), "Insertion ran, then the refinement was attempted");
        assertFalse(searched.result().complete());
        assertEquals("REFINEMENT_UNAVAILABLE", searched.result().stopReason());
        assertFalse(searched.result().candidates().isEmpty(), "Insertion offers survive the failed refinement");
        BookingCalculation.validate(searched.snapshot(), failing.input.request(), searched.result());
        assertEquals(0, failing.offers.invalidRefinements());
        assertEquals(Refinement.UNAVAILABLE, searched.refinement());
        assertTrue(searched.refinementRequired());
        assertFalse(searched.complete(), "a required refinement that failed leaves the search incomplete");
    }

    @Test void invalidRefinementKeepsInsertionWitnessesAndIsCounted() {
        var failing = failingRefinement(REQUIRED, () -> new IllegalArgumentException("Returned fairness mismatch"));
        var searched = failing.offers.calculate(failing.roads, failing.input.snapshot(), failing.input.request());

        assertEquals(2, failing.stages.get(), "Insertion ran, then the refinement was attempted");
        assertFalse(searched.result().complete());
        assertEquals("REFINEMENT_INVALID", searched.result().stopReason());
        assertFalse(searched.result().candidates().isEmpty(), "Insertion offers survive the invalid refinement");
        BookingCalculation.validate(searched.snapshot(), failing.input.request(), searched.result());
        assertEquals(1, failing.offers.invalidRefinements());
        assertEquals(Refinement.UNAVAILABLE, searched.refinement());
        assertFalse(searched.complete());
    }

    @Test void anOptionalRefinementThatFailsLeavesTheSearchComplete() {
        var failing = failingRefinement(OPTIONAL, () -> new HttpCalculation.Unavailable("Injected optional transport failure", null));
        var searched = failing.offers.calculate(failing.roads, failing.input.snapshot(), failing.input.request());

        assertFalse(searched.refinementRequired(), "the insertion pass found more windows than the threshold");
        assertEquals(Refinement.UNAVAILABLE, searched.refinement());
        assertTrue(searched.complete(), "the insertion pass was all the offers needed");
        assertFalse(searched.result().overtimeAuthorized(), "the internal result still records the cut-short refinement");
    }

    @Test void aRefinementCutShortByItsCapIsReportedShortenedAndOnlyMattersWhenRequired() {
        UnaryOperator<BookingCalculation.Output> capped = output -> {
            var result = output.result();
            return new BookingCalculation.Output(new BoundedBookingSearch.Result(result.candidates(), result.coverage(), false,
                    result.distinctRegularWindows(), result.confirmedRegularMinutes(), result.regularCapacityMinutes(), false, "REFINEMENT_TIME_LIMIT"),
                    output.neighborhoods(), output.evaluatedRoutes(), output.reusedRoutes(), output.prunedArrangements(),
                    output.reconstructionAttempts(), output.reconstructionEvaluations());
        };
        var optional = refining(OPTIONAL, "BOUNDED", capped);
        var searched = optional.offers.calculate(optional.roads, optional.input.snapshot(), optional.input.request());
        assertEquals(Refinement.SHORTENED, searched.refinement());
        assertTrue(searched.complete(), "the optional refinement's cap does not make the search incomplete");

        var required = refining(REQUIRED, "BOUNDED", capped);
        searched = required.offers.calculate(required.roads, required.input.snapshot(), required.input.request());
        assertEquals(Refinement.SHORTENED, searched.refinement());
        assertFalse(searched.complete(), "a required refinement that did not finish leaves the search incomplete");
    }

    @Test void aRefinementThatFinishesIsComplete() {
        for (int threshold : new int[] {OPTIONAL, REQUIRED}) {
            var fixture = refining(threshold, "BOUNDED", output -> output);
            var searched = fixture.offers.calculate(fixture.roads, fixture.input.snapshot(), fixture.input.request());
            assertEquals(2, fixture.stages.get());
            assertEquals(Refinement.COMPLETE, searched.refinement(), "threshold " + threshold);
            assertTrue(searched.complete(), "threshold " + threshold);
        }
    }

    @Test void theInsertionVariantSkipsTheRefinementAndIsCompleteWhenItsPassIs() {
        var fixture = refining(REQUIRED, "INSERTION", output -> output);
        var searched = fixture.offers.calculate(fixture.roads, fixture.input.snapshot(), fixture.input.request());
        assertEquals(1, fixture.stages.get(), "no refinement ran");
        assertEquals(Refinement.SKIPPED, searched.refinement());
        assertTrue(searched.complete());
    }

    @Test void anInsertionPassEndedByTheDeadlineSkipsTheRefinementAndIsIncomplete() {
        var input = withThreshold(CalculationFixture.booking(), OPTIONAL);
        var stages = new AtomicInteger();
        var transport = mock(CalculationTransport.class, invocation -> {
            stages.incrementAndGet();
            BookingCalculation.Input stage = Required.value(invocation.getArgument(0));
            // The engine ends its pass at the deadline by returning what it found so far.
            return new SearchDeadline(Required.value(java.time.Duration.ofNanos(1))).within(() -> BookingCalculation.run(stage));
        });
        var fixture = fixture(input, transport, stages, "BOUNDED");
        var searched = fixture.offers.calculate(fixture.roads, input.snapshot(), input.request());
        assertEquals("DEADLINE", searched.result().stopReason());
        assertEquals(1, stages.get(), "no refinement ran");
        assertEquals(Refinement.SKIPPED, searched.refinement());
        assertFalse(searched.complete());
    }

    @Test void anInvalidInsertionStillFailsTheSearch() {
        var input = CalculationFixture.booking();
        var transport = mock(CalculationTransport.class, _ -> { throw new IllegalArgumentException("Returned booking metrics do not match caller validation"); });
        var fixture = fixture(input, transport, new AtomicInteger(), "BOUNDED");
        assertThrows(IllegalArgumentException.class, () -> fixture.offers.calculate(fixture.roads, input.snapshot(), input.request()));
        assertEquals(0, fixture.offers.invalidRefinements(), "Only refinement failures are tolerated and counted");
    }

    private record Fixture(BookingCalculation.Input input, BookingOffers offers, RoadClient roads, AtomicInteger stages) { }

    private static Fixture failingRefinement(int threshold, Supplier<RuntimeException> failure) {
        var input = withThreshold(CalculationFixture.booking(), threshold);
        var stages = new AtomicInteger();
        var transport = mock(CalculationTransport.class, invocation -> {
            stages.incrementAndGet();
            BookingCalculation.Input stage = Required.value(invocation.getArgument(0));
            if (stage.stage() == BookingCalculation.Stage.REFINEMENT) throw Required.value(failure.get());
            return BookingCalculation.run(stage);
        });
        return fixture(input, transport, stages, "BOUNDED");
    }

    /** Runs both stages in the engine, passing the refinement's output through {@code refinement}. */
    private static Fixture refining(int threshold, String variant, UnaryOperator<BookingCalculation.Output> refinement) {
        var input = withThreshold(CalculationFixture.booking(), threshold);
        var stages = new AtomicInteger();
        var transport = mock(CalculationTransport.class, invocation -> {
            stages.incrementAndGet();
            BookingCalculation.Input stage = Required.value(invocation.getArgument(0));
            var output = BookingCalculation.run(stage);
            return stage.stage() == BookingCalculation.Stage.REFINEMENT ? refinement.apply(output) : output;
        });
        return fixture(input, transport, stages, variant);
    }

    private static BookingCalculation.Input withThreshold(BookingCalculation.Input input, int threshold) {
        var host = input.snapshot();
        var policy = host.policy();
        var snapshot = new BookingSnapshot(host.metroId(), host.capturedAt(), host.configurationFingerprint(), host.routingIdentity(),
                new SchedulingPolicy.Rules(threshold, policy.fairnessAllowance(), policy.bookingDeadlineMs()), host.rates(), host.days(), host.horizon());
        return new BookingCalculation.Input(snapshot, input.request(), input.stage(), input.variant(), input.insertion(), input.refinementMillis());
    }

    private static Fixture fixture(BookingCalculation.Input input, CalculationTransport transport, AtomicInteger stages, String variant) {
        var snapshot = input.snapshot();
        var roads = mock(RoadClient.class, invocation -> invocation.getMethod().getName().equals("activeIdentity")
                ? snapshot.routingIdentity() : org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation));
        var offers = new BookingOffers(mock(PublicApiStore.class), mock(BookingStore.class), mock(MetroRouting.class),
                mock(DailyPreparation.AddressLocator.class), transport, new SearchAdmission(2, 16), new RunningRequests(),
                variant, 1000, Required.value(Clock.systemUTC()));
        return new Fixture(input, offers, Required.value(roads), stages);
    }
}
