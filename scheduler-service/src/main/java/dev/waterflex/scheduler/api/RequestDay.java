package dev.waterflex.scheduler.api;

import dev.waterflex.scheduler.Required;
import dev.waterflex.scheduler.RoadPoint;
import dev.waterflex.scheduler.api.PublicRequests.DailyProposalRequest;
import dev.waterflex.scheduler.api.PublicTypes.Appointment;
import dev.waterflex.scheduler.api.PublicTypes.Location;
import dev.waterflex.scheduler.api.PublicTypes.Technician;
import dev.waterflex.scheduler.api.PublicTypes.TechnicianDay;
import dev.waterflex.scheduler.optimizer.DayPlan;
import dev.waterflex.scheduler.optimizer.PlanVisit;
import dev.waterflex.scheduler.optimizer.TechRoute;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Builds the solver input for one service date from a public request instead of the scheduler database. Routing is
 * supplied by the caller between {@link #points} and {@link #plan}, the same order the database path uses.
 */
public final class RequestDay {
    /** Resolves an address-only location. Host coordinates are authoritative and never pass through it. */
    public interface Locator { RoadPoint locate(PublicTypes.Address address); }

    private final DailyProposalRequest request;
    private final Map<String, RoadPoint> points;

    private RequestDay(DailyProposalRequest request, Map<String, RoadPoint> points) {
        this.request = request;
        this.points = points;
    }

    /**
     * Road points keyed the way the database path keys them: the technician ID for the departure, the technician ID
     * plus ":return" for the return, and the appointment ID for each visit.
     */
    public static RequestDay of(DailyProposalRequest request, Locator locator) {
        Map<String, RoadPoint> points = new LinkedHashMap<>();
        for (TechnicianDay day : days(request)) {
            put(points, day.technicianId(), point(day.start(), locator));
            put(points, day.technicianId() + ":return", point(day.end(), locator));
        }
        for (Appointment appointment : appointments(request)) put(points, appointment.id(), point(appointment.location(), locator));
        return new RequestDay(request, Required.value(java.util.Collections.unmodifiableMap(points)));
    }

    public Map<String, RoadPoint> points() { return points; }

    /**
     * Every directed pair between two different points that is absent from {@code reachable} is unroutable, as in
     * the database path. A point's leg to itself is kept as routing returned it, also as in the database path.
     * Overtime is never assigned, so every route allows zero overtime minutes.
     */
    public DayPlan plan(Map<String, DayPlan.RoadLeg> reachable) {
        for (String pair : reachable.keySet()) {
            String[] ends = Required.value(pair).split(">", -1);
            if (ends.length != 2 || !points.containsKey(ends[0]) || !points.containsKey(ends[1]))
                throw new IllegalStateException("Routing returned a leg outside the requested points: " + pair);
        }
        Set<String> unreachable = new HashSet<>();
        for (String from : points.keySet()) for (String to : points.keySet())
            if (!from.equals(to) && !reachable.containsKey(from + ">" + to)) unreachable.add(from + ">" + to);
        PublicTypes.Snapshot snapshot = request.snapshot();
        Map<String, Technician> technicians = new HashMap<>();
        for (Technician technician : snapshot.technicians()) technicians.put(technician.id(), technician);
        Map<String, TechRoute> routes = new LinkedHashMap<>();
        for (TechnicianDay day : days(request)) {
            Technician technician = Required.value(technicians.get(day.technicianId()), "technician for " + day.key());
            TechRoute route = new TechRoute(day.technicianId(), day.shift().start(), day.shift().end(), day.maxPaidMinutes(), 0,
                    Required.value(Set.copyOf(technician.qualifications())));
            List<TechRoute.Unavailable> absences = new ArrayList<>();
            for (PublicTypes.Window absence : day.absences()) absences.add(new TechRoute.Unavailable(absence.start(), absence.end()));
            route.setUnavailable(absences);
            routes.put(day.technicianId(), route);
        }
        List<PlanVisit> visits = new ArrayList<>();
        for (Appointment appointment : appointments(request)) {
            PlanVisit visit = new PlanVisit(appointment.id(), appointment.serviceId(), appointment.window().start(), appointment.window().end(),
                    appointment.durationMinutes(), appointment.technicianId(), appointment.plannedStart());
            Required.value(routes.get(appointment.technicianId()), "route for " + appointment.key()).getVisits().add(visit);
            visits.add(visit);
        }
        PublicTypes.Rates rates = snapshot.rates();
        return new DayPlan(Required.value(List.copyOf(routes.values())), Required.value(List.copyOf(visits)), reachable, unreachable,
                rates.regularHourly(), rates.overtimeHourly(), rates.mileagePerMile(), rates.travelBufferPct(), rates.travelBufferMinutes());
    }

    /** Technician-days in technician ID order, matching the database path's ORDER BY. The snapshot has one per technician. */
    private static List<TechnicianDay> days(DailyProposalRequest request) {
        TreeMap<String, TechnicianDay> days = new TreeMap<>();
        for (TechnicianDay day : request.snapshot().technicianDays()) days.put(day.technicianId(), day);
        return Required.value(List.copyOf(days.values()));
    }

    /** Appointments ordered by technician then sequence, matching the database path's ORDER BY. Sequences are unique per day. */
    private static List<Appointment> appointments(DailyProposalRequest request) {
        TreeMap<String, TreeMap<Integer, Appointment>> ordered = new TreeMap<>();
        for (Appointment appointment : request.snapshot().appointments())
            ordered.computeIfAbsent(appointment.technicianId(), _ -> new TreeMap<>()).put(appointment.sequence(), appointment);
        List<Appointment> appointments = new ArrayList<>();
        for (TreeMap<Integer, Appointment> route : ordered.values()) appointments.addAll(route.values());
        return Required.value(List.copyOf(appointments));
    }

    private static RoadPoint point(Location location, Locator locator) {
        Double lat = location.lat(), lng = location.lng();
        if (lat != null && lng != null) return new RoadPoint(lat, lng);
        return locator.locate(Required.value(location.address(), "address for a location without coordinates"));
    }

    private static void put(Map<String, RoadPoint> points, String key, RoadPoint point) {
        if (points.putIfAbsent(key, point) != null) throw new IllegalArgumentException("Identity " + key + " is used by more than one location");
    }
}
