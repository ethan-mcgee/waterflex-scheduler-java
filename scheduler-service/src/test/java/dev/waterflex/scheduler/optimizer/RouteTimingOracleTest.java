package dev.waterflex.scheduler.optimizer;

import dev.waterflex.scheduler.Required;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Exhaustive minute-grid oracle, independent of both production timing algorithms. */
class RouteTimingOracleTest {
    private static final Instant START = Required.value(Instant.parse("2026-09-23T08:00:00Z"));
    private record Window(int start, int end, int duration) { }
    private record Option(long paid, long overtime, long meters) { }

    @Test void intervalPlacementAndDepartureMatchExhaustiveEnumeration() {
        Random random = new Random(47291);
        for (int example = 0; example < 200; example++) {
            int regularEnd = 180, end = 210, breakStart = 60, breakEnd = 90;
            int limit = 60 + random.nextInt(151), overtimeLimit = random.nextInt(31);
            List<Window> windows = new ArrayList<>();
            for (int index = 0; index < 3; index++) {
                int start = random.nextInt(160);
                windows.add(new Window(start, start + 15 + random.nextInt(76), 5 + random.nextInt(26)));
            }
            TechRoute route = new TechRoute("t", time(0), time(regularEnd), limit, overtimeLimit, Required.value(Set.of("s")));
            route.getUnavailable().add(new TechRoute.Unavailable(time(breakStart), time(breakEnd)));
            List<PlanVisit> visits = new ArrayList<>();
            for (int index = 0; index < windows.size(); index++) {
                Window window = windows.get(index);
                visits.add(new PlanVisit("v" + index, "s", time(window.start()), time(window.end()), window.duration(), "t", time(window.start())));
            }
            route.getVisits().addAll(visits);
            Map<String, DayPlan.RoadLeg> roads = new HashMap<>();
            for (String from : List.of("t", "v0", "v1", "v2")) for (String to : List.of("v0", "v1", "v2", "t:return"))
                if (random.nextInt(10) != 0) roads.put(from + ">" + to, new DayPlan.RoadLeg(random.nextInt(16) * 60, random.nextInt(3000)));
            DayPlan plan = new DayPlan(Required.value(List.of(route)), visits, roads, 30, 45, .67, 0, 0);
            // The allowed overtime also determines the end of the last working interval.
            end = regularEnd + overtimeLimit;
            List<Option> exact = enumerate(windows, roads, 0, 0, new int[][]{{0, breakStart}, {breakEnd, end}}, regularEnd);
            var best = exact.stream().filter(value -> value.paid() <= limit && value.overtime() <= overtimeLimit)
                    .min(Comparator.<Option>comparingLong(value -> Required.value(value).overtime()).thenComparingDouble(value -> cost(Required.value(value))));
            var evaluated = RouteEvaluator.evaluate(plan);
            var scored = DayScoreCalculator.evaluate(plan);
            assertEquals(best.isPresent(), evaluated.feasible(), "feasibility case " + example);
            assertEquals(best.isPresent(), scored.hardPenalty() == 0, "score feasibility case " + example);
            if (best.isPresent()) {
                assertEquals(best.get().overtime(), evaluated.overtimeMinutes(), "overtime case " + example);
                assertEquals(Math.round(cost(Required.value(best.get()))), evaluated.costCents(), "cost case " + example);
                assertEquals(evaluated.arrivals(), scored.arrivals(), "arrivals case " + example);
                assertEquals(evaluated.costCents(), scored.costCents(), "score cost case " + example);
            }
        }
    }

    private static List<Option> enumerate(List<Window> windows, Map<String, DayPlan.RoadLeg> roads,
            int first, int interval, int[][] blocks, int regularEnd) {
        if (first == windows.size()) return Required.value(List.<Option>of(new Option(0, 0, 0)));
        List<Option> options = new ArrayList<>();
        for (int block = interval; block < blocks.length; block++) for (int stop = first + 1; stop <= windows.size(); stop++) {
            List<Option> tails = enumerate(windows, roads, stop, block + 1, blocks, regularEnd);
            if (tails.isEmpty()) continue;
            // Enumerate every possible departure, including those that create avoidable waiting.
            for (int departure = blocks[block][0]; departure < blocks[block][1]; departure++) {
                int cursor = departure; long meters = 0; boolean valid = true;
                String previous = "t";
                for (int visit = first; visit < stop; visit++) {
                    DayPlan.RoadLeg leg = roads.get(previous + ">v" + visit);
                    if (leg == null) { valid = false; break; }
                    Window window = windows.get(visit);
                    cursor = Math.max(cursor + (int) (leg.seconds() / 60), window.start());
                    if (cursor >= window.end()) { valid = false; break; }
                    cursor += window.duration(); meters += leg.meters(); previous = "v" + visit;
                }
                DayPlan.RoadLeg returned = roads.get(previous + ">t:return");
                if (!valid || returned == null) continue;
                cursor += (int) (returned.seconds() / 60); meters += returned.meters();
                if (cursor > blocks[block][1]) continue;
                long paid = cursor - departure, overtime = Math.max(0, cursor - Math.max(departure, regularEnd));
                for (Option tail : tails) options.add(new Option(paid + tail.paid(), overtime + tail.overtime(), meters + tail.meters()));
            }
        }
        return options;
    }
    private static double cost(Option value) { return (value.paid() - value.overtime()) * 50.0 + value.overtime() * 75.0 + value.meters() / 1609.344 * 67; }
    private static Instant time(int minutes) { return Required.value(START.plusSeconds(minutes * 60L)); }
}
