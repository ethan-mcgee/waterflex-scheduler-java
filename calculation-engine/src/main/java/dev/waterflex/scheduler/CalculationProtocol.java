package dev.waterflex.scheduler;
import dev.waterflex.scheduler.optimizer.*;
import java.util.*;
/** Versioned calculation envelopes. A proposal never conveys persistence or apply authority. */
public final class CalculationProtocol {
    public static final int VERSION = 1;
    private CalculationProtocol() { }
    public record Request(int schemaVersion, String requestId, String operation, long remainingMillis, String contentHash, String payload) {
        public Request {
            if (schemaVersion != VERSION || !Set.of("DAILY","BOOKING").contains(operation) || remainingMillis < 1 || remainingMillis > (operation.equals("DAILY") ? 20000 : 120000))
                throw new IllegalArgumentException("Invalid calculation envelope");
            UUID.fromString(requestId); CalculationJson.text(contentHash); CalculationJson.text(payload);
            if (!CalculationJson.hash(payload).equals(contentHash)) throw new IllegalArgumentException("Calculation content hash mismatch");
        }
        public static Request of(String operation, long remainingMillis, Object input) {
            String payload = input instanceof BookingCalculation.Input booking ? BookingDataset.encode(booking,BookingDataset.Encoding.SPARSE) : CalculationJson.write(input);
            return new Request(VERSION,Required.value(UUID.randomUUID().toString()),operation,remainingMillis,CalculationJson.hash(payload),payload);
        }
    }
    public record Response(int schemaVersion, String requestId, String contentHash, String policyVersion,
                           String costVersion, String scoreVersion, String engine, EngineProvenance provenance, long elapsedMillis, String payload) {
        public Response {
            if (schemaVersion != VERSION || elapsedMillis < 0 || !SchedulingPolicy.VERSION.equals(policyVersion)
                    || !Monetary.COST_MODEL.equals(costVersion) || !DailyDataset.SCORE_MODEL.equals(scoreVersion)) throw new IllegalArgumentException("Incompatible calculation response");
            UUID.fromString(requestId); CalculationJson.text(contentHash); CalculationJson.text(engine); Required.value(provenance); CalculationJson.text(payload);
        }
        public static Response of(Request request, long elapsed, Object output) {
            return new Response(VERSION,request.requestId(),request.contentHash(),SchedulingPolicy.VERSION,Monetary.COST_MODEL,DailyDataset.SCORE_MODEL,
                    EngineProvenance.loaded().label(),EngineProvenance.loaded(),elapsed,CalculationJson.write(output));
        }
        public void match(Request request) {
            if (!requestId.equals(request.requestId()) || !contentHash.equals(request.contentHash())) throw new IllegalArgumentException("Calculation response provenance mismatch");
            var expected=EngineProvenance.loaded();
            String version=provenance.version(), hash=provenance.sha256();
            if (!provenance.artifact().equals(expected.artifact()) || version == null || hash == null || !engine.equals(provenance.label())
                    || !version.equals(expected.version()) || !hash.equals(expected.sha256())) throw new IllegalArgumentException("Remote engine artifact mismatch or unavailable identity");
        }
    }
    public record DailyInput(String dataset, SchedulingPolicy.Rules policy) {
        public DailyInput { CalculationJson.text(dataset); Required.value(policy); }
    }
    public record Proposal(Map<String,List<String>> routes, List<String> unassigned) {
        public Proposal {
            Map<String,List<String>> copy = new TreeMap<>(); routes.forEach((key,value) -> copy.put(Required.value(key),Required.value(List.copyOf(value))));
            routes = Required.value(Map.copyOf(copy)); unassigned = Required.value(List.copyOf(unassigned));
        }
        public static Proposal of(DayPlan plan) {
            Map<String,List<String>> routes = new TreeMap<>(); plan.getRoutes().forEach(route -> routes.put(route.getId(),Required.value(route.getVisits().stream().<String>map(visit -> Required.value(visit).getId()).toList())));
            return new Proposal(routes,plan.getUnassignedVisitIds());
        }
        public DayPlan restore(DayPlan input) { return DailyCalculation.restore(input,routes,unassigned); }
    }
    public record DailyOutput(Proposal proposal, Proposal reference, boolean accepted, String reason, int solveMs,
                              DailyOutcome outcome, DailySolver.Diagnostics diagnostics) {
        public DailyOutput { Required.value(proposal); Required.value(reference); CalculationJson.text(reason); Required.value(outcome); Required.value(diagnostics); if (solveMs < 0) throw new IllegalArgumentException("Negative solve time"); }
        public static DailyOutput of(DailyCalculation.Result result) {
            return new DailyOutput(Proposal.of(result.plan()),Proposal.of(result.reference()),result.accepted(),result.reason(),result.solveMs(),DailyOutcome.assess(result.plan()),result.diagnostics());
        }
    }
}
