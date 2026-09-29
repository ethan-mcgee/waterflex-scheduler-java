package dev.waterflex.scheduler;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.waterflex.scheduler.BookingSnapshot.*;
import dev.waterflex.scheduler.optimizer.DayPlan;
import dev.waterflex.scheduler.optimizer.SchedulingPolicy;
import dev.waterflex.scheduler.optimizer.TechRoute;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Sequential customer promises with a separate exhaustive timing and route-order oracle. */
class FieldScenarioTest {
    static final Instant CAPTURED = Required.value(Instant.parse("2026-10-25T17:00:00Z"));
    static final LocalDate DATE = Required.value(LocalDate.parse("2026-10-26"));
    static final Instant START = ScheduleCutoff.localMinute(DATE, 480, false);
    static final RoadClient.Point OMAHA = new RoadClient.Point(41.2565, -95.9345);
    static final RoadClient.Point TOWN = new RoadClient.Point(41.4417, -96.4981);
    static final Rates RATES = new Rates(30, 45, .67, 0, 0);

    @Test void allBookingOrdersAndPromiseWidthsAreIndependentlyChecked() throws Exception {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (var order : List.of(List.of("A", "B", "C"), List.of("A", "C", "B"), List.of("B", "A", "C"),
                List.of("B", "C", "A"), List.of("C", "A", "B"), List.of("C", "B", "A")))
          for (int width : List.of(120, 240)) for (String variant : List.of("INSERTION", "BOUNDED", "EXPANDED", "RUIN_RECREATE", "SHARED")) {
            Day day = fixture(false, true, false, false);
            for (String id : order) {
                var request = new BoundedBookingSearch.Request(Required.value(id), "service", 60, id.equals("B") ? OMAHA : TOWN);
                var snapshot = snapshot(day);
                var engine = engine(snapshot, request, width, Required.value(variant));
                long started = System.nanoTime();
                var result = engine.search(!variant.equals("INSERTION"));
                var selected = Required.value(BoundedBookingSearch.choose(result.candidates(), snapshot.policy()));
                Map<String, Visit> facts = new TreeMap<>(day.visits());
                facts.put(id, new Visit(Required.value(id), Required.value(id), "service", selected.window().start(), selected.window().end(), 60,
                        request.location(), selected.technicianId(), Required.value(selected.validation().arrivals().get(id)), false));
                var fixed = Required.value(SmallCaseOracle.evaluate(day, selected.arrangement(), facts, RATES));
                assertEquals(fixed.costCents(), selected.validation().costCents(), "Independent timing and cost");
                long optimum = Long.MAX_VALUE;
                for (var window : engine.windows()) {
                    Map<String, Visit> possible = new TreeMap<>(day.visits());
                    possible.put(id, new Visit(Required.value(id), Required.value(id), "service", window.start(), window.end(), 60,
                            request.location(), selected.technicianId(), window.start(), false));
                    var exact = SmallCaseOracle.best(day, possible, RATES);
                    if (exact != null) optimum = Math.min(optimum, exact.costCents());
                }
                assertEquals(optimum, fixed.costCents(), "Small-town fixture must attain independently established best cost");
                assertEquals(0, selected.validation().overtimeMinutes());
                var cleanup = Required.value(SmallCaseOracle.best(day, day.visits(), RATES));
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("scenario", "small-town-backtracking"); row.put("bookingOrder", order); row.put("request", id);
                row.put("promiseMinutes", width); row.put("variant", variant); row.put("source", selected.source());
                row.put("searchMillis", (System.nanoTime() - started) / 1_000_000); row.put("costCents", fixed.costCents());
                row.put("oracleCostCents", optimum); row.put("noNewRequestOracleCostCents", cleanup.costCents());
                row.put("routes", selected.arrangement().routes()); row.put("drivingMinutes", selected.validation().driveMinutes());
                row.put("waitingMinutes", selected.validation().waitingMinutes()); row.put("meters", selected.validation().meters());
                row.put("overtimeMinutes", selected.validation().overtimeMinutes()); row.put("crossings", crossings(selected.arrangement()));
                row.put("candidateEvaluations", result.coverage().stream().mapToInt(c -> c.candidateEvaluations()).sum());
                row.put("routeEvaluations", engine.evaluatedRoutes()); row.put("reconstructionAttempts", engine.reconstructionAttempts());
                row.put("reconstructionEvaluations", engine.reconstructionEvaluations()); row.put("stopReason", result.stopReason());
                List<Map<String, Object>> visits = new ArrayList<>();
                facts.values().forEach(visit -> visits.add(Map.of("id", visit.id(), "windowStart", Required.value(visit.windowStart().toString()), "windowEnd", Required.value(visit.windowEnd().toString()),
                        "planned", Required.value(Required.value(selected.validation().arrivals().get(visit.id())).toString()), "serviceMinutes", visit.durationMinutes(), "location", visit.location())));
                row.put("visits", visits);
                row.put("segments", selected.validation().segments().entrySet().stream().map(entry -> Map.of("technician", entry.getKey(), "segments",
                        entry.getValue().stream().map(segment -> Map.of("departure", Required.value(segment.departure().toString()), "return", Required.value(segment.returnedAt().toString()), "order", segment.visitIds())).toList())).toList());
                row.put("endpoints", day.technicians().values().stream().map(t -> Map.of("id", t.id(), "departure", t.departure(), "return", t.returnTo())).toList());
                rows.add(row);
                // Customer confirmation fixes the promise, while internal order and arrival may change next time.
                Map<String, Visit> confirmed = new TreeMap<>();
                selected.arrangement().routes().forEach((tech, route) -> route.forEach(stop -> {
                    var visit = Required.value(facts.get(stop));
                    confirmed.put(stop, new Visit(visit.id(), visit.jobId(), visit.serviceId(), visit.windowStart(), visit.windowEnd(), visit.durationMinutes(),
                            visit.location(), Required.value(tech), Required.value(selected.validation().arrivals().get(stop)), false));
                }));
                day = new Day(day.technicians(), confirmed, selected.arrangement(), day.reservationVersion() + 1, day.roads());
            }
        }
        Path target = Required.value(Path.of("target", "field-scenarios.json")); Files.createDirectories(Required.value(target.getParent()));
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(target.toFile(), Map.of("format", 1, "routingIdentity", "directed-small-town-fixture-v1",
                "operationalEvidence", false, "rates", RATES, "rows", rows));
    }

    @Test void skillsReturnTravelAbsencesAndDirectedUnreachableLegsRemainHard() {
        for (boolean nearbyQualified : List.of(false, true)) {
            Day day = fixture(true, nearbyQualified, false, false);
            var request = new BoundedBookingSearch.Request("A", "service", 60, TOWN);
            var result = engine(snapshot(day), request, 240, "SHARED").search(true);
            var chosen = Required.value(BoundedBookingSearch.choose(result.candidates(), SchedulingPolicy.Rules.defaults()));
            assertEquals(nearbyQualified ? "nearby" : "omaha", chosen.technicianId());
        }
        Day split = fixture(false, true, true, false);
        assertTrue(engine(snapshot(split), new BoundedBookingSearch.Request("A", "service", 240, TOWN), 240, "SHARED").search(true).candidates().isEmpty());
        Day unreachable = fixture(false, true, false, true);
        assertTrue(engine(snapshot(unreachable), new BoundedBookingSearch.Request("A", "service", 60, TOWN), 240, "BOUNDED").search(true).candidates().isEmpty());
        Day regular = fixture(false, true, false, false);
        assertTrue(engine(snapshot(regular), new BoundedBookingSearch.Request("A", "service", 420, TOWN), 240, "SHARED").search(true).candidates().isEmpty(), "Service plus directed return exceeds eight hours");
    }
    @Test void fourHourPolicyAndOverflowKeepWeekendsAndDstCalendarBoundaries() {
        var engine = new BoundedBookingSearch(snapshot(fixture(false, true, false, false)), new BoundedBookingSearch.Request("A", "service", 60, TOWN), BoundedBookingSearch.Limits.defaults(), () -> { });
        assertEquals(5, engine.windows().size());
        assertTrue(engine.windows().stream().allMatch(window -> Duration.between(window.start(), window.end()).toMinutes() == 240));
        var capture = Required.value(Instant.parse("2026-10-23T17:00:00Z"));
        assertTrue(BookingService.bookingDates(capture).contains(LocalDate.parse("2026-11-01")));
        assertEquals(List.of(LocalDate.parse("2026-11-09"), LocalDate.parse("2026-11-10"), LocalDate.parse("2026-11-11"), LocalDate.parse("2026-11-12"), LocalDate.parse("2026-11-13")), BookingService.overflowDates(capture));
    }
    static BoundedBookingSearch engine(BookingSnapshot snapshot, BoundedBookingSearch.Request request, int width, String variant) {
        var engine = new BoundedBookingSearch(snapshot, request, variant.equals("INSERTION") || variant.equals("BOUNDED") ? BoundedBookingSearch.Limits.defaults() : BoundedBookingSearch.Limits.expanded(), () -> { }, width);
        if (variant.equals("SHARED") || variant.equals("RUIN_RECREATE")) engine.withRuinRecreate();
        if (variant.equals("EXPANDED") || variant.equals("RUIN_RECREATE")) engine.withoutSharedWindowEvaluation();
        return engine;
    }
    static BookingSnapshot snapshot(Day day) {
        Map<LocalDate, Day> days = new TreeMap<>();
        for (LocalDate date : BookingService.bookingDates(CAPTURED)) days.put(date, date.equals(DATE) ? day : new Day(Required.value(Map.of()), Required.value(Map.of()), new Arrangement(Required.value(Map.of())), 0, new Roads(Required.value(Map.of()), Required.value(Set.of()))));
        return new BookingSnapshot("Omaha", CAPTURED, "field-v2", "directed-small-town-fixture-v1", SchedulingPolicy.Rules.defaults(), RATES, days);
    }
    static Day fixture(boolean second, boolean qualified, boolean absence, boolean unreachable) {
        Map<String, Technician> techs = new TreeMap<>(); Map<String, List<String>> routes = new TreeMap<>();
        techs.put("omaha", new Technician("omaha", START, Required.value(START.plusSeconds(480 * 60)), 480, 60, Required.value(Set.of("service")),
                absence ? Required.value(List.of(new TechRoute.Unavailable(Required.value(START.plusSeconds(180 * 60)), Required.value(START.plusSeconds(300 * 60))))) : Required.value(List.of()), OMAHA, OMAHA, 0));
        if (second) techs.put("nearby", new Technician("nearby", START, Required.value(START.plusSeconds(480 * 60)), 480, 0,
                qualified ? Required.value(Set.of("service")) : Required.value(Set.of("other")), Required.value(List.of()), TOWN, TOWN, 0));
        techs.keySet().forEach(id -> routes.put(id, new ArrayList<>()));
        Map<String, DayPlan.RoadLeg> roads = new TreeMap<>(); Set<String> missing = new TreeSet<>();
        for (String from : List.of("omaha", "nearby", "A", "B", "C")) for (String to : List.of("omaha:return", "nearby:return", "A", "B", "C")) {
            int minutes = rural(Required.value(from)) == rural(Required.value(to)) ? from.equals(to) ? 0 : 5 : rural(Required.value(from)) ? 45 : 40;
            if (unreachable && to.equals("omaha:return") && rural(Required.value(from))) missing.add(from + ">" + to);
            else roads.put(from + ">" + to, new DayPlan.RoadLeg(minutes * 60L, minutes * 800L));
        }
        return new Day(techs, Required.value(Map.of()), new Arrangement(routes), 0, new Roads(roads, missing));
    }
    private static boolean rural(String id) { return id.equals("A") || id.equals("C") || id.startsWith("nearby"); }
    private static int crossings(Arrangement arrangement) {
        int count = 0;
        for (var route : arrangement.routes().entrySet()) { String previous = Required.value(route.getKey()); for (String stop : route.getValue()) {
            if (rural(previous) != rural(Required.value(stop))) count++; previous = Required.value(stop);
        } if (!route.getValue().isEmpty() && rural(previous) != rural(route.getKey() + ":return")) count++; }
        return count;
    }
}
