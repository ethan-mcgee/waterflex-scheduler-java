package dev.waterflex.scheduler.optimizer;

import dev.waterflex.scheduler.Required;
import java.time.Instant;
import java.util.*;

/** Deterministic directed fixtures, never a road-provider fallback. */
final class SolverBenchmarkData {
    enum Workload { SPARSE, CLUSTERED, DISPERSED, MIXED_SKILL, TIGHT_WINDOW, ABSENCE, NEAR_CAPACITY }
    private SolverBenchmarkData() { }
    static DayPlan create(int count, Workload workload) {
        if (count != 5 && count != 10 && count != 20 && count != 30 && count != 50) throw new IllegalArgumentException("Benchmark fleet must be 5, 10, 20, 30 or 50");
        List<TechRoute> routes = new ArrayList<>(); List<PlanVisit> visits = new ArrayList<>();
        Map<String, Integer> location = new TreeMap<>(); Map<String, Integer> cluster = new TreeMap<>();
        for (int technician = 0; technician < count; technician++) {
            String id = "tech-" + technician;
            Set<String> skills = workload == Workload.MIXED_SKILL && technician % 3 != 0 ? Required.value(Set.of("common")) : Required.value(Set.of("common", "scarce"));
            TechRoute route = new TechRoute(id, at(480), at(1020), 540, 60, skills);
            if (workload == Workload.ABSENCE && technician % 2 == 0) route.getUnavailable().add(new TechRoute.Unavailable(at(720), at(780)));
            routes.add(route); location.put(id, technician); location.put(id + ":return", technician);
            cluster.put(id, technician % 4); cluster.put(id + ":return", technician % 4);
            int assigned = workload == Workload.SPARSE ? (technician < count / 2 ? 3 : 1)
                    : workload == Workload.NEAR_CAPACITY ? 8 : (technician < count / 2 ? 6 : 2);
            int duration = workload == Workload.NEAR_CAPACITY ? 55 : workload == Workload.TIGHT_WINDOW ? 45 : 30;
            for (int index = 0; index < assigned; index++) {
                String visitId = "visit-" + technician + "-" + index;
                int window = 480 + (index / 2) * (workload == Workload.TIGHT_WINDOW ? 90 : 120);
                String service = workload == Workload.MIXED_SKILL && technician % 3 == 0 && index % 2 == 0 ? "scarce" : "common";
                PlanVisit visit = new PlanVisit(visitId, service, at(window), at(window + 120), duration, id, at(window));
                visit.setTechnician(route); route.getVisits().add(visit); visits.add(visit);
                location.put(visitId, count + visits.size()); cluster.put(visitId, technician % 4);
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
        if (!RouteEvaluator.evaluate(plan).feasible()) throw new IllegalStateException("Invalid benchmark seed: " + count + "/" + workload);
        return plan;
    }
    private static Instant at(int minute) { return Required.value(Instant.parse("2026-10-26T00:00:00Z").plusSeconds(minute * 60L)); }
}
