package dev.waterflex.scheduler.optimizer;

import dev.waterflex.scheduler.Required;
import java.util.*;

/** Isolate mutable solver entities, including inverse shadows, between phases and requests. */
public final class PlanCopies {
    private PlanCopies() { }
    public static DayPlan copy(DayPlan source) {
        source.getFacts().validateEntities(source, false);
        Map<String, PlanVisit> visits = new LinkedHashMap<>();
        for (PlanVisit visit : source.getVisits()) visits.put(visit.getId(), new PlanVisit(visit.getId(), visit.getServiceId(),
                visit.getWindowStart(), visit.getWindowEnd(), visit.getDurationMinutes(), visit.getOriginalTechnicianId(), visit.getOriginalPlannedStart()));
        List<TechRoute> routes = new ArrayList<>();
        for (TechRoute route : source.getRoutes()) {
            TechRoute clone = new TechRoute(route.getId(), route.getShiftStart(), route.getShiftEnd(), route.getMaxDailyMinutes(),
                    route.getMaxOvertimeMinutes(), Required.value(Set.copyOf(route.getQualifiedServiceIds())));
            clone.setUnavailable(new ArrayList<>(route.getUnavailable()));
            clone.freeze();
            for (PlanVisit visit : route.getVisits()) {
                PlanVisit cloned = Required.value(visits.get(visit.getId()), "copied visit");
                cloned.setTechnician(clone);
                clone.getVisits().add(cloned);
            }
            routes.add(clone);
        }
        return new DayPlan(routes, new ArrayList<>(visits.values()), source.getScoringFacts());
    }
    /** Fact changes create a new revision before solving or applying. */
    public static DayPlan withAbsence(DayPlan source, String technician, TechRoute.Unavailable absence) {
        DayPlan isolated = copy(source); boolean found = false; List<TechRoute> routes = new ArrayList<>();
        for (TechRoute route : isolated.getRoutes()) {
            TechRoute replacement = new TechRoute(route.getId(), route.getShiftStart(), route.getShiftEnd(), route.getMaxDailyMinutes(), route.getMaxOvertimeMinutes(), route.getQualifiedServiceIds());
            List<TechRoute.Unavailable> absences = new ArrayList<>(route.getUnavailable());
            if (route.getId().equals(technician)) { absences.add(absence); found = true; }
            replacement.setUnavailable(absences); replacement.setVisits(new ArrayList<>(route.getVisits()));
            replacement.getVisits().forEach(visit -> visit.setTechnician(replacement)); routes.add(replacement);
        }
        PlanFacts.check(found, "absence technician not in plan: " + technician);
        return new DayPlan(routes, isolated.getVisits(), source.getMatrix(), source.getFacts().unreachable(), source.getRegularHourly(), source.getOvertimeHourly(), source.getMileagePerMile(), source.getTravelBufferPct(), source.getTravelBufferMinutes());
    }
}
