package dev.waterflex.scheduler.optimizer;

import dev.waterflex.scheduler.Required;
import java.util.*;

/** Isolate mutable solver entities, including inverse shadows, between phases and requests. */
public final class PlanCopies {
    private PlanCopies() { }
    public static DayPlan copy(DayPlan source) {
        Map<String, PlanVisit> visits = new LinkedHashMap<>();
        for (PlanVisit visit : source.getVisits()) visits.put(visit.getId(), new PlanVisit(visit.getId(), visit.getServiceId(),
                visit.getWindowStart(), visit.getWindowEnd(), visit.getDurationMinutes(), visit.getOriginalTechnicianId(), visit.getOriginalPlannedStart()));
        List<TechRoute> routes = new ArrayList<>();
        for (TechRoute route : source.getRoutes()) {
            TechRoute clone = new TechRoute(route.getId(), route.getShiftStart(), route.getShiftEnd(), route.getMaxDailyMinutes(),
                    route.getMaxOvertimeMinutes(), Required.value(Set.copyOf(route.getQualifiedServiceIds())));
            clone.setUnavailable(new ArrayList<>(route.getUnavailable()));
            for (PlanVisit visit : route.getVisits()) {
                PlanVisit cloned = Required.value(visits.get(visit.getId()), "copied visit");
                cloned.setTechnician(clone);
                clone.getVisits().add(cloned);
            }
            routes.add(clone);
        }
        DayPlan copy = new DayPlan(routes, new ArrayList<>(visits.values()), Required.value(Map.copyOf(source.getMatrix())), source.getRegularHourly(),
                source.getOvertimeHourly(), source.getMileagePerMile(), source.getTravelBufferPct(), source.getTravelBufferMinutes());
        var target = source.getScoringFacts().target();
        if (target != null) copy.setScoringFacts(copy.getScoringFacts().withTarget(target));
        return copy;
    }
}
