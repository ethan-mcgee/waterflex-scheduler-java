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
    static final class RefinementLimit extends RuntimeException { private static final long serialVersionUID = 1L; }
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
    private record Merit(long cost, BigDecimal fairness, int changes, String technician, int position, Arrangement arrangement) {
        static Merit of(Candidate candidate) {
            return new Merit(candidate.costDeltaCents(), candidate.fairnessDelta(), candidate.changedAssignments(),
                    candidate.technicianId(), candidate.insertionPosition(), candidate.arrangement());
        }
    }
    /** Delay route-map copies until a move is actually examined within the deadline. */
    private static final class Move {
        private final Arrangement base;
        private final String from, to;
        private final int index, position, kind;
        private @Nullable Arrangement applied;
        Move(Arrangement base, String from, int index, String to, int position, int kind) {
            this.base = base; this.from = from; this.index = index; this.to = to; this.position = position; this.kind = kind;
        }
        Arrangement apply() {
            Arrangement previous = applied;
            if (previous != null) return previous;
            Map<String, List<String>> copy = new TreeMap<>(base.routes());
            List<String> source = new ArrayList<>(Required.value(copy.get(from)));
            copy.put(from, source);
            List<String> target = from.equals(to) ? source : new ArrayList<>(Required.value(copy.get(to)));
            copy.put(to, target);
            if (kind == 0) {
                String visit = Required.value(source.remove(index));
                target.add(from.equals(to) && position > index ? position - 1 : position, visit);
            } else if (kind == 1) {
                String visit = Required.value(source.get(index));
                source.set(index, Required.value(target.get(position))); target.set(position, visit);
            } else Collections.reverse(source.subList(index, position));
            Arrangement result = new Arrangement(copy); applied = result; return result;
        }
    }
    private record RouteRank(long roadDeltaSeconds, int qualificationScarcity, long slackMinutes, double utilization) { }
    private final BookingSnapshot snapshot;
    private final Request request;
    private final Limits limits;
    private final Runnable checkpoint;
    private final Map<LocalDate, DayContext> contexts = new TreeMap<>();
    private final class DayContext {
        final Day day;
        final BookingEvaluation evaluation;
        final RouteEvaluator.Result baseline;
        final BigDecimal fairness;
        final List<String> eligible;
        @Nullable List<String> shortlist;
        final Map<Arrangement, List<Move>> neighborhoods = new LinkedHashMap<>(16, .75f, true) {
            @Override protected boolean removeEldestEntry(Map.@Nullable Entry<Arrangement, List<Move>> eldest) { return size() > 32; }
        };
        final Map<Arrangement, Optional<Ranked>> arrangements = new LinkedHashMap<>(128, .75f, true) {
            @Override protected boolean removeEldestEntry(Map.@Nullable Entry<Arrangement, Optional<Ranked>> eldest) { return size() > 1024; }
        };
        DayContext(LocalDate date) {
            day = Required.value(snapshot.days().get(date), "snapshot service date");
            evaluation = new BookingEvaluation(day, snapshot.rates(), request.serviceId(), checkpoint);
            baseline = evaluation.evaluate(day.baseline(), day.visits(), false);
            if (!baseline.feasible()) throw new BookingSnapshot.Incomplete("Reservation baseline is infeasible");
            fairness = evaluation.fairness(day.baseline(), day.visits());
            eligible = Required.value(day.technicians().values().stream().filter(t -> t.services().contains(request.serviceId())
                    && evaluation.capacity(t.id()) > 0).<String>map((Technician t) -> t.id()).sorted().toList());
        }
    }
    private DayContext context(LocalDate date) { return Required.value(contexts.computeIfAbsent(date, key -> new DayContext(Required.value(key)))); }
    public long evaluatedRoutes() { return contexts.values().stream().mapToLong(value -> value.evaluation.evaluations()).sum(); }
    public long reusedRoutes() { return contexts.values().stream().mapToLong(value -> value.evaluation.hits()).sum(); }
    public long prunedArrangements() { return contexts.values().stream().mapToLong(value -> value.evaluation.pruned()).sum(); }

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
        return search(windows, rearrangementEnabled, null);
    }

    Result refine(Result insertion) { return search(windows(), true, insertion); }

    private Result search(List<Window> windows, boolean rearrangementEnabled, @Nullable Result insertion) {
        if (new HashSet<>(windows).size() != windows.size()) throw new IllegalArgumentException("Duplicate booking window");
        if (!new HashSet<>(windows).equals(new HashSet<>(windows())))
            throw new BookingSnapshot.Incomplete("Search does not cover every eligible customer window");
        List<State> states = new ArrayList<>();
        boolean complete = false;
        String stopped = "COMPLETED";
        long confirmed = 0, capacity = 0;
        try {
            // Confirmed demand is measured once per eligible technician/date, across all services.
            if (insertion != null) {
                confirmed = insertion.confirmedRegularMinutes(); capacity = insertion.regularCapacityMinutes();
                complete = insertion.complete();
            } else for (var entry : snapshot.days().entrySet()) {
                checkpoint.run();
                DayContext context = context(Required.value(entry.getKey()));
                Day day = context.day;
                Arrangement actual = day.actualArrangement();
                for (Technician technician : day.technicians().values()) {
                    if (!technician.services().contains(request.serviceId())) continue;
                    long available = context.evaluation.capacity(technician.id());
                    if (available == 0) continue;
                    var measured = context.evaluation.route(technician.id(), Required.value(actual.routes().get(technician.id())), day.visits(), true);
                    if (!measured.feasible()) throw new BookingSnapshot.Incomplete("Confirmed route is infeasible");
                    capacity = Math.addExact(capacity, available);
                    confirmed = Math.addExact(confirmed, Math.min(available, measured.paidMinutes() - measured.overtimeMinutes()));
                }
            }
            for (Window window : windows) {
                State state = new State(Required.value(window));
                if (insertion != null) {
                    state.insertionCursor = state.eligible.size(); state.examinedRoutes.addAll(state.eligible);
                    insertion.candidates().stream().filter(candidate -> candidate.window().equals(window)).forEach(state.candidates::add);
                }
                states.add(state);
            }
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
            // Abundant insertion choices finish the scarcity pass. Remaining bounded work may
            // improve cost/fairness, but its timeout cannot erase that completed pass or its offers.
            complete = regular > snapshot.policy().regularWindowThreshold();
            if (!complete || rearrangementEnabled) {
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
            }
        } catch (SearchDeadline.Expired expired) { stopped = "DEADLINE"; }
          catch (RefinementLimit limited) { stopped = "REFINEMENT_TIME_LIMIT"; }
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
        final BookingEvaluation evaluation;
        final RouteEvaluator.Result baseline;
        final BigDecimal baselineFairness;
        final List<String> eligible;
        final Set<String> examinedRoutes = new HashSet<>();
        final List<Candidate> candidates = new ArrayList<>();
        final Set<Arrangement> seen = new HashSet<>();
        final List<Ranked> ranked = new ArrayList<>();
        List<String> shortlist = new ArrayList<>();
        Iterator<Move> pending = Required.value(Collections.emptyIterator());
        final Map<String, Double> utilizations = new HashMap<>();
        int insertionCursor, arrangements = 1, generated, evaluations, depth;
        String reason = "INSERTION_COMPLETED";

        State(Window window) {
            this.window = window;
            DayContext context = context(window.day());
            day = context.day; evaluation = context.evaluation;
            baseline = context.baseline; baselineFairness = context.fairness; eligible = context.eligible;
            seen.add(day.baseline());
        }

        void insert(Arrangement arrangement, List<String> targets, String source) {
            int changedAssignments = changes(arrangement);
            for (String tech : targets) {
                Technician technician = Required.value(day.technicians().get(tech), "insertion technician");
                if (!technician.services().contains(request.serviceId()) || evaluation.capacity(technician.id()) == 0) continue;
                List<String> route = Required.value(arrangement.routes().get(tech), "insertion route");
                var visit = new Visit(request.jobId(), request.jobId(), request.serviceId(), window.start(), window.end(),
                        request.durationMinutes(), request.location(), Required.value(tech), window.start(), false);
                Map<String, Visit> facts = new InsertionFacts(day, visit);
                for (int position = 0; position <= route.size(); position++) {
                    checkpoint.run();
                    evaluations++;
                    if (!evaluation.possibleInsertion(Required.value(tech), route, visit, position)) continue;
                    Arrangement proposal = arrangement.insert(Required.value(tech), visit.id(), position);
                    var result = evaluation.metrics(proposal, facts, false);
                    if (!result.feasible()) continue;
                    if (evaluation.hasReservations() && !evaluation.metrics(proposal, facts, true).feasible()) continue;
                    BigDecimal fairness = evaluation.fairness(proposal, facts);
                    var merit = new Merit(result.costCents() - baseline.costCents(), Required.value(fairness.subtract(baselineFairness)),
                            changedAssignments, Required.value(tech), position, proposal);
                    retain(merit, result.overtimeMinutes() - baseline.overtimeMinutes(), source, facts);
                }
            }
        }

        void retain(Merit merit, long overtimeDelta, String source, Map<String, Visit> facts) {
            // A falling reference cost can only tighten the ceiling. Proven dominated
            // candidates cannot become the policy winner later in this same snapshot.
            long overtime = candidates.isEmpty() ? overtimeDelta : candidates.getFirst().overtimeDelta();
            if (overtimeDelta > overtime) return;
            long reference = overtimeDelta < overtime ? merit.cost() : Math.min(merit.cost(), candidates.stream()
                    .mapToLong((Candidate item) -> item.costDeltaCents()).min().orElse(merit.cost()));
            long ceiling = snapshot.policy().costCeiling(reference);
            if (merit.cost() > ceiling) return;
            if (overtimeDelta == overtime)
                for (Candidate item : candidates) if (dominates(Merit.of(Required.value(item)), merit)) return;
            // Keep the previously validated frontier if a deadline interrupts materialization.
            var validation = evaluation.evaluate(merit.arrangement(), facts, false);
            if (!validation.feasible()) throw new BookingSnapshot.Incomplete("Ranking and validation disagree");
            if (overtimeDelta < overtime) candidates.clear();
            candidates.removeIf(item -> item.costDeltaCents() > ceiling);
            candidates.removeIf(item -> dominates(merit, Merit.of(Required.value(item))));
            candidates.add(new Candidate(window, merit.technician(), merit.arrangement(), overtimeDelta, merit.cost(),
                    merit.fairness(), merit.changes(), merit.position(), source, validation));
        }

        boolean dominates(Merit first, Merit second) {
            if (first.cost() > second.cost() || first.fairness().compareTo(second.fairness()) > 0) return false;
            if (first.cost() < second.cost() || first.fairness().compareTo(second.fairness()) < 0) return true;
            if (first.changes() != second.changes()) return first.changes() < second.changes();
            int technician = first.technician().compareTo(second.technician());
            if (technician != 0) return technician < 0;
            if (first.position() != second.position()) return first.position() < second.position();
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
            var shared = context(window.day());
            List<String> cached = shared.shortlist;
            if (cached != null) { shortlist = cached; return; }
            // Idle, qualified capacity must not disappear from a cost-oriented shortlist.
            List<String> all = new ArrayList<>(day.technicians().keySet());
            all.removeIf(id -> evaluation.capacity(Required.value(id)) == 0);
            Map<String, RouteRank> ranks = new HashMap<>();
            for (String id : all) ranks.put(id, rank(Required.value(id)));
            all.sort(Comparator.comparingLong((String id) -> Required.value(ranks.get(id)).roadDeltaSeconds())
                    .thenComparingInt(id -> Required.value(ranks.get(id)).qualificationScarcity())
                    .thenComparing(Comparator.comparingLong((String id) -> Required.value(ranks.get(id)).slackMinutes()).reversed())
                    .thenComparingDouble(id -> Required.value(ranks.get(id)).utilization()).thenComparing(id -> id));
            String least = eligible.stream().min(Comparator.comparingDouble((String id) -> utilization(Required.value(id))).thenComparing(id -> id)).orElse(null);
            if (least != null) { all.remove(least); all.addFirst(least); }
            shortlist = Required.value(List.copyOf(all.subList(0, Math.min(limits.routes(), all.size()))));
            shared.shortlist = shortlist;
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
                    .filter(tech -> evaluation.capacity(tech.id()) > 0 && tech.services().contains(service)).count());
            long capacity = evaluation.capacity(id);
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
            long capacity = evaluation.capacity(id);
            if (capacity == 0) return Double.POSITIVE_INFINITY;
            double value = evaluation.route(id, Required.value(day.baseline().routes().get(id)), day.visits(), true).paidMinutes() / (double) capacity;
            utilizations.put(id, value);
            return value;
        }

        boolean step() {
            if (arrangements >= limits.arrangementsPerWindow()) { reason = "ARRANGEMENT_LIMIT"; return false; }
            while (!pending.hasNext()) {
                if (depth >= limits.depth() || ranked.isEmpty()) return false;
                ranked.sort(Comparator.comparingLong((Ranked r) -> r.overtime()).thenComparingLong(r -> r.cost())
                        .thenComparing(r -> r.fairness()).thenComparing(r -> r.arrangement().signature()));
                List<Move> next = new ArrayList<>();
                for (Ranked retained : ranked.subList(0, Math.min(limits.beam(), ranked.size())))
                    next.addAll(neighbors(retained.arrangement()));
                ranked.clear();
                depth++;
                pending = Required.value(next.iterator());
            }
            Arrangement arrangement = Required.value(pending.next()).apply();
            if (!seen.add(arrangement)) return true;
            arrangements++;
            var shared = context(window.day());
            Optional<Ranked> cached = shared.arrangements.get(arrangement);
            if (cached == null) {
                cached = Optional.empty();
                if (evaluation.possible(arrangement, day.visits())) {
                    var result = evaluation.evaluate(arrangement, day.visits(), false);
                    if (result.feasible() && evaluation.evaluate(arrangement, day.visits(), true).feasible())
                        cached = Optional.of(new Ranked(arrangement, result.overtimeMinutes(), result.costCents(), evaluation.fairness(arrangement, day.visits())));
                }
                shared.arrangements.put(arrangement, Required.value(cached));
            }
            if (cached.isPresent()) { ranked.add(cached.orElseThrow()); insert(arrangement, shortlist, "REARRANGEMENT"); }
            return true;
        }

        List<Move> neighbors(Arrangement base) {
            var shared = context(window.day());
            List<Move> cached = shared.neighborhoods.get(base);
            if (cached != null) { generated += cached.size(); return cached; }
            List<Move> relocation = new ArrayList<>(), swaps = new ArrayList<>(), reversals = new ArrayList<>();
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
                            relocation.add(new Move(base, Required.value(from), index, Required.value(to), position, 0));
                        }
                        for (int position = 0; position < target.size() && swaps.size() < cap; position++) {
                            String other = Required.value(target.get(position));
                            if (id.equals(other) || !qualifies(Required.value(to), id) || !qualifies(Required.value(from), other)) continue;
                            swaps.add(new Move(base, Required.value(from), index, Required.value(to), position, 1));
                        }
                    }
                    for (int end = index + 2; end <= source.size() && reversals.size() < cap; end++) {
                        reversals.add(new Move(base, Required.value(from), index, Required.value(from), end, 2));
                    }
                }
            }
            List<Move> result = new ArrayList<>();
            for (int index = 0; index < cap && result.size() < cap; index++) {
                if (index < relocation.size()) result.add(Required.value(relocation.get(index)));
                if (index < swaps.size() && result.size() < cap) result.add(Required.value(swaps.get(index)));
                if (index < reversals.size() && result.size() < cap) result.add(Required.value(reversals.get(index)));
            }
            generated += result.size();
            shared.neighborhoods.put(base, Required.value(List.copyOf(result)));
            return result;
        }
        boolean qualifies(String technician, String visit) {
            return Required.value(day.technicians().get(technician)).services().contains(Required.value(day.visits().get(visit)).serviceId());
        }
    }

}
