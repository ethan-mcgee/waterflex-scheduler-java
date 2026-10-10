package dev.waterflex.scheduler;
import dev.waterflex.scheduler.BookingSnapshot.*;
import dev.waterflex.scheduler.optimizer.*;
import java.time.*;
import java.util.*;
import org.jspecify.annotations.Nullable;
/** Each stage has all immutable facts and roads. The caller orchestrates routing. */
public final class BookingCalculation {
    public enum Stage { INSERTION, REFINEMENT }
    public record Input(BookingSnapshot snapshot, BoundedBookingSearch.Request request, Stage stage, String variant,
                        BoundedBookingSearch.@Nullable Result insertion, int refinementMillis) {
        public Input {
            Required.value(snapshot); Required.value(request); Required.value(stage);
            if (!Set.of("INSERTION","BOUNDED","EXPANDED","RUIN_RECREATE","SHARED").contains(variant)) throw new IllegalArgumentException("Unknown booking variant");
            if (refinementMillis < 0 || refinementMillis > 1000) throw new IllegalArgumentException("Invalid refinement cap");
            if (stage == Stage.REFINEMENT && insertion == null || stage != Stage.REFINEMENT && insertion != null)
                throw new IllegalArgumentException("Invalid insertion seed for booking stage");
        }
    }
    public record Output(BoundedBookingSearch.Result result, Map<LocalDate,Set<String>> neighborhoods,
                         long evaluatedRoutes, long reusedRoutes, long prunedArrangements,
                         long reconstructionAttempts, long reconstructionEvaluations) {
        public Output {
            Required.value(result); Map<LocalDate,Set<String>> copy=new TreeMap<>(); neighborhoods.forEach((date,ids) -> copy.put(Required.value(date),Required.value(Set.copyOf(ids))));
            neighborhoods=Required.value(Map.copyOf(copy));
            if (evaluatedRoutes < 0 || reusedRoutes < 0 || prunedArrangements < 0 || reconstructionAttempts < 0 || reconstructionEvaluations < 0) throw new IllegalArgumentException("Invalid booking diagnostics");
        }
    }
    private BookingCalculation() { }
    public static BoundedBookingSearch engine(Input input, Runnable checkpoint) {
        var limits = Set.of("EXPANDED","RUIN_RECREATE","SHARED").contains(input.variant()) ? BoundedBookingSearch.Limits.expanded() : BoundedBookingSearch.Limits.defaults();
        var engine = new BoundedBookingSearch(input.snapshot(), input.request(), limits, checkpoint);
        if (Set.of("RUIN_RECREATE","SHARED").contains(input.variant())) engine.withRuinRecreate();
        if (Set.of("EXPANDED","RUIN_RECREATE").contains(input.variant())) engine.withoutSharedWindowEvaluation();
        return engine;
    }
    public static Output run(Input input) {
        long started = System.nanoTime();
        Runnable checkpoint = () -> {
            SearchDeadline.checkpoint();
            if (input.stage() == Stage.REFINEMENT && input.refinementMillis() > 0
                    && System.nanoTime() - started >= input.refinementMillis() * 1_000_000L) throw new BoundedBookingSearch.RefinementLimit();
        };
        validateRoads(input);
        BoundedBookingSearch.Result insertion = input.insertion();
        if (insertion != null) validate(input.snapshot(), input.request(), insertion);
        var engine = engine(input, checkpoint);
        var result = switch(input.stage()) {
            case INSERTION -> engine.search(false);
            case REFINEMENT -> engine.refine(Required.value(insertion));
        };
        // Shortlist acquisition is orchestration data, never a routing callback from the engine.
        Map<LocalDate,Set<String>> neighborhoods = Required.value(Map.of());
        if (input.stage() == Stage.INSERTION && !input.variant().equals("INSERTION") && result.complete()) {
            try { neighborhoods = engine.neighborhoodRoutes(); }
            catch (SearchDeadline.Expired expired) {
                result = new BoundedBookingSearch.Result(result.candidates(),result.coverage(),false,result.distinctRegularWindows(),
                        result.confirmedRegularMinutes(),result.regularCapacityMinutes(),false,"DEADLINE");
            }
        }
        return new Output(result, neighborhoods, engine.evaluatedRoutes(), engine.reusedRoutes(), engine.prunedArrangements(),
                engine.reconstructionAttempts(), engine.reconstructionEvaluations());
    }
    public static void validateRoads(Input input) {
        var request = input.request();
        for (var entry : input.snapshot().days().entrySet()) {
            Day day = Required.value(entry.getValue());
            Set<String> points = new HashSet<>();
            for (Technician tech : day.technicians().values()) {
                PlanFacts.identity(tech.id(), "technician");
                if (!points.add(tech.id()) || !points.add(tech.id()+":return")) throw new IllegalArgumentException("Endpoint collision");
                day.roads().require(tech.id(),request.jobId()); day.roads().require(request.jobId(),tech.id()+":return");
            }
            for (Visit visit : day.visits().values()) {
                PlanFacts.identity(visit.id(),"visit");
                if (!points.add(visit.id())) throw new IllegalArgumentException("Visit endpoint collision");
                day.roads().require(visit.id(),request.jobId()); day.roads().require(request.jobId(),visit.id());
            }
            if (!points.add(request.jobId())) throw new IllegalArgumentException("Request identity collision");
            for (String pair : day.roads().legs().keySet()) PlanFacts.roadKey(Required.value(pair));
            for (String pair : day.roads().unreachable()) PlanFacts.roadKey(Required.value(pair));
            day.plan(day.baseline(),day.visits(),input.snapshot().rates(),false);
            day.plan(day.actualArrangement(),day.visits(),input.snapshot().rates(),true);
        }
        if (input.stage() == Stage.REFINEMENT) {
            var selected = engine(input, SearchDeadline::checkpoint).neighborhoodRoutes();
            for (var entry : selected.entrySet()) {
                Day day = Required.value(input.snapshot().days().get(entry.getKey())); Set<String> stops = new HashSet<>();
                for (String tech : entry.getValue()) stops.addAll(Required.value(day.baseline().routes().get(tech)));
                for (String tech : entry.getValue()) for (String visit : stops) {
                    day.roads().require(Required.value(tech),Required.value(visit)); day.roads().require(Required.value(visit),tech+":return");
                }
                for (String from : stops) for (String to : stops) if (!from.equals(to)) day.roads().require(Required.value(from),Required.value(to));
            }
        }
    }
    /** Recompute every returned offer from caller facts before it can become a reservation witness. */
    public static void validate(BookingSnapshot snapshot, BoundedBookingSearch.Request request, BoundedBookingSearch.Result result) {
        Set<BoundedBookingSearch.Window> windows = new HashSet<>(new BoundedBookingSearch(snapshot,request,BoundedBookingSearch.Limits.defaults(),SearchDeadline::checkpoint).windows());
        if (result.confirmedRegularMinutes() < 0 || result.regularCapacityMinutes() < result.confirmedRegularMinutes()
                || result.distinctRegularWindows() != result.candidates().stream().filter(candidate -> candidate.overtimeDelta() <= 0).map(candidate -> candidate.window()).distinct().count())
            throw new IllegalArgumentException("Invalid returned booking completeness metrics");
        Set<BoundedBookingSearch.Window> coverage = new HashSet<>();
        for (var item : result.coverage()) if (!windows.contains(item.window()) || !coverage.add(item.window())
                || item.routesExamined() < 0 || item.arrangementsExamined() < 0 || item.movesGenerated() < 0 || item.candidateEvaluations() < 0
                || result.complete() && !item.complete()) throw new IllegalArgumentException("Invalid returned booking coverage");
        for (var candidate : result.candidates()) {
            SearchDeadline.checkpoint();
            if (!windows.contains(candidate.window())) throw new IllegalArgumentException("Returned window not in snapshot");
            Day day = Required.value(snapshot.days().get(candidate.window().day()));
            Technician technician = Required.value(day.technicians().get(candidate.technicianId()));
            if (!technician.services().contains(request.serviceId())) throw new IllegalArgumentException("Returned technician unqualified");
            var visit = new Visit(request.jobId(),request.jobId(),request.serviceId(),candidate.window().start(),candidate.window().end(),
                    request.durationMinutes(),request.location(),candidate.technicianId(),candidate.window().start(),false);
            Map<String,Visit> facts = new HashMap<>(day.visits()); facts.put(visit.id(),visit);
            var validation = day.evaluate(candidate.arrangement(), facts,snapshot.rates());
            var baseline = day.evaluate(day.baseline(),day.visits(),snapshot.rates());
            if (!validation.feasible() || !validation.equals(candidate.validation())
                    || Math.subtractExact(validation.costCents(),baseline.costCents()) != candidate.costDeltaCents()
                    || Math.subtractExact(validation.overtimeMinutes(),baseline.overtimeMinutes()) != candidate.overtimeDelta())
                throw new IllegalArgumentException("Returned booking metrics do not match caller validation");
            List<String> order = Required.value(candidate.arrangement().routes().get(technician.id()));
            if (candidate.insertionPosition() < 0 || candidate.insertionPosition() >= order.size() || !order.get(candidate.insertionPosition()).equals(request.jobId()))
                throw new IllegalArgumentException("Returned insertion position mismatch");
            if (!day.evaluate(candidate.arrangement(),facts,snapshot.rates()).feasible()
                    || !RouteEvaluator.evaluate(day.plan(candidate.arrangement(),facts,snapshot.rates(),true)).feasible())
                throw new IllegalArgumentException("Returned confirmed workload infeasible");
            var evaluation = new BookingEvaluation(day,snapshot.rates(),request.serviceId(),SearchDeadline::checkpoint);
            var fairness = evaluation.fairness(candidate.arrangement(),facts).subtract(evaluation.fairness(day.baseline(),day.visits()));
            if (fairness.compareTo(candidate.fairnessDelta()) != 0) throw new IllegalArgumentException("Returned fairness mismatch");
            int changed = 0;
            for (var route : candidate.arrangement().routes().entrySet()) for (String id : route.getValue()) if (!id.equals(request.jobId())) {
                Visit original = Required.value(day.visits().get(id));
                if (!original.reservation() && !original.originalTechnicianId().equals(route.getKey())) changed++;
            }
            if (changed != candidate.changedAssignments()) throw new IllegalArgumentException("Returned changed assignment count mismatch");
        }
        if (result.overtimeAuthorized()) throw new IllegalArgumentException("Zero overtime policy prohibits authorization");
    }
}
