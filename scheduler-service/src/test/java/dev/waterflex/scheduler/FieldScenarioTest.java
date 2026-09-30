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
                row.put("endpoints", day.technicians().values().stream().map(t -> Map.of("id", t.id(), "departure", t.departure(), "return", t.returnTo(), "shiftStart", Required.value(t.shiftStart().toString()), "shiftEnd", Required.value(t.shiftEnd().toString()), "maxDailyMinutes", t.maxDailyMinutes(), "maxOvertimeMinutes", t.maxOvertimeMinutes())).toList());
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

    @Test void skillsReturnTravelAbsencesAndDirectedUnreachableLegsRemainHard() throws Exception {
        for (boolean nearbyQualified : List.of(false, true)) {
            Day day = fixture(true, nearbyQualified, false, false);
            var request = new BoundedBookingSearch.Request("A", "service", 60, TOWN);
            var result = engine(snapshot(day), request, 240, "SHARED").search(true);
            var chosen = Required.value(BoundedBookingSearch.choose(result.candidates(), SchedulingPolicy.Rules.defaults()));
            assertEquals(nearbyQualified ? "nearby" : "omaha", chosen.technicianId());
            Map<String, Visit> facts = Required.value(Map.of("A", new Visit("A", "A", "service", chosen.window().start(), chosen.window().end(), 60,
                    TOWN, chosen.technicianId(), chosen.window().start(), false)));
            assertEquals(Required.value(SmallCaseOracle.best(day, facts, RATES)).costCents(), chosen.validation().costCents());
            recordCompanion("nearby-qualified-" + nearbyQualified, day, chosen, facts);
        }
        Day split = fixture(false, true, true, false);
        assertTrue(engine(snapshot(split), new BoundedBookingSearch.Request("A", "service", 240, TOWN), 240, "SHARED").search(true).candidates().isEmpty());
        Day unreachable = fixture(false, true, false, true);
        assertTrue(engine(snapshot(unreachable), new BoundedBookingSearch.Request("A", "service", 60, TOWN), 240, "BOUNDED").search(true).candidates().isEmpty());
        Day regular = fixture(false, true, false, false);
        Map<String, Visit> longVisit = Required.value(Map.of("A", new Visit("A", "A", "service", START, Required.value(START.plusSeconds(240 * 60)),
                420, TOWN, "omaha", Required.value(START.plusSeconds(40 * 60)), false)));
        var legacy = regular.evaluate(new Arrangement(Required.value(Map.of("omaha", List.of("A")))), longVisit, RATES);
        assertTrue(legacy.feasible(), "The historical configured overtime allowance alone permits this route");
        assertEquals(25, legacy.overtimeMinutes());
        assertNull(SmallCaseOracle.best(regular, longVisit, RATES), "No independent zero-overtime schedule exists");
        assertTrue(engine(snapshot(regular), new BoundedBookingSearch.Request("A", "service", 420, TOWN), 240, "SHARED").search(true).candidates().isEmpty(), "Service plus directed return exceeds eight hours");
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(Path.of("target", "field-rejections.json").toFile(), Map.of("rejections", List.of(
                Map.of("scenario", "long-rural-return", "serviceMinutes", 420, "regularMinutes", 480, "maximumPaidMinutes", 600, "legacyOvertimeMinutes", 25, "directedReturnMinutes", 45, "expected", "NO_CANDIDATE"),
                Map.of("scenario", "split-availability", "serviceMinutes", 240, "absenceStart", Required.value(START.plusSeconds(180 * 60).toString()), "absenceEnd", Required.value(START.plusSeconds(300 * 60).toString()), "expected", "NO_CANDIDATE"),
                Map.of("scenario", "unreachable-directed-return", "serviceMinutes", 60, "unreachableLeg", "town > Omaha home", "expected", "NO_CANDIDATE"))));
    }
    @Test void fourHourPolicyAndOverflowKeepWeekendsAndDstCalendarBoundaries() {
        var engine = new BoundedBookingSearch(snapshot(fixture(false, true, false, false)), new BoundedBookingSearch.Request("A", "service", 60, TOWN), BoundedBookingSearch.Limits.defaults(), () -> { });
        assertEquals(5, engine.windows().size());
        assertTrue(engine.windows().stream().allMatch(window -> Duration.between(window.start(), window.end()).toMinutes() == 240));
        var capture = Required.value(Instant.parse("2026-10-23T17:00:00Z"));
        assertTrue(BookingService.bookingDates(capture).contains(LocalDate.parse("2026-11-01")));
        assertEquals(List.of(LocalDate.parse("2026-11-09"), LocalDate.parse("2026-11-10"), LocalDate.parse("2026-11-11"), LocalDate.parse("2026-11-12"), LocalDate.parse("2026-11-13")), BookingService.overflowDates(capture));
    }
    @Test void anOmahaPromiseOrLiveReservationCorrectlyPreventsGroupingUntilReleased() throws Exception {
        for (boolean hold : List.of(false, true)) {
            Day empty = fixture(false, true, false, false);
            var a = new Visit("A", "A", "service", Required.value(START.plusSeconds(40 * 60)), Required.value(START.plusSeconds(60 * 60)), 10, TOWN, "omaha", Required.value(START.plusSeconds(40 * 60)), false);
            var b = new Visit("B", "B", "service", Required.value(START.plusSeconds(120 * 60)), Required.value(START.plusSeconds(130 * 60)), 10, OMAHA, "omaha", Required.value(START.plusSeconds(120 * 60)), hold);
            Day day = new Day(empty.technicians(), Required.value(Map.of("A", a, "B", b)), new Arrangement(Required.value(Map.of("omaha", List.of("A", "B")))), 0, empty.roads());
            var request = new BoundedBookingSearch.Request("C", "service", 60, TOWN);
            var chosen = Required.value(BoundedBookingSearch.choose(engine(snapshot(day), request, 240, "SHARED").search(true).candidates(), SchedulingPolicy.Rules.defaults()));
            assertEquals(List.of("A", "B", "C"), chosen.arrangement().routes().get("omaha"));
            assertEquals(4, crossings(chosen.arrangement()));
            assertFalse(Required.value(chosen.validation().arrivals().get("B")).isAfter(b.windowEnd()));
            Map<String, Visit> facts = new TreeMap<>(day.visits());
            facts.put("C", new Visit("C", "C", "service", chosen.window().start(), chosen.window().end(), 60, TOWN, "omaha", chosen.window().start(), false));
            assertEquals(Required.value(SmallCaseOracle.best(day, facts, RATES)).costCents(), chosen.validation().costCents());
            recordCompanion(hold ? "active-hold" : "protected-omaha", day, chosen, facts);
            Day released = new Day(day.technicians(), Required.value(Map.of("A", a)), new Arrangement(Required.value(Map.of("omaha", List.of("A")))), 1, day.roads());
            var flexible = Required.value(BoundedBookingSearch.choose(engine(snapshot(released), request, 240, "SHARED").search(true).candidates(), SchedulingPolicy.Rules.defaults()));
            assertEquals(2, crossings(flexible.arrangement()));
            assertFalse(flexible.arrangement().routes().values().stream().anyMatch(route -> route.contains("B")));
            facts.remove("B");
            facts.put("C", new Visit("C", "C", "service", flexible.window().start(), flexible.window().end(), 60, TOWN, "omaha", flexible.window().start(), false));
            recordCompanion(hold ? "released-hold" : "cancelled-omaha", released, flexible, facts);
        }
    }
    @Test void coordinatedReassignmentSolvesACaseThatInsertionCannot() throws Exception {
        Day empty = fixture(true, false, false, false);
        Map<String, Technician> techs = new TreeMap<>();
        empty.technicians().forEach((id, technician) -> techs.put(id, new Technician(technician.id(), START, Required.value(START.plusSeconds(240 * 60)), id.equals("omaha") ? 220 : 160, 0,
                id.equals("omaha") ? Required.value(Set.of("old", "new")) : Required.value(Set.of("old")), Required.value(List.of()), technician.departure(), technician.returnTo(), 0)));
        for (int oldWidth : List.of(120, 240)) {
            var a = new Visit("A", "A", "old", START, Required.value(START.plusSeconds(oldWidth * 60L)), 90, TOWN, "omaha", Required.value(START.plusSeconds(40 * 60)), false);
            var b = new Visit("B", "B", "old", START, Required.value(START.plusSeconds(oldWidth * 60L)), 60, OMAHA, "nearby", Required.value(START.plusSeconds(45 * 60)), false);
            Day day = new Day(techs, Required.value(Map.of("A", a, "B", b)), new Arrangement(Required.value(Map.of("omaha", List.of("A"), "nearby", List.of("B")))), 0, empty.roads());
            var request = new BoundedBookingSearch.Request("C", "new", 50, TOWN);
            assertTrue(engine(snapshot(day), request, 240, "INSERTION").search(false).candidates().isEmpty());
            for (String variant : List.of("BOUNDED", "EXPANDED", "RUIN_RECREATE", "SHARED")) {
                var chosen = Required.value(BoundedBookingSearch.choose(engine(snapshot(day), request, 240, Required.value(variant)).search(true).candidates(), SchedulingPolicy.Rules.defaults()));
                assertEquals(List.of("A"), chosen.arrangement().routes().get("nearby"));
                assertEquals(2, chosen.changedAssignments()); assertEquals(0, chosen.validation().overtimeMinutes());
                Map<String, Visit> facts = new TreeMap<>(day.visits());
                facts.put("C", new Visit("C", "C", "new", chosen.window().start(), chosen.window().end(), 50, TOWN, "omaha", chosen.window().start(), false));
                assertEquals(Required.value(SmallCaseOracle.best(day, facts, RATES)).costCents(), chosen.validation().costCents());
                recordCompanion("reassignment-" + oldWidth + "-" + variant, day, chosen, facts);
            }
        }
    }
    @Test void alternatingRequestsAreGroupedWithoutBreakingEarlierPromises() throws Exception {
        for (int width : List.of(120, 240)) {
            Day day = fixture(false, true, false, false);
            for (String id : List.of("A", "B", "C", "D", "E")) {
                var request = new BoundedBookingSearch.Request(Required.value(id), "service", 30, rural(Required.value(id)) ? TOWN : OMAHA);
                var chosen = Required.value(BoundedBookingSearch.choose(engine(snapshot(day), request, width, "SHARED").search(true).candidates(), SchedulingPolicy.Rules.defaults()));
                Map<String, Visit> facts = new TreeMap<>(day.visits());
                facts.put(id, new Visit(Required.value(id), Required.value(id), "service", chosen.window().start(), chosen.window().end(), 30, request.location(), chosen.technicianId(), chosen.window().start(), false));
                assertEquals(Required.value(SmallCaseOracle.best(day, facts, RATES)).costCents(), chosen.validation().costCents());
                assertEquals(2, crossings(chosen.arrangement()));
                recordCompanion("alternating-" + width + "-" + id, day, chosen, facts);
                Map<String, Visit> confirmed = new TreeMap<>();
                for (var visit : facts.values()) confirmed.put(visit.id(), new Visit(visit.id(), visit.jobId(), visit.serviceId(), visit.windowStart(), visit.windowEnd(), visit.durationMinutes(), visit.location(), "omaha", Required.value(chosen.validation().arrivals().get(visit.id())), false));
                day = new Day(day.technicians(), confirmed, chosen.arrangement(), day.reservationVersion() + 1, day.roads());
            }
        }
    }
    private static void recordCompanion(String name, Day day, BoundedBookingSearch.Candidate chosen, Map<String, Visit> facts) throws Exception {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("scenario", name); row.put("before", day.baseline().routes()); row.put("after", chosen.arrangement().routes());
        row.put("costCents", chosen.validation().costCents()); row.put("drivingMinutes", chosen.validation().driveMinutes());
        row.put("meters", chosen.validation().meters());
        row.put("noNewRequestOracleCostCents", Required.value(SmallCaseOracle.best(day, day.visits(), RATES)).costCents());
        row.put("directedRoads", day.roads().legs());
        row.put("endpoints", day.technicians().values().stream().map(t -> Map.of("id", t.id(), "departure", t.departure(), "return", t.returnTo(), "shiftStart", Required.value(t.shiftStart().toString()), "shiftEnd", Required.value(t.shiftEnd().toString()), "maxDailyMinutes", t.maxDailyMinutes(), "maxOvertimeMinutes", t.maxOvertimeMinutes())).toList());
        row.put("waitingMinutes", chosen.validation().waitingMinutes()); row.put("crossings", crossings(chosen.arrangement()));
        row.put("overtimeMinutes", chosen.validation().overtimeMinutes()); row.put("source", chosen.source());
        row.put("visits", facts.values().stream().map(v -> Map.of("id", v.id(), "start", Required.value(v.windowStart().toString()), "end", Required.value(v.windowEnd().toString()), "planned", Required.value(Required.value(chosen.validation().arrivals().get(v.id())).toString()), "serviceMinutes", v.durationMinutes())).toList());
        row.put("segments", chosen.validation().segments().entrySet().stream().map(e -> Map.of("technician", e.getKey(), "segments", e.getValue().stream().map(s -> Map.of("departure", Required.value(s.departure().toString()), "return", Required.value(s.returnedAt().toString()), "order", s.visitIds())).toList())).toList());
        Path target = Required.value(Path.of("target", "field-" + name + ".json"));
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(target.toFile(), row);
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
        techs.put("omaha", new Technician("omaha", START, Required.value(START.plusSeconds(480 * 60)), 600, 60, Required.value(Set.of("service")),
                absence ? Required.value(List.of(new TechRoute.Unavailable(Required.value(START.plusSeconds(180 * 60)), Required.value(START.plusSeconds(300 * 60))))) : Required.value(List.of()), OMAHA, OMAHA, 0));
        if (second) techs.put("nearby", new Technician("nearby", START, Required.value(START.plusSeconds(480 * 60)), 480, 0,
                qualified ? Required.value(Set.of("service")) : Required.value(Set.of("other")), Required.value(List.of()), TOWN, TOWN, 0));
        techs.keySet().forEach(id -> routes.put(id, new ArrayList<>()));
        Map<String, DayPlan.RoadLeg> roads = new TreeMap<>(); Set<String> missing = new TreeSet<>();
        for (String from : List.of("omaha", "nearby", "A", "B", "C", "D", "E")) for (String to : List.of("omaha:return", "nearby:return", "A", "B", "C", "D", "E")) {
            int minutes = rural(Required.value(from)) == rural(Required.value(to)) ? from.equals(to) ? 0 : 5 : rural(Required.value(from)) ? 45 : 40;
            if (unreachable && to.equals("omaha:return") && rural(Required.value(from))) missing.add(from + ">" + to);
            else roads.put(from + ">" + to, new DayPlan.RoadLeg(minutes * 60L, minutes * 800L));
        }
        return new Day(techs, Required.value(Map.of()), new Arrangement(routes), 0, new Roads(roads, missing));
    }
    private static boolean rural(String id) { return id.equals("A") || id.equals("C") || id.equals("E") || id.startsWith("nearby"); }
    private static int crossings(Arrangement arrangement) {
        int count = 0;
        for (var route : arrangement.routes().entrySet()) { String previous = Required.value(route.getKey()); for (String stop : route.getValue()) {
            if (rural(previous) != rural(Required.value(stop))) count++; previous = Required.value(stop);
        } if (!route.getValue().isEmpty() && rural(previous) != rural(route.getKey() + ":return")) count++; }
        return count;
    }
}
