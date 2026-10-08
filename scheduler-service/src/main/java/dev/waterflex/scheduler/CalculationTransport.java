package dev.waterflex.scheduler;
import dev.waterflex.scheduler.optimizer.*;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
/** Rollout stays embedded by default. Every remote result is checked against caller-owned facts. */
@Component
public final class CalculationTransport {
    private final @Nullable HttpCalculation remote;
    public CalculationTransport(@Value("${scheduler.calculation.mode:EMBEDDED}") String mode,
            @Value("${scheduler.calculation.url:http://127.0.0.1:8002}") String url,
            @Value("${scheduler.calculation.auth-token:}") String token) {
        if (!mode.equals("EMBEDDED") && !mode.equals("REMOTE")) throw new IllegalArgumentException("Unknown calculation mode");
        remote = mode.equals("REMOTE") ? new HttpCalculation(url,token) : null;
    }
    public BookingCalculation.Output booking(BookingCalculation.Input input) {
        if (remote == null) {
            return BookingCalculation.run(input);
        }
        long remaining = allowance(120000);
        var request = CalculationProtocol.Request.of("BOOKING",remaining,input);
        var response = Required.value(remote).calculate(request);
        var output = CalculationJson.read(response.payload(),BookingCalculation.Output.class);
        BookingCalculation.validate(input.snapshot(),input.request(),output.result());
        return output;
    }
    public DailyCalculation.Result daily(DayPlan plan,SchedulingPolicy.Rules policy,DailySolver solver,
            java.util.Map<String,RoadPoint> points,String snapshotId,String revision,String routing,String configuration,java.util.Map<String,Integer> schedule) {
        java.util.Map<String,String> tokens = new java.util.LinkedHashMap<>();
        schedule.forEach((technician,version) -> tokens.put(Required.value(technician),Integer.toString(Required.value(version))));
        return dailyTokens(plan,policy,solver,points,snapshotId,revision,routing,configuration,tokens);
    }
    /** As {@link #daily}, with opaque per-technician revision tokens such as host last-modified timestamps. */
    public DailyCalculation.Result dailyTokens(DayPlan plan,SchedulingPolicy.Rules policy,DailySolver solver,
            java.util.Map<String,RoadPoint> points,String snapshotId,String revision,String routing,String configuration,java.util.Map<String,String> schedule) {
        if (remote == null) return DailyCalculation.run(plan,policy,solver);
        long remaining = allowance(20000);
        var dataset = DailyDataset.captureTokens(plan,points,snapshotId,revision,routing,configuration,schedule,solver.variant(),solver.seed(),remaining);
        var request = CalculationProtocol.Request.of("DAILY",remaining,new CalculationProtocol.DailyInput(dataset.json(DailyDataset.Encoding.SPARSE),policy));
        var response = Required.value(remote).calculate(request); var output = CalculationJson.read(response.payload(),CalculationProtocol.DailyOutput.class);
        DayPlan proposed = output.proposal().restore(plan), reference = output.reference().restore(plan);
        if (!DailyOutcome.assess(proposed).equals(output.outcome())) throw new IllegalArgumentException("Remote daily outcome differs from caller validation");
        boolean accepted;
        if (plan.getMode() == DayPlan.Mode.REPAIR) accepted = DailyCalculation.valid(proposed) && RouteEvaluator.evaluate(proposed).overtimeMinutes() == 0;
        else {
            boolean proposedValid = DailyCalculation.valid(proposed), referenceValid = DailyCalculation.valid(reference);
            if (proposedValid && !referenceValid) throw new IllegalArgumentException("Remote reference is not independently valid");
            accepted = proposedValid && (DailyCalculation.valid(plan)
                    ? SchedulingPolicy.compare(SchedulingPolicy.measure(plan),SchedulingPolicy.measure(proposed),SchedulingPolicy.measure(reference),policy).accepted()
                    : DailyOutcome.assess(proposed).policyEligible());
        }
        if (output.accepted() != accepted) throw new IllegalArgumentException("Remote daily policy eligibility mismatch");
        return new DailyCalculation.Result(proposed,reference,accepted,output.reason(),output.solveMs(),output.diagnostics());
    }
    private static long allowance(long maximum) {
        SearchDeadline clock = Required.value(SearchDeadline.current(),"caller calculation clock"); clock.requireTime();
        long remaining = Math.min(maximum,clock.explorationNanos()/1_000_000);
        if (remaining < 1) throw new SearchDeadline.Expired(); return remaining;
    }
}
