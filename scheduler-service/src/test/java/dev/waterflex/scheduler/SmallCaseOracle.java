package dev.waterflex.scheduler;

import dev.waterflex.scheduler.BookingSnapshot.*;
import java.time.Instant;
import java.util.*;
import org.jspecify.annotations.Nullable;

/** Exhaustive minute-grid oracle. Does not call production timing, scoring or RouteEvaluator. */
final class SmallCaseOracle {
    record Timing(long paid, long drive, long waitMinutes, long meters, Map<String, Instant> arrivals,
                  List<Instant> departures, List<Instant> returns) { }
    record Answer(Arrangement arrangement, long costCents, Map<String, Timing> routes) { }
    private record Interval(Instant start, Instant end) { }
    private SmallCaseOracle() { }

    static @Nullable Answer best(Day day, Map<String, Visit> visits, Rates rates) {
        if (visits.size() > 6) throw new IllegalArgumentException("Exact oracle is limited to six visits");
        Map<String, List<String>> routes = new TreeMap<>(); day.technicians().keySet().forEach(id -> routes.put(id, new ArrayList<>()));
        return enumerate(day, visits, rates, routes, new TreeSet<>(visits.keySet()), null);
    }
    private static @Nullable Answer enumerate(Day day, Map<String, Visit> visits, Rates rates,
            Map<String, List<String>> routes, Set<String> remaining, @Nullable Answer best) {
        if (remaining.isEmpty()) {
            Answer answer = evaluate(day, new Arrangement(routes), visits, rates);
            return answer != null && (best == null || answer.costCents() < best.costCents()) ? answer : best;
        }
        for (String id : new ArrayList<>(remaining)) {
            Visit visit = Objects.requireNonNull(visits.get(id));
            remaining.remove(id);
            for (var route : routes.entrySet()) {
                if (!Objects.requireNonNull(day.technicians().get(route.getKey())).services().contains(visit.serviceId())) continue;
                route.getValue().add(id);
                best = enumerate(day, visits, rates, routes, remaining, best);
                route.getValue().removeLast();
            }
            remaining.add(id);
        }
        return best;
    }
    static @Nullable Answer evaluate(Day day, Arrangement arrangement, Map<String, Visit> visits, Rates rates) {
        Set<String> seen = new HashSet<>();
        Map<String, Timing> timing = new TreeMap<>(); long paid = 0, meters = 0;
        for (var route : arrangement.routes().entrySet()) {
            Technician tech = Objects.requireNonNull(day.technicians().get(route.getKey()));
            for (String id : route.getValue()) if (!seen.add(id) || !tech.services().contains(Objects.requireNonNull(visits.get(id)).serviceId())) return null;
            List<Interval> available = new ArrayList<>();
            Instant cursor = tech.shiftStart();
            var absences = new ArrayList<>(tech.absences()); absences.sort(Comparator.comparing(a -> a.start()));
            for (var absence : absences) {
                Instant end = absence.start().isBefore(tech.shiftEnd()) ? absence.start() : tech.shiftEnd();
                if (cursor.isBefore(end)) available.add(new Interval(cursor, end));
                if (absence.end().isAfter(cursor)) cursor = absence.end();
            }
            if (cursor.isBefore(tech.shiftEnd())) available.add(new Interval(cursor, tech.shiftEnd()));
            Timing measured = segments(day, tech, Objects.requireNonNull(route.getValue()), visits, rates, available, 0, 0);
            if (measured == null || measured.paid() > tech.maxDailyMinutes()) return null;
            timing.put(route.getKey(), measured); paid += measured.paid(); meters += measured.meters();
        }
        if (!seen.equals(visits.keySet())) return null;
        return new Answer(arrangement, cost(new Timing(paid, 0, 0, meters, new TreeMap<>(), new ArrayList<>(), new ArrayList<>()), rates).movePointRight(2).setScale(0, java.math.RoundingMode.HALF_UP).longValueExact(), timing);
    }
    private static @Nullable Timing segments(Day day, Technician tech, List<String> order, Map<String, Visit> visits,
            Rates rates, List<Interval> available, int interval, int index) {
        if (index == order.size()) return new Timing(0, 0, 0, 0, new TreeMap<>(), new ArrayList<>(), new ArrayList<>());
        if (interval == available.size()) return null;
        Timing best = segments(day, tech, order, visits, rates, available, interval + 1, index);
        Interval hours = Objects.requireNonNull(available.get(interval));
        for (int end = index + 1; end <= order.size(); end++) {
            Timing rest = segments(day, tech, order, visits, rates, available, interval + 1, end);
            if (rest == null) continue;
            for (Instant departure = hours.start(); departure.isBefore(hours.end()); departure = Required.value(departure.plusSeconds(60))) {
                Instant now = departure; String from = tech.id(); long drive = 0, wait = 0, meters = 0; boolean feasible = true;
                Map<String, Instant> arrivals = new TreeMap<>();
                for (int stop = index; stop < end; stop++) {
                    String id = Objects.requireNonNull(order.get(stop)); Visit visit = Objects.requireNonNull(visits.get(id));
                    var leg = day.roads().legs().get(from + ">" + id);
                    if (leg == null) { feasible = false; break; }
                    long travel = (long) Math.ceil((leg.seconds() * (1 + rates.travelBufferPct()) + rates.travelBufferMinutes() * 60) / 60.0);
                    drive += travel; meters += leg.meters(); now = Required.value(now.plusSeconds(travel * 60));
                    if (now.isBefore(visit.windowStart())) { wait += java.time.Duration.between(now, visit.windowStart()).toMinutes(); now = visit.windowStart(); }
                    if (!now.isBefore(visit.windowEnd())) { feasible = false; break; }
                    arrivals.put(id, now); now = Required.value(now.plusSeconds(visit.durationMinutes() * 60L)); from = id;
                }
                if (!feasible) continue;
                var back = day.roads().legs().get(from + ">" + tech.id() + ":return");
                if (back == null) continue;
                long travel = (long) Math.ceil((back.seconds() * (1 + rates.travelBufferPct()) + rates.travelBufferMinutes() * 60) / 60.0);
                drive += travel; meters += back.meters(); now = Required.value(now.plusSeconds(travel * 60));
                if (now.isAfter(hours.end())) continue;
                long paid = java.time.Duration.between(departure, now).toMinutes() + rest.paid();
                arrivals.putAll(rest.arrivals()); List<Instant> departures = new ArrayList<>(), returns = new ArrayList<>();
                departures.add(departure); departures.addAll(rest.departures()); returns.add(now); returns.addAll(rest.returns());
                Timing candidate = new Timing(paid, drive + rest.drive(), wait + rest.waitMinutes(), meters + rest.meters(), arrivals, departures, returns);
                if (best == null || cost(candidate, rates).compareTo(cost(best, rates)) < 0) best = candidate;
            }
        }
        return best;
    }
    private static java.math.BigDecimal cost(Timing value, Rates rates) { return Required.value(rates.regularHourly().multiply(java.math.BigDecimal.valueOf(value.paid())).divide(new java.math.BigDecimal("60"), 80, java.math.RoundingMode.HALF_UP).add(rates.mileagePerMile().multiply(java.math.BigDecimal.valueOf(value.meters())).divide(new java.math.BigDecimal("1609.344"), 80, java.math.RoundingMode.HALF_UP))); }
}
