package dev.waterflex.scheduler.optimizer;

import dev.waterflex.scheduler.Monetary;
import dev.waterflex.scheduler.Required;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Exhaustive minute-grid oracle, independent of both production timing algorithms. */
class RouteTimingOracleTest {
    private static final Instant START = Required.value(Instant.parse("2026-09-23T08:00:00Z"));
    private record Window(int start, int end, int duration) { }
    private record Option(long paid, long overtime, long meters, long buffer) { }

    @Test void intervalPlacementAndDepartureMatchExhaustiveEnumeration() {
        check(47291, PlanFacts.ArrivalBuffer.NONE);
    }

    /**
     * With a late-arrival buffer both production timings must find the least buffer use any departure gives, after
     * overtime and before cost. 25 percent held to 10 to 30 minutes reaches the floor, the share, the cap and the
     * half-window limit across these 15 to 90 minute windows.
     */
    @Test void bufferedPlacementMatchesExhaustiveEnumeration() {
        check(47291, new PlanFacts.ArrivalBuffer(25, 10, 30));
        check(90210, new PlanFacts.ArrivalBuffer(50, 0, 240));
    }

    private static void check(long seed, PlanFacts.ArrivalBuffer buffer) {
        Random random = new Random(seed);
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
            DayPlan plan = new DayPlan(Required.value(List.of(route)), visits, roads, Required.value(Set.of()), Monetary.legacy(30), Monetary.legacy(45),
                    Monetary.legacy(.67), Monetary.legacy(0), 0, buffer);
            // The allowed overtime also determines the end of the last working interval.
            end = regularEnd + overtimeLimit;
            List<Option> exact = enumerate(windows, roads, 0, 0, new int[][]{{0, breakStart}, {breakEnd, end}}, regularEnd, buffer);
            var best = exact.stream().filter(value -> value.paid() <= limit && value.overtime() <= overtimeLimit)
                    .min(Comparator.<Option>comparingLong(value -> Required.value(value).overtime()).thenComparingLong(value -> Required.value(value).buffer())
                            .thenComparing(value -> cost(Required.value(value))));
            var evaluated = RouteEvaluator.evaluate(plan);
            var scored = DayScoreCalculator.evaluate(plan);
            String label = buffer + " case " + example;
            assertEquals(best.isPresent(), evaluated.feasible(), "feasibility " + label);
            assertEquals(best.isPresent(), scored.hardPenalty() == 0, "score feasibility " + label);
            if (best.isPresent()) {
                assertEquals(best.get().overtime(), evaluated.overtimeMinutes(), "overtime " + label);
                assertEquals(best.get().buffer(), evaluated.bufferSeconds(), "buffer " + label);
                assertEquals(rounded(cost(Required.value(best.get()))), evaluated.costCents(), "cost " + label);
                assertEquals(evaluated.arrivals(), scored.arrivals(), "arrivals " + label);
                assertEquals(evaluated.costCents(), scored.costCents(), "score cost " + label);
                assertEquals(evaluated.bufferSeconds(), scored.bufferSeconds(), "score buffer " + label);
            }
        }
    }

    /** The oracle's own buffer rule, on the minute grid: a share of the window, held to the bounds and half the window. */
    private static int bufferMinutes(Window window, PlanFacts.ArrivalBuffer buffer) {
        int minutes = window.end() - window.start();
        int held = Math.min(Math.max(minutes * buffer.percent() / 100, buffer.minMinutes()), buffer.maxMinutes());
        return Math.min(held, minutes / 2);
    }

    private static List<Option> enumerate(List<Window> windows, Map<String, DayPlan.RoadLeg> roads,
            int first, int interval, int[][] blocks, int regularEnd, PlanFacts.ArrivalBuffer buffer) {
        if (first == windows.size()) return Required.value(List.<Option>of(new Option(0, 0, 0, 0)));
        List<Option> options = new ArrayList<>();
        for (int block = interval; block < blocks.length; block++) for (int stop = first + 1; stop <= windows.size(); stop++) {
            List<Option> tails = enumerate(windows, roads, stop, block + 1, blocks, regularEnd, buffer);
            if (tails.isEmpty()) continue;
            // Enumerate every possible departure, including those that create avoidable waiting.
            for (int departure = blocks[block][0]; departure < blocks[block][1]; departure++) {
                int cursor = departure; long meters = 0, inside = 0; boolean valid = true;
                String previous = "t";
                for (int visit = first; visit < stop; visit++) {
                    DayPlan.RoadLeg leg = roads.get(previous + ">v" + visit);
                    if (leg == null) { valid = false; break; }
                    Window window = windows.get(visit);
                    cursor = Math.max(cursor + (int) (leg.seconds() / 60), window.start());
                    if (cursor >= window.end()) { valid = false; break; }
                    int deadline = window.end() - bufferMinutes(window, buffer);
                    if (cursor >= deadline) inside += (cursor - deadline) * 60L + 1;
                    cursor += window.duration(); meters += leg.meters(); previous = "v" + visit;
                }
                DayPlan.RoadLeg returned = roads.get(previous + ">t:return");
                if (!valid || returned == null) continue;
                cursor += (int) (returned.seconds() / 60); meters += returned.meters();
                if (cursor > blocks[block][1]) continue;
                long paid = cursor - departure, overtime = Math.max(0, cursor - Math.max(departure, regularEnd));
                for (Option tail : tails) options.add(new Option(paid + tail.paid(), overtime + tail.overtime(), meters + tail.meters(), inside + tail.buffer()));
            }
        }
        return options;
    }
    private static java.math.BigInteger cost(Option value) { return Required.value(java.math.BigInteger.valueOf((value.paid() - value.overtime()) * 50 + value.overtime() * 75).multiply(java.math.BigInteger.valueOf(201168)).add(java.math.BigInteger.valueOf(value.meters()).multiply(java.math.BigInteger.valueOf(8375)))); }
    private static long rounded(java.math.BigInteger numerator) { var parts = numerator.divideAndRemainder(java.math.BigInteger.valueOf(201168)); return parts[0].add(parts[1].shiftLeft(1).compareTo(java.math.BigInteger.valueOf(201168)) >= 0 ? java.math.BigInteger.ONE : java.math.BigInteger.ZERO).longValueExact(); }
    private static Instant time(int minutes) { return Required.value(START.plusSeconds(minutes * 60L)); }
}
