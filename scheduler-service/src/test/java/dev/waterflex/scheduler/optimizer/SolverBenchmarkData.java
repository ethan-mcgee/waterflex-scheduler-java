package dev.waterflex.scheduler.optimizer;

import dev.waterflex.scheduler.Required;
import java.time.Instant;
import java.util.*;

/**
 * Deterministic directed fixtures, never a road-provider fallback. Fixture seed 0 is the canonical fixture of each fleet
 * and workload, byte for byte as earlier studies measured it. Another fixture seed draws a new fixture of the same
 * shape for out-of-sample checks: which technicians are heavy, scarce-skilled, absent or in which travel group is
 * shuffled, and every point gets a new location number, so the same travel formulas give new legs in the same ranges.
 */
final class SolverBenchmarkData {
    /** MIXED_SHIFT gives technicians 4, 6 and 9 hour days by rank, so capacity-weighted and unweighted fairness differ. */
    enum Workload { SPARSE, CLUSTERED, DISPERSED, MIXED_SKILL, TIGHT_WINDOW, ABSENCE, NEAR_CAPACITY, MIXED_SHIFT }
    /** The MIXED_SHIFT shift length in minutes for a technician's rank. */
    static int shiftMinutes(int rank) { return switch (rank % 3) { case 0 -> 240; case 1 -> 360; default -> 540; }; }
    private SolverBenchmarkData() { }
    static DayPlan create(int count, Workload workload) { return create(count, workload, 0); }
    static DayPlan create(int count, Workload workload, long fixtureSeed) {
        if (count != 5 && count != 10 && count != 20 && count != 30 && count != 50) throw new IllegalArgumentException("Benchmark fleet must be 5, 10, 20, 30 or 50");
        if (fixtureSeed < 0) throw new IllegalArgumentException("Benchmark fixture seed must not be negative");
        Random random = new Random(fixtureSeed);
        // Each technician's role (heavy or light, scarce skill, absence, travel group) follows its rank, not its ID.
        List<Integer> ranks = new ArrayList<>();
        for (int technician = 0; technician < count; technician++) ranks.add(technician);
        if (fixtureSeed != 0) Collections.shuffle(ranks, random);
        Set<Integer> usedLocations = new HashSet<>();
        List<TechRoute> routes = new ArrayList<>(); List<PlanVisit> visits = new ArrayList<>();
        Map<String, Integer> location = new TreeMap<>(); Map<String, Integer> cluster = new TreeMap<>();
        for (int technician = 0; technician < count; technician++) {
            String id = "tech-" + technician;
            int rank = Required.value(ranks.get(technician));
            Set<String> skills = workload == Workload.MIXED_SKILL && rank % 3 != 0 ? Required.value(Set.of("common")) : Required.value(Set.of("common", "scarce"));
            int shift = workload == Workload.MIXED_SHIFT ? shiftMinutes(rank) : 540;
            TechRoute route = new TechRoute(id, at(480), at(480 + shift), shift, shift / 9, skills);
            if (workload == Workload.ABSENCE && rank % 2 == 0) route.getUnavailable().add(new TechRoute.Unavailable(at(720), at(780)));
            int home = locationNumber(technician, fixtureSeed, random, usedLocations);
            routes.add(route); location.put(id, home); location.put(id + ":return", home);
            cluster.put(id, rank % 4); cluster.put(id + ":return", rank % 4);
            int assigned = workload == Workload.SPARSE ? (rank < count / 2 ? 3 : 1)
                    : workload == Workload.NEAR_CAPACITY ? 8
                    : workload == Workload.MIXED_SHIFT ? (rank < count / 2 ? shift / 80 : shift / 240 + 1) : (rank < count / 2 ? 6 : 2);
            int duration = workload == Workload.NEAR_CAPACITY ? 55 : workload == Workload.TIGHT_WINDOW ? 45 : 30;
            for (int index = 0; index < assigned; index++) {
                String visitId = "visit-" + technician + "-" + index;
                int window = 480 + (index / 2) * (workload == Workload.TIGHT_WINDOW ? 90 : 120);
                String service = workload == Workload.MIXED_SKILL && rank % 3 == 0 && index % 2 == 0 ? "scarce" : "common";
                PlanVisit visit = new PlanVisit(visitId, service, at(window), at(window + 120), duration, id, at(window));
                visit.setTechnician(route); route.getVisits().add(visit); visits.add(visit);
                location.put(visitId, locationNumber(count + visits.size(), fixtureSeed, random, usedLocations)); cluster.put(visitId, rank % 4);
            }
        }
        Map<String, DayPlan.RoadLeg> matrix = new TreeMap<>();
        for (String from : location.keySet()) for (String to : location.keySet()) {
            int a = Required.value(location.get(from)), b = Required.value(location.get(to));
            long seconds = a == b ? 0 : workload == Workload.CLUSTERED
                    ? (Objects.equals(cluster.get(from), cluster.get(to)) ? 120 : 480) + Math.floorMod(a - b, 3) * 30L
                    : workload == Workload.DISPERSED ? 180 + Math.floorMod(a * 31 - b * 13, 420)
                    : 90 + Math.floorMod(a * 31 - b * 13, 210);
            matrix.put(from + ">" + to, new DayPlan.RoadLeg(seconds, seconds * 12));
        }
        DayPlan plan = new DayPlan(routes, visits, matrix, 30, 45, .67, .2, 5);
        if (!RouteEvaluator.evaluate(plan).feasible()) throw new IllegalStateException("Invalid benchmark seed: " + count + "/" + workload + "/" + fixtureSeed);
        return plan;
    }
    /** The canonical fixture numbers points in order; a drawn fixture gives each point a distinct number in its place. */
    private static int locationNumber(int canonical, long fixtureSeed, Random random, Set<Integer> used) {
        if (fixtureSeed == 0) return canonical;
        int drawn;
        do { drawn = random.nextInt(100_000); } while (!used.add(drawn));
        return drawn;
    }
    private static Instant at(int minute) { return Required.value(Instant.parse("2026-10-26T00:00:00Z").plusSeconds(minute * 60L)); }
}
