package dev.waterflex.scheduler.api;

import dev.waterflex.scheduler.BookingSnapshot;
import dev.waterflex.scheduler.CalculationJson;
import dev.waterflex.scheduler.Required;
import dev.waterflex.scheduler.RoadPoint;
import dev.waterflex.scheduler.api.BookingDayState.HoldState;
import dev.waterflex.scheduler.api.BookingDayState.TechnicianState;
import dev.waterflex.scheduler.api.BookingReconciliation.Ending;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** The reconciliation rules of "Booking (Decided 2026-10-08)": a hold stands exactly as stored or is reported lost. */
class BookingReconciliationTest {
    private static final Instant NOW = Required.value(Instant.parse("2026-10-11T15:00:00Z"));
    private static final Instant DAY = Required.value(Instant.parse("2026-10-12T13:00:00Z"));
    private static final RoadPoint HOME = new RoadPoint(41.25, -95.93);

    private static BookingSnapshot.Technician technician(String id, long version) {
        return new BookingSnapshot.Technician(id, DAY, Required.value(DAY.plusSeconds(9 * 3600)), 540, 0, Required.value(java.util.Set.of("service")),
                Required.value(List.of()), HOME, HOME, version);
    }

    private static BookingSnapshot.Visit appointment(String id, String technician, int hour) {
        Instant start = Required.value(DAY.plusSeconds(hour * 3600L));
        return new BookingSnapshot.Visit(id, id, "service", start, Required.value(start.plusSeconds(4 * 3600)), 30, HOME, technician, start, false);
    }

    /** A host day: technician ID to version and appointment IDs in order (each at the hour of its position). */
    private static BookingSnapshot.Day host(Map<String, Long> versions, Map<String, List<String>> routes) {
        Map<String, BookingSnapshot.Technician> technicians = new TreeMap<>();
        Map<String, BookingSnapshot.Visit> visits = new TreeMap<>();
        versions.forEach((id, version) -> technicians.put(id, technician(Required.value(id), version)));
        routes.forEach((technician, ids) -> {
            for (int i = 0; i < ids.size(); i++) visits.put(ids.get(i), appointment(Required.value(ids.get(i)), Required.value(technician), i));
        });
        return new BookingSnapshot.Day(technicians, visits, new BookingSnapshot.Arrangement(routes), 0,
                new BookingSnapshot.Roads(Required.value(Map.of()), Required.value(java.util.Set.of())));
    }

    private static HoldState hold(String job, String technician, Instant expiresAt) {
        return new HoldState(job, "offer-" + job, "set-" + job, expiresAt, "service", DAY, Required.value(DAY.plusSeconds(7200)), 60, 41.26, -95.94, DAY, technician);
    }

    private static final Instant LATER = Required.value(NOW.plusSeconds(300));

    /**
     * Stored: tech-a [a1, hold-x], tech-b [b1] with a pending move of c1 from tech-c, tech-c [] and tech-d [d1], all at
     * version 1. The host expects a1 on tech-a, b1 on tech-b, c1 on tech-c and d1 on tech-d.
     */
    private static BookingDayState stored() {
        Map<String, TechnicianState> technicians = new TreeMap<>();
        technicians.put("tech-a", new TechnicianState(1L, list("a1"), list("a1", "hold-x")));
        technicians.put("tech-b", new TechnicianState(1L, list("b1"), list("b1", "c1")));
        technicians.put("tech-c", new TechnicianState(1L, list("c1"), list()));
        technicians.put("tech-d", new TechnicianState(1L, list("d1"), list("d1")));
        return new BookingDayState(technicians, Required.value(Map.of("hold-x", hold("job-x", "tech-a", LATER))));
    }

    private static List<String> list(String... ids) { return Required.value(List.of(ids)); }

    private static Map<String, Long> versions(long a, long b, long c, long d) {
        Map<String, Long> versions = new TreeMap<>();
        versions.put("tech-a", a); versions.put("tech-b", b); versions.put("tech-c", c); versions.put("tech-d", d);
        return versions;
    }

    private static Map<String, List<String>> routes(List<String> a, List<String> b, List<String> c, List<String> d) {
        Map<String, List<String>> routes = new LinkedHashMap<>();
        routes.put("tech-a", a); routes.put("tech-b", b); routes.put("tech-c", c); routes.put("tech-d", d);
        return routes;
    }

    private static BookingSnapshot.Day unchanged() { return host(versions(1, 1, 1, 1), routes(list("a1"), list("b1"), list("c1"), list("d1"))); }

    @Test void aDayWithNothingStoredIsExactlyTheHostDay() {
        BookingSnapshot.Day host = unchanged();
        var result = BookingReconciliation.reconcile(null, host, "job-new", NOW);
        assertSame(host, result.day());
        assertEquals(BookingDayState.of(host), result.state());
        assertTrue(result.holds().isEmpty() && result.ended().isEmpty());
    }

    @Test void unchangedTechnicianDaysKeepEveryHoldAndPendingMove() {
        var result = BookingReconciliation.reconcile(stored(), unchanged(), "job-new", NOW);
        assertEquals(stored(), result.state());
        assertEquals(java.util.Set.of("hold-x"), result.holds().keySet());
        assertEquals(list("b1", "c1"), result.day().baseline().routes().get("tech-b"), "the pending move stays");
        assertTrue(Required.value(result.day().visits().get("hold-x")).reservation());
        assertEquals("tech-c", Required.value(result.day().visits().get("c1")).originalTechnicianId(), "the host still has c1 on tech-c");
        assertTrue(result.ended().isEmpty());
    }

    @Test void expiredHoldsAndTheRequestingJobsOwnHoldsEndNormally() {
        assertEquals(Map.of("hold-x", Ending.EXPIRED), BookingReconciliation.reconcile(stored(), unchanged(), "job-new", Required.value(LATER.plusSeconds(1))).ended());
        var refreshed = BookingReconciliation.reconcile(stored(), unchanged(), "job-x", NOW);
        assertEquals(Map.of("hold-x", Ending.SUPERSEDED), refreshed.ended());
        assertEquals(list("a1"), refreshed.state().technicians().get("tech-a") == null ? null : Required.value(refreshed.state().technicians().get("tech-a")).route());
        assertEquals(list("b1", "c1"), Required.value(refreshed.state().technicians().get("tech-b")).route(), "moves made for an ended hold stay, as in the portal");
    }

    @Test void aChangedTechnicianDayWithTheExpectedAppointmentsIsAccepted() {
        var result = BookingReconciliation.reconcile(stored(), host(versions(7, 8, 1, 1), routes(list("a1"), list("b1"), list("c1"), list("d1"))), "job-new", NOW);
        assertEquals(java.util.Set.of("hold-x"), result.holds().keySet());
        assertEquals(7L, Required.value(result.state().technicians().get("tech-a")).version());
        assertEquals(8L, Required.value(result.state().technicians().get("tech-b")).version());
    }

    @Test void aDispatcherChangeToAFreeRouteReplacesOnlyThatRoute() {
        var result = BookingReconciliation.reconcile(stored(), host(versions(1, 1, 1, 9), routes(list("a1"), list("b1"), list("c1"), list("d2", "d1"))), "job-new", NOW);
        assertTrue(result.ended().isEmpty());
        assertEquals(new TechnicianState(9L, list("d2", "d1"), list("d2", "d1")), result.state().technicians().get("tech-d"));
        assertEquals(java.util.Set.of("hold-x"), result.holds().keySet());
    }

    @Test void aDispatcherMoveBetweenTwoFreeRoutesReplacesBoth() {
        Map<String, TechnicianState> technicians = new TreeMap<>(stored().technicians());
        technicians.put("tech-e", new TechnicianState(1L, list("e1"), list("e1")));
        var stored = new BookingDayState(technicians, stored().holds());
        Map<String, Long> versions = versions(1, 1, 1, 5); versions.put("tech-e", 5L);
        Map<String, List<String>> routes = routes(list("a1"), list("b1"), list("c1"), list("d1", "e1")); routes.put("tech-e", list());
        var result = BookingReconciliation.reconcile(stored, host(versions, routes), "job-new", NOW);
        assertTrue(result.ended().isEmpty());
        assertEquals(list("d1", "e1"), Required.value(result.state().technicians().get("tech-d")).route());
        assertEquals(list(), Required.value(result.state().technicians().get("tech-e")).route());
    }

    @Test void aDispatcherChangeToARouteAHoldOrAPendingMoveDependsOnLosesEveryHold() {
        for (BookingSnapshot.Day host : List.of(
                host(versions(2, 1, 1, 1), routes(list("a2", "a1"), list("b1"), list("c1"), list("d1"))),
                host(versions(1, 1, 2, 1), routes(list("a1"), list("b1"), list("c1", "c2"), list("d1"))),
                host(versions(1, 2, 1, 1), routes(list("a1"), list(), list("c1"), list("d1", "b1"))))) {
            var result = BookingReconciliation.reconcile(stored(), host, "job-new", NOW);
            assertEquals(Map.of("hold-x", Ending.LOST), result.ended());
            assertEquals(BookingDayState.of(host), result.state(), "the day restarts from the host's routes");
            assertTrue(result.holds().isEmpty());
        }
    }

    @Test void technicianDaysThatAppearOrDisappearFollowTheSameRule() {
        Map<String, Long> versions = versions(1, 1, 1, 1); versions.put("tech-n", 3L);
        Map<String, List<String>> routes = routes(list("a1"), list("b1"), list("c1"), list("d1")); routes.put("tech-n", list("n1"));
        var added = BookingReconciliation.reconcile(stored(), host(versions, routes), "job-new", NOW);
        assertEquals(new TechnicianState(3L, list("n1"), list("n1")), added.state().technicians().get("tech-n"));
        assertTrue(added.ended().isEmpty());
        Map<String, Long> withoutD = versions(1, 1, 1, 1); withoutD.remove("tech-d");
        Map<String, List<String>> routesWithoutD = routes(list("a1"), list("b1"), list("c1"), list()); routesWithoutD.remove("tech-d");
        var dropped = BookingReconciliation.reconcile(stored(), host(withoutD, routesWithoutD), "job-new", NOW);
        assertFalse(dropped.state().technicians().containsKey("tech-d"), "a skipped free technician-day leaves the arrangement");
        assertTrue(dropped.ended().isEmpty());
        Map<String, Long> withoutA = versions(1, 1, 1, 1); withoutA.remove("tech-a");
        Map<String, List<String>> routesWithoutA = routes(list(), list("b1"), list("c1"), list("d1")); routesWithoutA.remove("tech-a");
        assertEquals(Map.of("hold-x", Ending.LOST), BookingReconciliation.reconcile(stored(), host(withoutA, routesWithoutA), "job-new", NOW).ended(),
                "a hold's technician-day leaving loses the day's holds");
    }

    @Test void anArrangementThatNoLongerFitsLosesEveryRemainingHold() {
        var reconciled = BookingReconciliation.reconcile(stored(), unchanged(), "job-x", NOW);
        var stillHeld = BookingReconciliation.reconcile(stored(), unchanged(), "job-new", NOW);
        assertEquals(Map.of("hold-x", Ending.LOST), BookingReconciliation.infeasible(stillHeld, unchanged()).ended());
        assertEquals(Map.of("hold-x", Ending.SUPERSEDED), BookingReconciliation.infeasible(reconciled, unchanged()).ended(), "an ended hold keeps its ending");
    }

    @Test void theStoredStateIsValidatedAndRoundTrips() {
        BookingDayState state = stored();
        assertEquals(state, PublicRequests.read(CalculationJson.write(state), BookingDayState.class));
        Map<String, TechnicianState> twice = new TreeMap<>(state.technicians());
        twice.put("tech-d", new TechnicianState(1L, list("d1", "a1"), list("d1", "a1")));
        assertThrows(IllegalArgumentException.class, () -> new BookingDayState(twice, state.holds()));
        Map<String, TechnicianState> unplaced = new TreeMap<>(state.technicians());
        unplaced.put("tech-a", new TechnicianState(1L, list("a1"), list("a1")));
        assertThrows(IllegalArgumentException.class, () -> new BookingDayState(unplaced, state.holds()));
        assertThrows(IllegalArgumentException.class, () -> new BookingDayState(state.technicians(),
                Required.value(Map.of("hold-x", hold("job-x", "tech-b", LATER)))), "a hold must be on its technician's route");
        List<String> missing = new ArrayList<>(list("a1"));
        Map<String, TechnicianState> uncovered = new TreeMap<>(state.technicians());
        uncovered.put("tech-c", new TechnicianState(1L, Required.value(List.copyOf(missing)), list()));
        assertThrows(IllegalArgumentException.class, () -> new BookingDayState(uncovered, state.holds()));
        assertThrows(IllegalArgumentException.class, () -> PublicRequests.read(Required.value(CalculationJson.write(state).replace("\"version\":1", "\"version\":-1")), BookingDayState.class));
    }
}
