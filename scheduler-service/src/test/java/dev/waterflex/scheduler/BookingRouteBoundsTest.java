package dev.waterflex.scheduler;

import dev.waterflex.scheduler.BookingSnapshot.*;
import dev.waterflex.scheduler.optimizer.DayPlan;
import dev.waterflex.scheduler.optimizer.TechRoute;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BookingRouteBoundsTest {
    @Test void noFeasibleInsertionIsPrunedAcrossDirectedAbsenceAndChangedPromiseCases() {
        Random random = new Random(9017); int feasible = 0, pruned = 0;
        Instant start = Required.value(Instant.parse("2026-10-26T13:00:00Z"));
        var point = new RoadClient.Point(41, -96);
        for (int sample = 0; sample < 250; sample++) {
            var rates = new Rates(30, 45, .67, sample % 2 == 0 ? .2 : 0, sample % 3);
            List<TechRoute.Unavailable> absences = sample % 2 == 0
                    ? Required.value(List.<TechRoute.Unavailable>of(new TechRoute.Unavailable(Required.value(start.plusSeconds(90 * 60)), Required.value(start.plusSeconds(120 * 60))))) : Required.value(List.of());
            var technician = new Technician("tech", start, Required.value(start.plusSeconds(240 * 60)), 240, 30,
                    Required.value(Set.of("service")), absences, point, point, 0);
            Map<String, Visit> facts = new HashMap<>();
            for (int index = 0; index < 3; index++) {
                String id = "v" + index; Instant window = Required.value(start.plusSeconds((index * 45L + random.nextInt(15)) * 60));
                facts.put(id, new Visit(id, id, "service", window, Required.value(window.plusSeconds(7200)), 10 + random.nextInt(21), point, "tech", window, false));
            }
            Map<String, DayPlan.RoadLeg> matrix = new HashMap<>();
            for (String from : List.of("tech", "tech:return", "v0", "v1", "v2", "new"))
                for (String to : List.of("tech", "tech:return", "v0", "v1", "v2", "new"))
                    matrix.put(from + ">" + to, new DayPlan.RoadLeg(random.nextInt(20) * 60L, random.nextInt(1000)));
            // The direct arc may be much slower than endpoint travel across the absence.
            matrix.put("v1>v2", new DayPlan.RoadLeg(sample % 4 == 0 ? 80 * 60 : 60, 100));
            var order = Required.value(List.of("v0", "v1", "v2"));
            var baseline = new Arrangement(Required.value(Map.<String, List<String>>of("tech", order)));
            var day = new Day(Required.value(Map.<String, Technician>of("tech", technician)), facts, baseline, 0, new Roads(matrix, Required.value(Set.of())));
            var bounds = new BookingRouteBounds(day, rates);
            boolean baseFeasible = day.evaluate(baseline, facts, rates).feasible();
            if (baseFeasible) assertTrue(bounds.possible("tech", order, facts));
            if (!baseFeasible) continue;
            for (int promise = 0; promise < 4; promise++) {
                Instant window = Required.value(start.plusSeconds(promise * 60L * 60));
                Visit visit = new Visit("new", "new", "service", window, Required.value(window.plusSeconds(7200)), 25, point, "tech", window, false);
                Map<String, Visit> insertedFacts = new HashMap<>(facts); insertedFacts.put("new", visit);
                for (int position = 0; position <= order.size(); position++) {
                    var inserted = baseline.insert("tech", "new", position);
                    boolean allowed = bounds.insertion("tech", order, facts, visit, position);
                    if (!allowed) pruned++;
                    if (day.evaluate(inserted, insertedFacts, rates).feasible()) {
                        feasible++; assertTrue(allowed, "False pruning at sample " + sample + " promise " + promise + " position " + position);
                        assertTrue(bounds.possible("tech", Required.value(inserted.routes().get("tech")), insertedFacts));
                    }
                }
            }
        }
        assertTrue(feasible > 100); assertTrue(pruned > 0);
    }
}
