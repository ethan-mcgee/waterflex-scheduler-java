package dev.waterflex.scheduler;

import dev.waterflex.scheduler.BookingSnapshot.*;
import dev.waterflex.scheduler.optimizer.RouteEvaluator;
import dev.waterflex.scheduler.optimizer.SchedulingPolicy;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import org.jspecify.annotations.Nullable;

/** Deterministic, bounded, same-date search. All inputs are immutable and already routed. */
public final class BoundedBookingSearch {
    public record Limits(int routes, int depth, int beam, int arrangementsPerWindow) {
        public Limits {
            if (routes < 1 || routes > 6 || depth < 1 || depth > 2 || beam < 1 || beam > 8
                    || arrangementsPerWindow < 1 || arrangementsPerWindow > 500)
                throw new IllegalArgumentException("Invalid booking neighborhood limits");
        }
        public static Limits defaults() { return new Limits(6, 2, 8, 500); }
    }
    public record Request(String jobId, String serviceId, int durationMinutes, RoadClient.Point location) {
        public Request {
            if (jobId.isBlank() || serviceId.isBlank() || durationMinutes <= 0)
                throw new IllegalArgumentException("Invalid booking request");
        }
    }
    public record Window(LocalDate day, Instant start, Instant end) {
        public Window { if (!start.isBefore(end)) throw new IllegalArgumentException("Invalid booking window"); }
    }
    public record Candidate(Window window, String technicianId, Arrangement arrangement,
            long overtimeDelta, long costDeltaCents, BigDecimal fairnessDelta, int changedAssignments,
            int insertionPosition, String source, RouteEvaluator.Result validation) { }
    public record Coverage(Window window, int routesExamined, int arrangementsExamined, int movesGenerated,
                           int candidateEvaluations, boolean complete, String stopReason) { }
    public record Result(List<Candidate> candidates, List<Coverage> coverage, boolean complete,
                         int distinctRegularWindows, long confirmedRegularMinutes, long regularCapacityMinutes,
                         boolean overtimeAuthorized, String stopReason) { }
    private record Ranked(Arrangement arrangement, long overtime, long cost, BigDecimal fairness) { }
    private record RouteRank(long roadDeltaSeconds, int qualificationScarcity, long slackMinutes, double utilization) { }
    private final BookingSnapshot snapshot;
    private final Request request;
    private final Limits limits;
    private final Runnable checkpoint;

    public BoundedBookingSearch(BookingSnapshot snapshot, Request request, Limits limits, Runnable checkpoint) {
        this.snapshot = snapshot; this.request = request; this.limits = limits; this.checkpoint = checkpoint;
    }

    public List<Window> windows() {
        Set<Window> windows = new HashSet<>();
        var chicago = java.time.ZoneId.of("America/Chicago");
        for (var entry : snapshot.days().entrySet()) for (Technician technician : entry.getValue().technicians().values()) {
            if (!technician.services().contains(request.serviceId()) || technician.regularCapacity() == 0) continue;
            var start = technician.shiftStart().atZone(chicago);
            var end = technician.shiftEnd().atZone(chicago);
            int first = start.getHour() * 60 + start.getMinute();
            int last = end.toLocalDate().isAfter(entry.getKey()) ? 1440 : end.getHour() * 60 + end.getMinute();
            for (int minute : BookingService.windowStartMinutes(first, last)) {
                Instant instant = ScheduleCutoff.localMinute(Required.value(entry.getKey()), minute, false);
                windows.add(new Window(Required.value(entry.getKey()), instant, Required.value(instant.plusSeconds(7200))));
            }
        }
        return Required.value(windows.stream().sorted(Comparator.comparing((Window window) -> window.day())
                .thenComparing(window -> window.start()).thenComparing(window -> window.end())).toList());
    }

    public Result search(boolean rearrangementEnabled) { return search(windows(), rearrangementEnabled); }

    public Map<LocalDate, Set<String>> neighborhoodRoutes() {
        Map<LocalDate, Set<String>> result = new TreeMap<>();
        for (Window window : windows()) if (!result.containsKey(window.day())) {
            checkpoint.run();
            State state = new State(Required.value(window));
            state.chooseRoutes();
            result.put(window.day(), Required.value(Set.copyOf(state.shortlist)));
        }
        return Required.value(Map.copyOf(result));
    }

    public Result search(List<Window> windows, boolean rearrangementEnabled) {
        if (new HashSet<>(windows).size() != windows.size()) throw new IllegalArgumentException("Duplicate booking window");
        if (!new HashSet<>(windows).equals(new HashSet<>(windows())))
            throw new BookingSnapshot.Incomplete("Search does not cover every eligible customer window");
        List<State> states = new ArrayList<>();
        boolean complete = false;
        String stopped = "COMPLETED";
        long confirmed = 0, capacity = 0;
        try {
            // Confirmed demand is measured once per eligible technician/date, across all services.
            for (var entry : snapshot.days().entrySet()) {
                checkpoint.run();
                Day day = Required.value(entry.getValue());
                var confirmedPlan = day.plan(day.baseline(), day.visits(), snapshot.rates(), true);
                for (var route : confirmedPlan.getRoutes()) {
                    if (!route.getQualifiedServiceIds().contains(request.serviceId())) continue;
                    long available = SchedulingPolicy.regularCapacity(Required.value(route));
                    if (available == 0) continue;
                    var plan = new dev.waterflex.scheduler.optimizer.DayPlan(Required.value(List.of(route)), route.getVisits(),
                            confirmedPlan.getMatrix(), confirmedPlan.getRegularHourly(), confirmedPlan.getOvertimeHourly(),
                            confirmedPlan.getMileagePerMile(), confirmedPlan.getTravelBufferPct(), confirmedPlan.getTravelBufferMinutes());
                    var measured = RouteEvaluator.evaluate(plan);
                    if (!measured.feasible()) throw new BookingSnapshot.Incomplete("Confirmed route is infeasible");
                    capacity = Math.addExact(capacity, available);
                    confirmed = Math.addExact(confirmed, Math.min(available, measured.paidMinutes() - measured.overtimeMinutes()));
                }
            }
            for (Window window : windows) states.add(new State(Required.value(window)));
            // One route per window per round gives every date an insertion opportunity.
            boolean work;
            do {
                work = false;
                for (State state : states) {
                    checkpoint.run();
                    if (state.insertionCursor < state.eligible.size()) {
                        String tech = Required.value(state.eligible.get(state.insertionCursor++));
                        state.insert(state.day.baseline(), Required.value(List.of(tech)), "INSERTION");
                        state.examinedRoutes.add(tech);
                        work = true;
                    }
                }
            } while (work);
            int regular = regularWindows(states);
            if (regular <= snapshot.policy().regularWindowThreshold()) {
                if (!rearrangementEnabled) stopped = "REARRANGEMENT_DISABLED";
                else {
                    for (State state : states) state.startNeighborhood();
                    do {
                        work = false;
                        for (State state : states) {
                            checkpoint.run();
                            if (state.step()) work = true;
                        }
                    } while (work);
                    complete = true;
                }
            } else complete = true;
        } catch (SearchDeadline.Expired expired) { stopped = "DEADLINE"; }
        List<Candidate> candidates = new ArrayList<>();
        List<Coverage> coverage = new ArrayList<>();
        for (State state : states) {
            candidates.addAll(state.candidates);
            coverage.add(new Coverage(state.window, state.examinedRoutes.size(), state.arrangements, state.generated,
                    state.evaluations, complete, complete ? state.reason : stopped));
        }
        Set<Window> reported = new HashSet<>();
        coverage.forEach(item -> reported.add(item.window()));
        for (Window window : windows) if (!reported.contains(window))
            coverage.add(new Coverage(Required.value(window), 0, 0, 0, 0, false, stopped));
        int regular = regularWindows(states);
        return new Result(Required.value(List.copyOf(candidates)), Required.value(List.copyOf(coverage)), complete, regular,
                confirmed, capacity, snapshot.policy().authorizeOvertime(regular, confirmed, capacity, complete), stopped);
    }

    /** Reapply the reference ceiling to all validated candidates, never to an arbitrary dollar winner. */
    public static @Nullable Candidate choose(List<Candidate> candidates, SchedulingPolicy.Rules policy) {
        if (candidates.isEmpty()) return null;
        long overtime = candidates.stream().mapToLong((Candidate c) -> c.overtimeDelta()).min().orElseThrow();
        long reference = candidates.stream().filter(c -> c.overtimeDelta() == overtime)
                .mapToLong((Candidate c) -> c.costDeltaCents()).min().orElseThrow();
        long ceiling = policy.costCeiling(reference);
        return candidates.stream().filter(c -> c.overtimeDelta() == overtime && c.costDeltaCents() <= ceiling)
                .min(Comparator.comparing((Candidate c) -> c.fairnessDelta()).thenComparingLong(c -> c.costDeltaCents())
                        .thenComparingInt(c -> c.changedAssignments()).thenComparing(c -> c.window().start())
                        .thenComparing(c -> c.technicianId()).thenComparingInt(c -> c.insertionPosition())
                        .thenComparing(c -> c.arrangement().signature())).orElseThrow();
    }

    private static int regularWindows(List<State> states) {
        return (int) states.stream().filter(state -> state.candidates.stream().anyMatch(c -> c.overtimeDelta() <= 0)).count();
    }

    private final class State {
        final Window window;
        final Day day;
        final RouteEvaluator.Result baseline;
        final BigDecimal baselineFairness;
        final List<String> eligible;
        final Set<String> examinedRoutes = new HashSet<>();
        final List<Candidate> candidates = new ArrayList<>();
        final Set<Arrangement> seen = new HashSet<>();
        final List<Ranked> ranked = new ArrayList<>();
        List<String> shortlist = new ArrayList<>();
        Iterator<Arrangement> pending = Required.value(Collections.emptyIterator());
        final Map<String, Double> utilizations = new HashMap<>();
        int insertionCursor, arrangements = 1, generated, evaluations, depth;
        String reason = "INSERTION_COMPLETED";

        State(Window window) {
            this.window = window;
            day = Required.value(snapshot.days().get(window.day()), "snapshot service date");
            baseline = day.evaluate(day.baseline(), day.visits(), snapshot.rates());
            if (!baseline.feasible()) throw new BookingSnapshot.Incomplete("Reservation baseline is infeasible");
            baselineFairness = fairness(day.plan(day.baseline(), day.visits(), snapshot.rates(), true));
            eligible = Required.value(day.technicians().values().stream().filter(t -> t.services().contains(request.serviceId())
                            && t.regularCapacity() > 0).map((Technician t) -> t.id()).sorted().toList());
            seen.add(day.baseline());
        }

        void insert(Arrangement arrangement, List<String> targets, String source) {
            for (String tech : targets) {
                Technician technician = Required.value(day.technicians().get(tech), "insertion technician");
                if (!technician.services().contains(request.serviceId()) || technician.regularCapacity() == 0) continue;
                List<String> route = Required.value(arrangement.routes().get(tech), "insertion route");
                for (int position = 0; position <= route.size(); position++) {
                    checkpoint.run();
                    evaluations++;
                    var visit = new Visit(request.jobId(), request.jobId(), request.serviceId(), window.start(), window.end(),
                            request.durationMinutes(), request.location(), Required.value(tech), window.start(), false);
                    Map<String, Visit> facts = new HashMap<>(day.visits());
                    if (facts.putIfAbsent(visit.id(), visit) != null) throw new BookingSnapshot.Incomplete("Request already present in snapshot");
                    Arrangement proposal = arrangement.insert(Required.value(tech), visit.id(), position);
                    var result = day.evaluate(proposal, facts, snapshot.rates());
                    if (!result.feasible()) continue;
                    var confirmed = day.plan(proposal, facts, snapshot.rates(), true);
                    if (!RouteEvaluator.evaluate(confirmed).feasible()) continue;
                    BigDecimal fairness = fairness(confirmed);
                    retain(new Candidate(window, Required.value(tech), proposal, result.overtimeMinutes() - baseline.overtimeMinutes(),
                            result.costCents() - baseline.costCents(), Required.value(fairness.subtract(baselineFairness)),
                            changes(arrangement), position, source, result));
                }
            }
        }

        void retain(Candidate candidate) {
            // A falling reference cost can only tighten the ceiling. Proven dominated
            // candidates cannot become the policy winner later in this same snapshot.
            long overtime = candidates.isEmpty() ? candidate.overtimeDelta() : candidates.getFirst().overtimeDelta();
            if (candidate.overtimeDelta() > overtime) return;
            if (candidate.overtimeDelta() < overtime) candidates.clear();
            long reference = Math.min(candidate.costDeltaCents(), candidates.stream()
                    .mapToLong((Candidate item) -> item.costDeltaCents()).min().orElse(candidate.costDeltaCents()));
            long ceiling = snapshot.policy().costCeiling(reference);
            candidates.removeIf(item -> item.costDeltaCents() > ceiling);
            if (candidate.costDeltaCents() > ceiling) return;
            for (Candidate item : candidates) if (dominates(Required.value(item), candidate)) return;
            candidates.removeIf(item -> dominates(candidate, Required.value(item)));
            candidates.add(candidate);
        }

        boolean dominates(Candidate first, Candidate second) {
            if (first.costDeltaCents() > second.costDeltaCents() || first.fairnessDelta().compareTo(second.fairnessDelta()) > 0) return false;
            if (first.costDeltaCents() < second.costDeltaCents() || first.fairnessDelta().compareTo(second.fairnessDelta()) < 0) return true;
            if (first.changedAssignments() != second.changedAssignments()) return first.changedAssignments() < second.changedAssignments();
            int technician = first.technicianId().compareTo(second.technicianId());
            if (technician != 0) return technician < 0;
            if (first.insertionPosition() != second.insertionPosition()) return first.insertionPosition() < second.insertionPosition();
            return first.arrangement().signature().compareTo(second.arrangement().signature()) <= 0;
        }

        int changes(Arrangement arrangement) {
            int count = 0;
            for (var entry : arrangement.routes().entrySet()) for (String id : entry.getValue()) {
                Visit visit = Required.value(day.visits().get(id), "existing visit");
                if (!visit.reservation() && !visit.originalTechnicianId().equals(entry.getKey())) count++;
            }
            return count;
        }

        void startNeighborhood() {
            chooseRoutes();
            examinedRoutes.addAll(shortlist);
            depth = 1;
            pending = Required.value(neighbors(day.baseline()).iterator());
            reason = "NEIGHBORHOOD_COMPLETED";
        }

        void chooseRoutes() {
            // Idle, qualified capacity must not disappear from a cost-oriented shortlist.
            List<String> all = new ArrayList<>(day.technicians().keySet());
            all.removeIf(id -> Required.value(day.technicians().get(id)).regularCapacity() == 0);
            Map<String, RouteRank> ranks = new HashMap<>();
            for (String id : all) ranks.put(id, rank(Required.value(id)));
            all.sort(Comparator.comparingLong((String id) -> Required.value(ranks.get(id)).roadDeltaSeconds())
                    .thenComparingInt(id -> Required.value(ranks.get(id)).qualificationScarcity())
                    .thenComparing(Comparator.comparingLong((String id) -> Required.value(ranks.get(id)).slackMinutes()).reversed())
                    .thenComparingDouble(id -> Required.value(ranks.get(id)).utilization()).thenComparing(id -> id));
            String least = eligible.stream().min(Comparator.comparingDouble((String id) -> utilization(Required.value(id))).thenComparing(id -> id)).orElse(null);
            if (least != null) { all.remove(least); all.addFirst(least); }
            shortlist = new ArrayList<>(all.subList(0, Math.min(limits.routes(), all.size())));
        }

        RouteRank rank(String id) {
            checkpoint.run();
            List<String> stops = Required.value(day.baseline().routes().get(id));
            long roadDelta = Long.MAX_VALUE;
            for (int position = 0; position <= stops.size(); position++) {
                String from = position == 0 ? id : Required.value(stops.get(position - 1));
                String to = position == stops.size() ? id + ":return" : Required.value(stops.get(position));
                long incoming = seconds(from, request.jobId()), outgoing = seconds(request.jobId(), to);
                long removed = stops.isEmpty() ? 0 : seconds(from, to);
                if (incoming != Long.MAX_VALUE && outgoing != Long.MAX_VALUE && removed != Long.MAX_VALUE)
                    roadDelta = Math.min(roadDelta, incoming + outgoing - removed);
            }
            Set<String> services = new HashSet<>();
            services.add(request.serviceId());
            for (String stop : stops) services.add(Required.value(day.visits().get(stop)).serviceId());
            int scarcity = Integer.MAX_VALUE;
            for (String service : services) scarcity = Math.min(scarcity, (int) day.technicians().values().stream()
                    .filter(tech -> tech.regularCapacity() > 0 && tech.services().contains(service)).count());
            long capacity = Required.value(day.technicians().get(id)).regularCapacity();
            double utilized = utilization(id);
            return new RouteRank(roadDelta, scarcity, capacity - Math.round(utilized * capacity), utilized);
        }

        long seconds(String from, String to) {
            day.roads().require(from, to);
            var leg = day.roads().legs().get(from + ">" + to);
            return leg == null ? Long.MAX_VALUE : leg.seconds();
        }

        double utilization(String id) {
            Double cached = utilizations.get(id);
            if (cached != null) return cached;
            Technician tech = Required.value(day.technicians().get(id), "shortlist technician");
            long capacity = tech.regularCapacity();
            if (capacity == 0) return Double.POSITIVE_INFINITY;
            var plan = day.plan(day.baseline(), day.visits(), snapshot.rates(), true);
            var route = plan.getRoutes().stream().filter(r -> r.getId().equals(id)).findFirst().orElseThrow();
            var single = new dev.waterflex.scheduler.optimizer.DayPlan(Required.value(List.of(route)), route.getVisits(), plan.getMatrix(),
                    plan.getRegularHourly(), plan.getOvertimeHourly(), plan.getMileagePerMile(), plan.getTravelBufferPct(), plan.getTravelBufferMinutes());
            double value = RouteEvaluator.evaluate(single).paidMinutes() / (double) capacity;
            utilizations.put(id, value);
            return value;
        }

        BigDecimal fairness(dev.waterflex.scheduler.optimizer.DayPlan plan) {
            Set<String> services = new HashSet<>();
            services.add(request.serviceId());
            plan.getVisits().forEach(visit -> services.add(visit.getServiceId()));
            List<SchedulingPolicy.Workload> workload = new ArrayList<>();
            for (var route : plan.getRoutes()) {
                long capacity = SchedulingPolicy.regularCapacity(Required.value(route));
                if (capacity == 0 || Collections.disjoint(services, route.getQualifiedServiceIds())) continue;
                var single = new dev.waterflex.scheduler.optimizer.DayPlan(Required.value(List.of(route)), route.getVisits(), plan.getMatrix(),
                        plan.getRegularHourly(), plan.getOvertimeHourly(), plan.getMileagePerMile(), plan.getTravelBufferPct(), plan.getTravelBufferMinutes());
                var metrics = RouteEvaluator.evaluate(single);
                if (!metrics.feasible()) throw new BookingSnapshot.Incomplete("Confirmed workload cannot be evaluated");
                workload.add(new SchedulingPolicy.Workload(route.getId(), metrics.paidMinutes(), capacity, Required.value(BigDecimal.ZERO)));
            }
            return SchedulingPolicy.fairness(workload).variance();
        }

        boolean step() {
            if (arrangements >= limits.arrangementsPerWindow()) { reason = "ARRANGEMENT_LIMIT"; return false; }
            while (!pending.hasNext()) {
                if (depth >= limits.depth() || ranked.isEmpty()) return false;
                ranked.sort(Comparator.comparingLong((Ranked r) -> r.overtime()).thenComparingLong(r -> r.cost())
                        .thenComparing(r -> r.fairness()).thenComparing(r -> r.arrangement().signature()));
                List<Arrangement> next = new ArrayList<>();
                for (Ranked retained : ranked.subList(0, Math.min(limits.beam(), ranked.size())))
                    next.addAll(neighbors(retained.arrangement()));
                ranked.clear();
                depth++;
                pending = Required.value(next.iterator());
            }
            Arrangement arrangement = Required.value(pending.next());
            if (!seen.add(arrangement)) return true;
            arrangements++;
            var result = day.evaluate(arrangement, day.visits(), snapshot.rates());
            if (result.feasible()) {
                var confirmed = day.plan(arrangement, day.visits(), snapshot.rates(), true);
                if (RouteEvaluator.evaluate(confirmed).feasible()) {
                    ranked.add(new Ranked(arrangement, result.overtimeMinutes(), result.costCents(), fairness(confirmed)));
                    insert(arrangement, shortlist, "REARRANGEMENT");
                }
            }
            return true;
        }

        List<Arrangement> neighbors(Arrangement base) {
            List<Arrangement> relocation = new ArrayList<>(), swaps = new ArrayList<>(), reversals = new ArrayList<>();
            int cap = limits.arrangementsPerWindow();
            for (String from : shortlist) {
                List<String> source = Required.value(base.routes().get(from), "source route");
                for (int index = 0; index < source.size(); index++) {
                    checkpoint.run();
                    String id = Required.value(source.get(index));
                    for (String to : shortlist) {
                        List<String> target = Required.value(base.routes().get(to), "target route");
                        if (qualifies(Required.value(to), id)) for (int position = 0; position <= target.size() && relocation.size() < cap; position++) {
                            if (from.equals(to) && (position == index || position == index + 1)) continue;
                            Map<String, List<String>> copy = mutable(base);
                            Required.value(copy.get(from)).remove(index);
                            Required.value(copy.get(to)).add(from.equals(to) && position > index ? position - 1 : position, id);
                            relocation.add(new Arrangement(copy));
                        }
                        for (int position = 0; position < target.size() && swaps.size() < cap; position++) {
                            String other = Required.value(target.get(position));
                            if (id.equals(other) || !qualifies(Required.value(to), id) || !qualifies(Required.value(from), other)) continue;
                            Map<String, List<String>> copy = mutable(base);
                            Required.value(copy.get(from)).set(index, other);
                            Required.value(copy.get(to)).set(position, id);
                            swaps.add(new Arrangement(copy));
                        }
                    }
                    for (int end = index + 2; end <= source.size() && reversals.size() < cap; end++) {
                        Map<String, List<String>> copy = mutable(base);
                        Collections.reverse(Required.value(copy.get(from)).subList(index, end));
                        reversals.add(new Arrangement(copy));
                    }
                }
            }
            List<Arrangement> result = new ArrayList<>();
            for (int index = 0; index < cap && result.size() < cap; index++) {
                if (index < relocation.size()) result.add(Required.value(relocation.get(index)));
                if (index < swaps.size() && result.size() < cap) result.add(Required.value(swaps.get(index)));
                if (index < reversals.size() && result.size() < cap) result.add(Required.value(reversals.get(index)));
            }
            generated += result.size();
            return result;
        }
        boolean qualifies(String technician, String visit) {
            return Required.value(day.technicians().get(technician)).services().contains(Required.value(day.visits().get(visit)).serviceId());
        }
    }

    private static Map<String, List<String>> mutable(Arrangement arrangement) {
        Map<String, List<String>> copy = new TreeMap<>();
        arrangement.routes().forEach((id, visits) -> copy.put(id, new ArrayList<>(visits)));
        return copy;
    }
}
