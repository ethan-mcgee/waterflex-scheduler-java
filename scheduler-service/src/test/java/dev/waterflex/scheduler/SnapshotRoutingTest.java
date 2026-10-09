package dev.waterflex.scheduler;

import dev.waterflex.scheduler.BookingSnapshot.*;
import dev.waterflex.scheduler.optimizer.SchedulingPolicy;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;

class SnapshotRoutingTest {
    private static final Instant CAPTURED = Required.value(Instant.parse("2026-10-25T17:00:00Z"));
    private static final LocalDate DATE = Required.value(LocalDate.parse("2026-10-26"));
    private static final Instant START = Required.value(Instant.parse("2026-10-26T14:00:00Z"));
    private static final Instant END = Required.value(START.plusSeconds(8 * 3600));
    private static final Rates RATES = new Rates(30, 45, 0, 0, 0);
    private static RoadPoint point(int value) { return new RoadPoint(41 + value * .01, -95); }

    private static final class DirectedRoads extends RoadClient {
        final List<Pair> requested = new ArrayList<>();
        DirectedRoads() { super(new JdbcTemplate(), "unused", 100, 60, org.mockito.Mockito.mock(org.springframework.transaction.PlatformTransactionManager.class)); }
        @Override public Map<String, Leg> sparse(List<Pair> pairs, String identity) {
            assertEquals("roads", identity);
            Map<String, Leg> result = new HashMap<>();
            for (Pair pair : pairs) {
                requested.add(pair);
                // One direction is unreachable; its reverse remains routable.
                if (pair.origin().equals(point(1)) && pair.destination().equals(point(4))) continue;
                result.put(pair.id(), new Leg(seconds(pair.origin(), pair.destination()), 100));
            }
            return result;
        }
        static long seconds(RoadPoint from, RoadPoint to) { return Math.round((from.lat() - 40) * 100 + (to.lat() - 40) * 10); }
    }

    @Test void insertionLoadsConfirmedShortcutsAndPreservesDirectedUnreachableLegs() {
        var roads = new DirectedRoads();
        var routing = new SnapshotRouting(roads);
        BookingSnapshot original = snapshot();
        var request = new BoundedBookingSearch.Request("new", "service", 15, point(4));
        var routed = routing.insertion(original, request);
        Day day = Required.value(routed.days().get(DATE));
        assertTrue(day.roads().unreachable().contains("a>new"));
        assertEquals(DirectedRoads.seconds(point(4), point(1)), Required.value(day.roads().legs().get("new>a:return")).seconds());
        assertTrue(day.roads().legs().containsKey("first>last"));
        assertDoesNotThrow(() -> day.plan(day.baseline(), day.visits(), RATES, true));
        assertTrue(Required.value(original.days().get(DATE)).roads().legs().isEmpty());
        int count = roads.requested.size();
        routing.insertion(routed, request);
        assertEquals(count, roads.requested.size(), "Known legs and known unreachable pairs must not be requested again");
        assertDoesNotThrow(() -> new BoundedBookingSearch(routed, request, BoundedBookingSearch.Limits.defaults(), () -> { }).search(false));
    }

    @Test void neighborhoodAndRemovalFetchRequiredCrossRouteAndShortcutLegs() {
        var roads = new DirectedRoads();
        var routing = new SnapshotRouting(roads);
        var request = new BoundedBookingSearch.Request("new", "service", 15, point(4));
        var inserted = routing.insertion(snapshot(), request);
        Map<LocalDate, Set<String>> selected = new TreeMap<>();
        selected.put(DATE, Required.value(Set.of("a", "b")));
        var routed = routing.neighborhoods(inserted, selected);
        Day day = Required.value(routed.days().get(DATE));
        Map<String, List<String>> routes = new TreeMap<>();
        routes.put("a", Required.value(List.of("hold")));
        routes.put("b", Required.value(List.of("last", "first")));
        var moved = new Arrangement(routes);
        assertDoesNotThrow(() -> day.plan(moved, day.visits(), RATES, false));
        assertDoesNotThrow(() -> day.plan(moved, day.visits(), RATES, true));
        Map<String, Visit> remaining = new HashMap<>(day.visits());
        remaining.remove("hold");
        routes.put("a", Required.value(List.of()));
        var removed = new Arrangement(routes);
        Day lifecycle = routing.arrangements(DATE, day, remaining, Required.value(List.<Arrangement>of(removed)), "roads");
        assertDoesNotThrow(() -> lifecycle.evaluate(removed, remaining, RATES));
    }

    @ParameterizedTest @ValueSource(ints = {1, 2, 4})
    void offerBundleReservesSiblingAlternativesTogetherWithoutMutatingConfirmedAssignments(int limit) {
        var routing = new SnapshotRouting(new DirectedRoads());
        var request = new BoundedBookingSearch.Request("new", "service", 15, point(3));
        var snapshot = routing.insertion(snapshot(), request);
        var result = new BoundedBookingSearch(snapshot, request, BoundedBookingSearch.Limits.defaults(), () -> { }).search(false);
        Map<LocalDate, Map<String, ReservationState.Hold>> holds = new TreeMap<>();
        Instant expiry = Required.value(CAPTURED.plusSeconds(600));
        for (LocalDate date : snapshot.days().keySet()) holds.put(date, date.equals(DATE)
                ? Required.value(Map.<String, ReservationState.Hold>of("hold", new ReservationState.Hold("hold-job", "old-offer", expiry, false))) : Required.value(Map.of()));
        var bundle = ReservationOffers.prepare(snapshot, request, holds, result, expiry, limit, () -> { });
        assertEquals(limit, bundle.offers().size());
        assertTrue(result.complete());
        assertTrue(result.distinctRegularWindows() > 2, "The display cap must not establish overtime scarcity");
        assertFalse(result.overtimeAuthorized());
        var full = ReservationOffers.prepare(snapshot, request, holds, result, expiry, 4, () -> { });
        assertEquals(full.offers().stream().limit(limit).map(offer -> offer.candidate()).toList(),
                bundle.offers().stream().map(offer -> offer.candidate()).toList(), "Reduced limits preserve candidate ranking");
        var common = Required.value(bundle.dates().get(DATE));
        assertEquals(1 + limit, common.state().holds().size());
        assertEquals(3 + limit, common.day().visits().size());
        assertTrue(common.validation().feasible());
        assertEquals(0, common.validation().overtimeMinutes());
        assertEquals(limit, bundle.offers().stream().map(offer -> offer.candidate().window()).distinct().count());
        Set<String> siblingIds = new HashSet<>();
        bundle.offers().forEach(offer -> siblingIds.add(offer.holdId()));
        assertEquals(Required.value(snapshot.days().get(DATE)).baseline(), ReservationOffers.without(common.day().baseline(), siblingIds));
        assertTrue(bundle.offers().stream().noneMatch(offer -> offer.overtimeAuthorized()));
        Map<LocalDate, Day> reservedDays = new TreeMap<>(); reservedDays.put(DATE, common.day());
        Map<LocalDate, Map<String, ReservationState.Hold>> reservedHolds = new TreeMap<>(); reservedHolds.put(DATE, common.state().holds());
        var facts = new ReservationTransition.Facts(snapshot.metroId(), CAPTURED, snapshot.configurationFingerprint(), snapshot.routingIdentity(),
                snapshot.policy(), RATES, reservedDays, reservedHolds);
        var transition = new ReservationTransition(routing);
        var selected = Required.value(bundle.offers().getFirst());
        var confirmed = Required.value(transition.prepare(facts, "new", new ReservationTransition.Confirmation(selected.holdId(), "appointment")).get(DATE));
        assertEquals(4, confirmed.day().visits().size());
        assertEquals(1, confirmed.holds().size());
        assertFalse(Required.value(confirmed.day().visits().get("appointment")).reservation());
        assertEquals(selected.visit().windowStart(), Required.value(confirmed.day().visits().get("appointment")).windowStart());
        assertTrue(confirmed.validation().feasible());
        assertEquals(3 + limit, common.day().visits().size(), "Preparing confirmation must not mutate the reservation snapshot");
        var released = Required.value(transition.prepare(facts, "new", null).get(DATE));
        assertEquals(Required.value(snapshot.days().get(DATE)).baseline(), released.day().baseline());
        assertEquals(1, released.holds().size());
        assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> transition.prepare(facts, "new", new ReservationTransition.Confirmation("missing", "appointment")));
    }

    private static BookingSnapshot snapshot() {
        Map<String, Technician> technicians = new TreeMap<>();
        for (String id : List.of("a", "b")) technicians.put(id, new Technician(Required.value(id), START, END, 480, 60,
                Required.value(Set.of("service")), Required.value(List.of()), point(id.equals("a") ? 1 : 2), point(id.equals("a") ? 1 : 2), 0));
        Map<String, Visit> visits = new TreeMap<>();
        for (String id : List.of("first", "hold", "last")) visits.put(id, new Visit(Required.value(id), id + "-job", "service", START, END,
                15, point(id.equals("first") ? 5 : id.equals("hold") ? 6 : 7), "a", START, id.equals("hold")));
        Map<String, List<String>> routes = new TreeMap<>();
        routes.put("a", Required.value(List.of("first", "hold", "last"))); routes.put("b", Required.value(List.of()));
        var emptyRoads = new Roads(Required.value(Map.of()), Required.value(Set.of()));
        Day populated = new Day(technicians, visits, new Arrangement(routes), 0, emptyRoads);
        Day empty = new Day(Required.value(Map.of()), Required.value(Map.of()), new Arrangement(Required.value(Map.of())), 0, emptyRoads);
        Map<LocalDate, Day> days = new TreeMap<>();
        for (LocalDate date : BookingCalendar.bookingDates(CAPTURED)) days.put(date, date.equals(DATE) ? populated : empty);
        return new BookingSnapshot("metro", CAPTURED, "config", "roads", SchedulingPolicy.Rules.defaults(), RATES, days);
    }
}
