package dev.waterflex.scheduler;

import dev.waterflex.scheduler.BookingSnapshot.*;
import dev.waterflex.scheduler.optimizer.DayPlan;
import dev.waterflex.scheduler.optimizer.SchedulingPolicy;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.TreeMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReservedOvertimeTest {
    @Test void regularReservationRetainsOvertimeTransferredFromExistingWork() {
        var captured = Required.value(Instant.parse("2026-10-23T12:00:00Z"));
        var date = Required.value(BookingService.bookingDates(captured).getFirst());
        var start = Required.value(date.atStartOfDay(ZoneOffset.UTC).toInstant().plusSeconds(14 * 3600));
        var hour = Required.value(start.plusSeconds(3600));
        var twoHours = Required.value(start.plusSeconds(7200));
        var threeHours = Required.value(start.plusSeconds(10800));
        var point = new RoadClient.Point(41.25, -95.93);
        var a = new Technician("a", start, hour, 180, 120, Required.value(Set.of("pinned", "old", "new")), Required.value(List.of()), point, point, 1);
        var b = new Technician("b", start, threeHours, 180, 0, Required.value(Set.of("old")), Required.value(List.of()), point, point, 1);
        var pinned = new Visit("pinned", "pinned-job", "pinned", start, twoHours, 60, point, "a", start, false);
        var old = new Visit("old", "old-job", "old", hour, threeHours, 120, point, "a", hour, false);
        var added = new Visit("new-job", "new-job", "new", hour, threeHours, 30, point, "a", hour, false);
        Map<String, DayPlan.RoadLeg> legs = new HashMap<>();
        for (String from : List.of("a", "b", "pinned", "old", "new-job", "appointment"))
            for (String to : List.of("a:return", "b:return", "pinned", "old", "new-job", "appointment"))
                legs.put(from + ">" + to, new DayPlan.RoadLeg(0, 0));
        var roads = new Roads(legs, Required.value(Set.of()));
        var rates = new Rates(30, 45, 0, 0, 0);
        var day = new Day(Required.value(Map.of("a", a, "b", b)), Required.value(Map.of("pinned", pinned, "old", old)),
                new Arrangement(Required.value(Map.of("a", List.of("pinned", "old"), "b", List.of()))), 0, roads);
        var proposal = new Arrangement(Required.value(Map.of("a", List.of("pinned", "new-job"), "b", List.of("old"))));
        var proposedFacts = Required.value(Map.of("pinned", pinned, "old", old, "new-job", added));
        var before = day.evaluate(day.baseline(), day.visits(), rates);
        var after = day.evaluate(proposal, proposedFacts, rates);
        assertTrue(before.feasible()); assertTrue(after.feasible());
        assertEquals(120, before.overtimeMinutes()); assertEquals(30, after.overtimeMinutes());
        var candidate = new BoundedBookingSearch.Candidate(new BoundedBookingSearch.Window(date, hour, threeHours), "a", proposal,
                after.overtimeMinutes() - before.overtimeMinutes(), after.costCents() - before.costCents(), Required.value(BigDecimal.ZERO), 1, 1, "REARRANGEMENT", after);
        Map<LocalDate, Day> days = new TreeMap<>();
        for (LocalDate item : BookingService.bookingDates(captured)) days.put(item, item.equals(date) ? day : new Day(
                Required.value(Map.of()), Required.value(Map.of()), new Arrangement(Required.value(Map.of())), 0, roads));
        var snapshot = new BookingSnapshot("metro", captured, "configuration", "roads", SchedulingPolicy.Rules.defaults(), rates, days);
        var result = new BoundedBookingSearch.Result(Required.value(List.of(candidate)), Required.value(List.of()), true, 1, 60, 240, false, "COMPLETED");
        var bundle = ReservationOffers.prepare(snapshot, new BoundedBookingSearch.Request("new-job", "new", 30, point),
                Required.value(Map.of(date, Map.of())), result, Required.value(captured.plusSeconds(600)), new BookingOfferLimit("4"), () -> { });
        assertEquals(1, bundle.offers().size());
        var offered = Required.value(bundle.offers().getFirst());
        assertFalse(offered.overtimeAuthorized());
        var common = Required.value(bundle.dates().get(date));
        for (boolean reassignmentAlreadyApplied : List.of(false, true)) {
            Map<String, Visit> visits = new TreeMap<>(common.day().visits());
            if (reassignmentAlreadyApplied) visits.put("old", new Visit(old.id(), old.jobId(), old.serviceId(), old.windowStart(), old.windowEnd(),
                    old.durationMinutes(), old.location(), "b", old.plannedStart(), false));
            Day current = new Day(common.day().technicians(), visits, common.day().baseline(), 1, common.day().roads());
            var routing = mock(SnapshotRouting.class, _ -> current);
            var transition = new ReservationTransition(routing);
            var facts = new BookingSnapshotLoader.Facts("metro", captured, "configuration", "roads", snapshot.policy(), rates,
                    Required.value(Map.of(date, current)), Required.value(Map.of(date, common.state().holds())));
            // Unknown historical metrics do not become an invented regular authorization.
            assertThrows(ResponseStatusException.class, () -> transition.prepare(facts, "new-job",
                    new ReservationTransition.Confirmation(offered.holdId(), "appointment")));
            assertThrows(ResponseStatusException.class, () -> transition.prepare(facts, "new-job",
                    new ReservationTransition.Confirmation(offered.holdId(), "appointment", 1)),
                    "A positive persisted delta does not establish a regular reservation");
            var confirmed = Required.value(transition.prepare(facts, "new-job", new ReservationTransition.Confirmation(
                    offered.holdId(), "appointment", Math.toIntExact(candidate.overtimeDelta()))).get(date));
            assertTrue(confirmed.validation().feasible());
            assertEquals(30, confirmed.validation().overtimeMinutes());
            assertTrue(confirmed.holds().isEmpty());
        }
    }

    @Test void reservedAuthorizationSurvivesDemandDropButLimitsAndAuthorizationRemainHard() {
        for (boolean authorized : List.of(false, true)) for (int limit : List.of(10, 60)) {
            var date = Required.value(LocalDate.parse("2026-10-26"));
            var start = Required.value(Instant.parse("2026-10-26T14:00:00Z"));
            var end = Required.value(start.plusSeconds(7200));
            var point = new RoadClient.Point(41.25, -95.93);
            var technician = new Technician("tech", start, end, 180, limit, Required.value(Set.of("service")),
                    Required.value(List.of()), point, point, 1);
            var visit = new Visit("hold", "job", "service", end, Required.value(end.plusSeconds(7200)), 30, point, "tech", end, true);
            var roads = new Roads(Required.value(Map.of("tech>hold", new DayPlan.RoadLeg(60, 100),
                    "hold>tech:return", new DayPlan.RoadLeg(60, 100), "tech>appointment", new DayPlan.RoadLeg(60, 100),
                    "appointment>tech:return", new DayPlan.RoadLeg(60, 100))), Required.value(Set.of()));
            var day = new Day(Required.value(Map.of("tech", technician)), Required.value(Map.of("hold", visit)),
                    new Arrangement(Required.value(Map.of("tech", List.of("hold")))), 1, roads);
            var hold = new ReservationState.Hold("job", "offer", Required.value(start.plusSeconds(600)), authorized);
            var policy = SchedulingPolicy.Rules.defaults();
            assertFalse(policy.authorizeOvertime(0, 0, 120, true), "Demand has fallen below the booking authorization threshold");
            var facts = new BookingSnapshotLoader.Facts("metro", start, "configuration", "roads", policy,
                    new Rates(30, 45, 0, 0, 0), Required.value(Map.of(date, day)), Required.value(Map.of(date, Map.of("hold", hold))));
            var routing = mock(SnapshotRouting.class, invocation -> {
                assertEquals("arrangements", invocation.getMethod().getName());
                return day;
            });
            var transition = new ReservationTransition(routing);
            if (authorized && limit == 60) {
                var result = Required.value(transition.prepare(facts, "job", new ReservationTransition.Confirmation("hold", "appointment")).get(date));
                assertTrue(result.validation().feasible()); assertTrue(result.validation().overtimeMinutes() > 0);
                assertTrue(result.holds().isEmpty());
                assertFalse(Required.value(result.day().visits().get("appointment")).reservation());
            } else assertThrows(ResponseStatusException.class,
                    () -> transition.prepare(facts, "job", new ReservationTransition.Confirmation("hold", "appointment")));
        }
    }
}
