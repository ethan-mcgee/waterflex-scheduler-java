package dev.waterflex.scheduler;

import dev.waterflex.scheduler.BookingSnapshot.*;
import dev.waterflex.scheduler.optimizer.DayPlan;
import java.time.LocalDate;
import java.util.*;

/** Fetches directed legs between search phases through one metro's routing client. Candidate scoring never calls this. */
public final class SnapshotRouting {
    private record PairKey(LocalDate day, String from, String to) { }
    private final RoadClient roads;
    public SnapshotRouting(RoadClient roads) { this.roads = roads; }

    public BookingSnapshot insertion(BookingSnapshot snapshot, BoundedBookingSearch.Request request) {
        Map<PairKey, RoadClient.Pair> pairs = new LinkedHashMap<>();
        for (var entry : snapshot.days().entrySet()) {
            SearchDeadline.checkpoint();
            LocalDate date = Required.value(entry.getKey());
            Day day = Required.value(entry.getValue());
            Map<String, RoadPoint> points = points(day, day.visits());
            if (points.putIfAbsent(request.jobId(), request.location()) != null)
                throw new BookingSnapshot.Incomplete("Request identifier already exists in snapshot");
            add(pairs, date, day, points, request.jobId(), request.jobId());
            for (Technician technician : day.technicians().values()) {
                add(pairs, date, day, points, technician.id(), request.jobId());
                add(pairs, date, day, points, request.jobId(), technician.id() + ":return");
            }
            for (Visit visit : day.visits().values()) {
                add(pairs, date, day, points, visit.id(), request.jobId());
                add(pairs, date, day, points, request.jobId(), visit.id());
            }
            routePairs(pairs, date, day, day.baseline(), day.visits(), points);
            routePairs(pairs, date, day, day.actualArrangement(), day.visits(), points);
        }
        return withDays(snapshot, fetch(snapshot.days(), pairs, snapshot.routingIdentity()));
    }

    public BookingSnapshot neighborhoods(BookingSnapshot snapshot, Map<LocalDate, Set<String>> selected) {
        return neighborhoods(snapshot, selected, SearchDeadline::checkpoint);
    }

    public BookingSnapshot neighborhoods(BookingSnapshot snapshot, Map<LocalDate, Set<String>> selected, Runnable checkpoint) {
        Map<PairKey, RoadClient.Pair> pairs = new LinkedHashMap<>();
        for (var entry : selected.entrySet()) {
            checkpoint.run();
            LocalDate date = Required.value(entry.getKey());
            Day day = Required.value(snapshot.days().get(date), "neighborhood date");
            Map<String, RoadPoint> points = points(day, day.visits());
            Set<String> stops = new TreeSet<>();
            for (String technician : entry.getValue()) stops.addAll(Required.value(day.baseline().routes().get(technician), "neighborhood route"));
            for (String technician : entry.getValue()) for (String visit : stops) {
                add(pairs, date, day, points, Required.value(technician), Required.value(visit));
                add(pairs, date, day, points, Required.value(visit), technician + ":return");
            }
            for (String from : stops) for (String to : stops) if (!from.equals(to))
                add(pairs, date, day, points, Required.value(from), Required.value(to));
        }
        checkpoint.run();
        return withDays(snapshot, fetch(snapshot.days(), pairs, snapshot.routingIdentity()));
    }

    public Day arrangements(LocalDate date, Day day, Map<String, Visit> visits, List<Arrangement> arrangements, String identity) {
        Map<PairKey, RoadClient.Pair> pairs = new LinkedHashMap<>();
        Map<String, RoadPoint> points = points(day, visits);
        for (Arrangement arrangement : arrangements) routePairs(pairs, date, day, Required.value(arrangement), visits, points);
        Map<LocalDate, Day> source = new TreeMap<>();
        source.put(date, day);
        return Required.value(fetch(source, pairs, identity).get(date), "routed reservation date");
    }

    private static void routePairs(Map<PairKey, RoadClient.Pair> pairs, LocalDate date, Day day,
            Arrangement arrangement, Map<String, Visit> visits, Map<String, RoadPoint> points) {
        for (var entry : arrangement.routes().entrySet()) {
            String technician = Required.value(entry.getKey());
            String previous = technician, confirmedPrevious = technician;
            for (String id : entry.getValue()) {
                String visit = Required.value(id);
                add(pairs, date, day, points, previous, visit);
                add(pairs, date, day, points, technician, visit);
                add(pairs, date, day, points, visit, technician + ":return");
                previous = visit;
                if (!Required.value(visits.get(visit), "routing visit").reservation()) {
                    add(pairs, date, day, points, confirmedPrevious, visit);
                    confirmedPrevious = visit;
                }
            }
        }
    }

    private static Map<String, RoadPoint> points(Day day, Map<String, Visit> visits) {
        Map<String, RoadPoint> points = new HashMap<>();
        day.technicians().forEach((id, technician) -> {
            points.put(id, technician.departure()); points.put(id + ":return", technician.returnTo());
        });
        visits.forEach((id, visit) -> {
            if (points.putIfAbsent(id, visit.location()) != null) throw new BookingSnapshot.Incomplete("Routing identifier collision");
        });
        return points;
    }

    private static void add(Map<PairKey, RoadClient.Pair> pairs, LocalDate date, Day day,
            Map<String, RoadPoint> points, String from, String to) {
        String key = from + ">" + to;
        if (day.roads().legs().containsKey(key) || day.roads().unreachable().contains(key)) return;
        PairKey pair = new PairKey(date, from, to);
        if (!pairs.containsKey(pair)) pairs.put(pair, new RoadClient.Pair(Required.value(Integer.toString(pairs.size())),
                Required.value(points.get(from), "routing origin"), Required.value(points.get(to), "routing destination")));
    }

    private Map<LocalDate, Day> fetch(Map<LocalDate, Day> days, Map<PairKey, RoadClient.Pair> pairs, String identity) {
        Map<String, RoadClient.Leg> result = roads.sparse(new ArrayList<>(pairs.values()), identity);
        Map<LocalDate, Map<String, DayPlan.RoadLeg>> legs = new HashMap<>();
        Map<LocalDate, Set<String>> unreachable = new HashMap<>();
        days.forEach((date, day) -> { legs.put(date, new HashMap<>(day.roads().legs())); unreachable.put(date, new HashSet<>(day.roads().unreachable())); });
        pairs.forEach((pair, requested) -> {
            RoadClient.Leg leg = result.get(requested.id());
            String key = pair.from() + ">" + pair.to();
            if (leg == null) Required.value(unreachable.get(pair.day())).add(key);
            else Required.value(legs.get(pair.day())).put(key, new DayPlan.RoadLeg(leg.seconds(), leg.meters()));
        });
        Map<LocalDate, Day> updated = new TreeMap<>();
        days.forEach((date, day) -> updated.put(date, new Day(day.technicians(), day.visits(), day.baseline(), day.reservationVersion(),
                new Roads(Required.value(legs.get(date)), Required.value(unreachable.get(date))))));
        return Required.value(Map.copyOf(updated));
    }

    private static BookingSnapshot withDays(BookingSnapshot snapshot, Map<LocalDate, Day> days) {
        return new BookingSnapshot(snapshot.metroId(), snapshot.capturedAt(), snapshot.calendarReference(), snapshot.configurationFingerprint(), snapshot.routingIdentity(),
                snapshot.policy(), snapshot.rates(), days, snapshot.horizon());
    }
}
