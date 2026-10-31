package dev.waterflex.scheduler.optimizer;

import dev.waterflex.scheduler.Required;
import dev.waterflex.scheduler.Monetary;
import dev.waterflex.scheduler.SearchDeadline;
import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/** Independently recomputes route timing, coverage, qualifications, limits, and cost. */
public final class RouteEvaluator {
    /**
     * {@code bufferSeconds} is the late-arrival buffer the final arrivals use ({@link PlanFacts.ArrivalBuffer}), read from
     * the arrival instants themselves rather than from the search that placed them.
     */
    public record Result(boolean feasible, long costCents, Map<String, Instant> arrivals,
                         long paidMinutes, long overtimeMinutes, long driveMinutes, long waitingMinutes, long meters, long bufferSeconds,
                         Map<String, List<WorkingSegment>> segments) {
        public Result {
            arrivals=Required.value(Map.copyOf(arrivals)); Map<String,List<WorkingSegment>> copy=new TreeMap<>();
            segments.forEach((id,values) -> copy.put(Required.value(id),Required.value(List.copyOf(values)))); segments=Required.value(Map.copyOf(copy));
            if (costCents < 0 || paidMinutes < 0 || overtimeMinutes < 0 || driveMinutes < 0 || waitingMinutes < 0 || meters < 0 || bufferSeconds < 0) throw new IllegalArgumentException("Invalid route resource metrics");
        }
    }
    /** The buffer seconds an arrival at {@code arrival} uses: whole seconds at or past the visit's deadline, plus one. */
    public static long bufferSeconds(PlanFacts.ArrivalBuffer buffer, PlanVisit visit, Instant arrival) {
        Instant deadline = buffer.deadline(visit.getWindowStart(), visit.getWindowEnd());
        return arrival.isBefore(deadline) ? 0 : Math.addExact(Duration.between(deadline, arrival).getSeconds(), 1);
    }
    private static long bufferSeconds(PlanFacts.ArrivalBuffer buffer, List<PlanVisit> visits, Map<String, Instant> arrivals) {
        long total = 0;
        for (PlanVisit visit : visits) {
            Instant arrival = arrivals.get(visit.getId());
            if (arrival != null) total = Math.addExact(total, bufferSeconds(buffer, visit, arrival));
        }
        return total;
    }
    public record WorkingSegment(Instant departure, Instant returnedAt, List<String> visitIds) {
        public WorkingSegment { Required.value(departure); Required.value(returnedAt); visitIds=Required.value(List.copyOf(visitIds));
            if (departure.isAfter(returnedAt)) throw new IllegalArgumentException("Invalid working segment"); }
    }
    private record Segment(String previous, Instant departure, Instant done) { }
    private record Timing(PlanVisit visit, Instant arrival, long waitingBefore) { }
    private record Work(Instant start, Instant end) { }
    private record RouteResult(boolean feasible, long paid, long overtime, long drive, long waiting, long meters, long buffer) { }
    private RouteEvaluator() { }

    public static Result evaluate(DayPlan plan) {
        return evaluate(plan, true);
    }
    /** Validates served work independently; callers must inspect unresolved demand separately. */
    public static Result evaluateAssigned(DayPlan plan) {
        return evaluate(plan, false);
    }
    private static Result evaluate(DayPlan plan, boolean requireCoverage) {
        plan.getFacts().validateEntities(plan, false);
        Set<String> expected = new HashSet<>();
        boolean feasible = true;
        for (PlanVisit visit : plan.getVisits()) if (!expected.add(visit.getId())) feasible = false;
        Set<String> seen = new HashSet<>();
        Map<String, Instant> arrivals = new HashMap<>();
        long paid = 0, overtime = 0, drive = 0, waiting = 0, meters = 0, buffer = 0;
        Map<String, List<WorkingSegment>> segments = new LinkedHashMap<>();
        for (TechRoute route : plan.getRoutes()) {
            for (PlanVisit visit : route.getVisits())
                if (!seen.add(visit.getId()) || !expected.contains(visit.getId())) feasible = false;
            List<WorkingSegment> routeSegments = new ArrayList<>();
            RouteResult result = validateRoute(plan, route, arrivals, routeSegments);
            segments.put(route.getId(), Required.value(List.copyOf(routeSegments)));
            feasible &= result.feasible();
            paid = Math.addExact(paid, result.paid()); overtime = Math.addExact(overtime, result.overtime()); drive += result.drive();
            waiting += result.waiting(); meters = Math.addExact(meters, result.meters()); buffer = Math.addExact(buffer, result.buffer());
        }
        feasible &= arrivals.keySet().equals(seen) && (!requireCoverage || seen.equals(expected));
        long cents = Monetary.cents(paid, overtime, meters, plan.getRegularHourly(), plan.getOvertimeHourly(), plan.getMileagePerMile());
        return new Result(feasible, cents, Required.value(Map.copyOf(arrivals)), paid, overtime, drive, waiting, meters, buffer, Required.value(Map.copyOf(segments)));
    }

    private static RouteResult validateRoute(DayPlan plan, TechRoute route, Map<String, Instant> arrivals, List<WorkingSegment> segments) {
        Placement timing = new TimingValidation(plan, route).solve();
        if (timing == null) {
            RouteResult incomplete = infeasibleRoute(plan, route, arrivals, segments);
            return new RouteResult(false, incomplete.paid(), incomplete.overtime(), incomplete.drive(), incomplete.waiting(), incomplete.meters(), incomplete.buffer());
        }
        arrivals.putAll(timing.arrivals()); segments.addAll(timing.segments());
        boolean qualified = route.getVisits().stream().allMatch(visit -> route.getQualifiedServiceIds().contains(visit.getServiceId()));
        return new RouteResult(qualified, timing.paid(), timing.overtime(), timing.drive(), timing.waiting(), timing.meters(), timing.buffer());
    }

    private record Placement(long paid, long overtime, long drive, long waiting, long meters, long buffer,
            Map<String, Instant> arrivals, List<WorkingSegment> segments) { }
    private record Prefix(int interval, int nextVisit) { }
    private record Choice(int interval, int first, int end) { }

    /** Independent enumeration with resource dominance; scoring uses a separate forward dynamic program. */
    private static final class TimingValidation {
        final DayPlan plan;
        final TechRoute route;
        final List<Work> intervals;
        final Map<Choice, Optional<Placement>> choices = new HashMap<>();
        final Map<Prefix, List<Placement>> visited = new HashMap<>();
        @Nullable Placement best;
        TimingValidation(DayPlan plan, TechRoute route) { this.plan = plan; this.route = route; intervals = workingIntervals(route); }

        @Nullable Placement solve() {
            Placement empty = new Placement(0, 0, 0, 0, 0, 0, Required.value(Map.of()), Required.value(List.of()));
            if (route.getVisits().isEmpty()) return empty;
            if (intervals.size() == 1) {
                Placement single = place(0, 0, route.getVisits().size());
                return single != null && allowed(single) ? single : null;
            }
            explore(0, 0, empty);
            return best;
        }

        void explore(int interval, int next, Placement prefix) {
            SearchDeadline.checkpoint();
            if (next == route.getVisits().size()) {
                Placement current = best;
                if (current == null || compare(prefix, current) < 0) best = prefix;
                return;
            }
            Prefix key = new Prefix(interval, next);
            List<Placement> prior = visited.computeIfAbsent(key, _ -> new ArrayList<>());
            for (Placement value : prior) if (dominates(Required.value(value), prefix)) return;
            prior.removeIf(value -> dominates(prefix, Required.value(value))); prior.add(prefix);
            for (int block = interval; block < intervals.size(); block++) for (int end = next + 1; end <= route.getVisits().size(); end++) {
                SearchDeadline.checkpoint();
                Choice choice = new Choice(block, next, end);
                Optional<Placement> cached = choices.get(choice);
                if (cached == null) { cached = Optional.ofNullable(place(block, next, end)); choices.put(choice, cached); }
                Placement segment = cached.orElse(null);
                if (segment == null) continue;
                Map<String, Instant> arrivals = new LinkedHashMap<>(prefix.arrivals()); arrivals.putAll(segment.arrivals());
                List<WorkingSegment> segments = new ArrayList<>(prefix.segments()); segments.addAll(segment.segments());
                Placement combined = new Placement(prefix.paid() + segment.paid(), prefix.overtime() + segment.overtime(), prefix.drive() + segment.drive(),
                        prefix.waiting() + segment.waiting(), prefix.meters() + segment.meters(), Math.addExact(prefix.buffer(), segment.buffer()), arrivals, segments);
                if (allowed(combined)) explore(block + 1, end, combined);
            }
        }

        @Nullable Placement place(int interval, int first, int end) {
            Work work = Required.value(intervals.get(interval));
            PlanVisit initial = Required.value(route.getVisits().get(first));
            DayPlan.RoadLeg outbound = plan.getMatrix().get(route.getId() + ">" + initial.getId());
            PlanVisit last = Required.value(route.getVisits().get(end - 1));
            DayPlan.RoadLeg home = plan.getMatrix().get(last.getId() + ">" + route.getId() + ":return");
            if (outbound == null || home == null) return null;
            Instant departure = latest(work.start(), Required.value(initial.getWindowStart().minus(Duration.ofMinutes(travel(plan, outbound)))));
            Instant clock = departure;
            List<Instant> earliest = new ArrayList<>(); List<Long> waits = new ArrayList<>(); List<String> ids = new ArrayList<>();
            long driving = 0, waiting = 0, meters = 0;
            for (int index = first; index < end; index++) {
                PlanVisit visit = Required.value(route.getVisits().get(index));
                String previous = index == first ? route.getId() : route.getVisits().get(index - 1).getId();
                DayPlan.RoadLeg road = plan.getMatrix().get(previous + ">" + visit.getId());
                if (road == null) return null;
                long minutes = travel(plan, road);
                Instant reached = Required.value(clock.plus(Duration.ofMinutes(minutes)));
                Instant arrival = latest(reached, visit.getWindowStart());
                if (!arrival.isBefore(visit.getWindowEnd())) return null;
                clock = Required.value(arrival.plus(Duration.ofMinutes(visit.getDurationMinutes())));
                if (clock.isAfter(work.end())) return null;
                waiting += Duration.between(reached, arrival).toMinutes();
                driving += minutes; meters = Math.addExact(meters, road.meters()); earliest.add(arrival); waits.add(waiting); ids.add(visit.getId());
            }
            Instant returned = Required.value(clock.plus(Duration.ofMinutes(travel(plan, home))));
            if (returned.isAfter(work.end())) return null;
            // Backward latest-arrival bounds independently constrain the departure shift. A visit's bound is its buffer
            // deadline less one nanosecond (the window end without a buffer), or its earliest arrival when that is
            // already at or past the deadline: delaying the departure must never add late-arrival buffer seconds.
            PlanFacts.ArrivalBuffer reserve = plan.getArrivalBuffer();
            Instant latestArrival = Required.value(work.end().minus(Duration.ofMinutes(travel(plan, home) + last.getDurationMinutes())));
            for (int index = end - 1; index >= first; index--) {
                PlanVisit visit = Required.value(route.getVisits().get(index));
                Instant deadline = reserve.deadline(visit.getWindowStart(), visit.getWindowEnd());
                Instant reached = Required.value(earliest.get(index - first));
                Instant exclusive = reached.isBefore(deadline) ? Required.value(deadline.minusNanos(1)) : reached;
                if (exclusive.isBefore(latestArrival)) latestArrival = exclusive;
                if (index > first) {
                    PlanVisit previous = Required.value(route.getVisits().get(index - 1));
                    DayPlan.RoadLeg leg = Required.value(plan.getMatrix().get(previous.getId() + ">" + visit.getId()), "validated segment leg");
                    latestArrival = Required.value(latestArrival.minus(Duration.ofMinutes(previous.getDurationMinutes() + travel(plan, leg))));
                }
            }
            Instant latestDeparture = Required.value(latestArrival.minus(Duration.ofMinutes(travel(plan, outbound))));
            long delay = Math.max(0, Math.min(waiting, Duration.between(departure, latestDeparture).toMinutes()));
            Instant actualDeparture = Required.value(departure.plus(Duration.ofMinutes(delay)));
            Map<String, Instant> arrivals = new LinkedHashMap<>();
            for (int index = 0; index < ids.size(); index++) arrivals.put(ids.get(index), Required.value(earliest.get(index)
                    .plus(Duration.ofMinutes(Math.max(0, delay - waits.get(index))))));
            List<WorkingSegment> segments = new ArrayList<>(); segments.add(new WorkingSegment(actualDeparture, returned, Required.value(List.copyOf(ids))));
            long buffer = bufferSeconds(reserve, Required.value(route.getVisits().subList(first, end)), arrivals);
            return new Placement(Duration.between(actualDeparture, returned).toMinutes(), Math.max(0, Duration.between(latest(actualDeparture, route.getShiftEnd()), returned).toMinutes()),
                    driving + travel(plan, home), waiting - delay, meters + home.meters(), buffer, arrivals, segments);
        }

        boolean allowed(Placement value) { return value.paid() <= route.getMaxDailyMinutes() && value.overtime() <= route.getMaxOvertimeMinutes(); }
        java.math.BigDecimal cost(Placement value) {
            return Monetary.numerator(value.paid(), value.overtime(), value.meters(), plan.getRegularHourly(), plan.getOvertimeHourly(), plan.getMileagePerMile());
        }
        boolean dominates(Placement first, Placement second) {
            return first.paid() <= second.paid() && first.overtime() <= second.overtime() && first.waiting() <= second.waiting() && first.buffer() <= second.buffer()
                    && cost(first).compareTo(cost(second)) <= 0 && first.segments().size() == second.segments().size() && departureOrder(first, second) <= 0;
        }
        int compare(Placement first, Placement second) {
            int order = Long.compare(first.overtime(), second.overtime());
            if (order == 0) order = Long.compare(first.buffer(), second.buffer());
            if (order == 0) order = cost(first).compareTo(cost(second));
            if (order == 0) order = Long.compare(first.waiting(), second.waiting());
            if (order == 0) order = Long.compare(first.paid(), second.paid());
            return order == 0 ? departureOrder(first, second) : order;
        }
        int departureOrder(Placement first, Placement second) {
            for (int index = 0; index < Math.min(first.segments().size(), second.segments().size()); index++) {
                int order = first.segments().get(index).departure().compareTo(second.segments().get(index).departure());
                if (order != 0) return order;
            }
            return Integer.compare(first.segments().size(), second.segments().size());
        }
    }

    private static RouteResult infeasibleRoute(DayPlan plan, TechRoute route, Map<String, Instant> arrivals, List<WorkingSegment> segments) {
        List<Work> work = workingIntervals(route);
        long paid = 0, overtime = 0, drive = 0, waiting = 0, meters = 0;
        long segmentDrive = 0, segmentWaiting = 0, segmentMeters = 0;
        List<Timing> segmentTimings = new ArrayList<>();
        int interval = 0;
        Segment segment = null;
        boolean feasible = true;
        for (PlanVisit visit : route.getVisits()) {
            if (!route.getQualifiedServiceIds().contains(visit.getServiceId())) feasible = false;
            boolean placed = false;
            while (interval < work.size()) {
                Work shift = work.get(interval);
                String from = segment == null ? route.getId() : segment.previous();
                DayPlan.RoadLeg road = plan.getMatrix().get(from + ">" + visit.getId());
                DayPlan.RoadLeg returnHome = plan.getMatrix().get(visit.getId() + ">" + route.getId() + ":return");
                if (road == null || returnHome == null) { feasible = false; break; }
                long travel = travel(plan, road);
                Instant depart = segment == null ? latest(shift.start(), Required.value(visit.getWindowStart().minus(Duration.ofMinutes(travel)))) : segment.departure();
                Instant fromTime = segment == null ? depart : segment.done();
                Instant arrival = latest(Required.value(fromTime.plus(Duration.ofMinutes(travel))), visit.getWindowStart());
                Instant doneAtHome = arrival.plus(Duration.ofMinutes(visit.getDurationMinutes() + travel(plan, returnHome)));
                if (arrival.isBefore(visit.getWindowEnd()) && !doneAtHome.isAfter(shift.end())) {

                    segmentWaiting += Math.max(0, Duration.between(fromTime.plus(Duration.ofMinutes(travel)), arrival).toMinutes());
                    segmentTimings.add(new Timing(visit, arrival, segmentWaiting));
                    segmentDrive += travel; segmentMeters += road.meters();
                    segment = new Segment(visit.getId(), depart, Required.value(arrival.plus(Duration.ofMinutes(visit.getDurationMinutes())))); arrivals.put(visit.getId(), arrival);
                    placed = true;
                    break;
                }
                if (segment != null) {
                    DayPlan.RoadLeg home = plan.getMatrix().get(segment.previous() + ">" + route.getId() + ":return");
                    if (home == null) { feasible = false; break; }
                    Instant back = segment.done().plus(Duration.ofMinutes(travel(plan, home)));
                    if (back.isAfter(shift.end())) feasible = false;
                    long delay = delayDeparture(plan.getArrivalBuffer(), segmentTimings, segmentWaiting, arrivals);
                    Instant departure = Required.value(segment.departure().plus(Duration.ofMinutes(delay)));
                    segments.add(new WorkingSegment(departure, Required.value(back),
                            Required.value(segmentTimings.stream().<String>map((Timing timing) -> timing.visit().getId()).toList())));
                    paid = Math.addExact(paid, Duration.between(departure, back).toMinutes());
                    overtime = Math.addExact(overtime, Math.max(0, Duration.between(latest(departure, route.getShiftEnd()), back).toMinutes()));
                    drive += segmentDrive + travel(plan, home); waiting += segmentWaiting - delay; meters = Math.addExact(meters, segmentMeters + home.meters());
                    segmentTimings.clear();
                    segment = null;
                    segmentDrive = segmentWaiting = segmentMeters = 0;
                }
                interval++;
            }
            if (!placed) feasible = false;
        }
        if (segment != null) {
            DayPlan.RoadLeg home = plan.getMatrix().get(segment.previous() + ">" + route.getId() + ":return");
            if (home == null || interval >= work.size()) feasible = false;
            else {
                Instant back = segment.done().plus(Duration.ofMinutes(travel(plan, home)));
                if (back.isAfter(work.get(interval).end())) feasible = false;
                long delay = delayDeparture(plan.getArrivalBuffer(), segmentTimings, segmentWaiting, arrivals);
                Instant departure = Required.value(segment.departure().plus(Duration.ofMinutes(delay)));
                segments.add(new WorkingSegment(departure, Required.value(back),
                        Required.value(segmentTimings.stream().<String>map((Timing timing) -> timing.visit().getId()).toList())));
                paid = Math.addExact(paid, Duration.between(departure, back).toMinutes());
                overtime = Math.addExact(overtime, Math.max(0, Duration.between(latest(departure, route.getShiftEnd()), back).toMinutes()));
                drive += segmentDrive + travel(plan, home); waiting += segmentWaiting - delay; meters = Math.addExact(meters, segmentMeters + home.meters());
            }
        }
        if (paid > route.getMaxDailyMinutes() || overtime > route.getMaxOvertimeMinutes()) feasible = false;
        return new RouteResult(feasible, paid, overtime, drive, waiting, meters, bufferSeconds(plan.getArrivalBuffer(), route.getVisits(), arrivals));
    }

    // Absorb waiting without moving the segment's return time. The earliest equivalent
    // departure is the first point at which all removable waiting has disappeared. No arrival
    // moves past its buffer deadline, and one already at or past it does not move at all.
    private static long delayDeparture(PlanFacts.ArrivalBuffer reserve, List<Timing> timings, long waiting, Map<String, Instant> arrivals) {
        long delay = waiting;
        for (Timing timing : timings) {
            Instant deadline = reserve.deadline(timing.visit().getWindowStart(), timing.visit().getWindowEnd());
            long slack = timing.arrival().isBefore(deadline) ? Duration.between(timing.arrival(), deadline.minusNanos(1)).toMinutes() : 0;
            delay = Math.min(delay, slack + timing.waitingBefore());
        }
        delay = Math.max(0, delay);
        for (Timing timing : timings) arrivals.put(timing.visit().getId(), Required.value(timing.arrival()
                .plus(Duration.ofMinutes(Math.max(0, delay - timing.waitingBefore())))));
        return delay;
    }

    private static List<Work> workingIntervals(TechRoute route) {
        List<Work> work = new ArrayList<>();
        Instant end = route.getShiftEnd().plus(Duration.ofMinutes(route.getMaxOvertimeMinutes()));
        Instant cursor = route.getShiftStart();
        List<TechRoute.Unavailable> absences = new ArrayList<>(route.getUnavailable());
        absences.sort(Comparator.comparing((TechRoute.Unavailable absence) -> absence.start()));
        for (TechRoute.Unavailable absence : absences) {
            Instant beforeEnd = absence.start().isBefore(end) ? absence.start() : end;
            if (cursor.isBefore(beforeEnd)) work.add(new Work(cursor, Required.value(beforeEnd)));
            if (cursor.isBefore(absence.end())) cursor = absence.end();
            if (!cursor.isBefore(end)) break;
        }
        if (cursor.isBefore(end)) work.add(new Work(cursor, Required.value(end)));
        return work;
    }

    private static long travel(DayPlan plan, DayPlan.RoadLeg road) {
        return dev.waterflex.scheduler.Monetary.bufferedMinutes(road.seconds(), plan.getTravelBufferPct(), plan.getTravelBufferMinutes());
    }
    private static Instant latest(Instant a, Instant b) { return a.isAfter(b) ? a : b; }
}
