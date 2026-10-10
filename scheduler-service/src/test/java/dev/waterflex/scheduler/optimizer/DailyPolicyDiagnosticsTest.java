package dev.waterflex.scheduler.optimizer;
import dev.waterflex.scheduler.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class DailyPolicyDiagnosticsTest {
    private static DayPlan plan(int capacity,boolean reverse) {
        Instant start=Required.value(Instant.parse("2030-01-01T14:00:00Z"));
        var route=new TechRoute("t",start,Required.value(start.plusSeconds(capacity*60L)),480,120,Required.value(Set.of("s")));
        var a=new PlanVisit("a","s",start,Required.value(start.plusSeconds(28800)),40,"t",start);
        var b=new PlanVisit("b","s",start,Required.value(start.plusSeconds(28800)),40,"t",Required.value(start.plusSeconds(6600)));
        route.getVisits().addAll(reverse ? List.of(b,a) : List.of(a,b));
        Map<String,DayPlan.RoadLeg> roads=new HashMap<>();
        for (String id : List.of("a","b")) { roads.put("t>"+id,new DayPlan.RoadLeg(0,0)); roads.put(id+">t:return",new DayPlan.RoadLeg(0,0)); }
        roads.put("a>b",new DayPlan.RoadLeg(4200,0)); roads.put("b>a",new DayPlan.RoadLeg(0,0));
        return new DayPlan(Required.value(List.of(route)),Required.value(List.of(a,b)),roads,30,45,0,0,0);
    }
    @Test void ninetyToTwentyOvertimeIsDiagnosticImprovementAndNeverStartsFairness() {
        verifyPhases(60,1,DailyPolicyDiagnostics.Fairness.SKIPPED_REFERENCE_OVERTIME);
    }
    @Test void eligibleZeroOvertimeReferenceStillStartsFairness() {
        verifyPhases(480,2,DailyPolicyDiagnostics.Fairness.SEARCHED);
    }
    private static void verifyPhases(int capacity,int expected,DailyPolicyDiagnostics.Fairness fairness) {
        DayPlan baseline=plan(capacity,false), candidate=PlanCopies.copy(baseline);
        Collections.reverse(candidate.getRoutes().getFirst().getVisits());
        var real=new DailySolver("CURRENT_CAPPED",17);
        var evidence=real.solve(candidate,Required.value(Duration.ofMillis(100)));
        AtomicInteger calls=new AtomicInteger();
        var solver=mock(DailySolver.class,invocation -> {
            if (invocation.getMethod().getName().equals("solve")) {
                calls.incrementAndGet();
                return new SolverEngine.Result(PlanCopies.copy(candidate),evidence.statistics(),DailyOutcome.assess(candidate));
            }
            if (invocation.getMethod().getName().equals("diagnostics") && invocation.getArguments()[0] instanceof List<?> values) {
                List<DailySolver.Phase> phases=new ArrayList<>();
                for (Object value : values) { if (!(value instanceof DailySolver.Phase phase)) throw new IllegalArgumentException("Invalid test phase"); phases.add(phase); }
                return real.diagnostics(phases);
            }
            throw new IllegalArgumentException("Unexpected solver call");
        });
        var result=DailyOperation.execute(new SearchAdmission(2,16),Required.value(Duration.ofSeconds(20)),
                () -> DailyCalculation.run(baseline,SchedulingPolicy.Rules.defaults(),solver),_ -> { });
        assertEquals(expected,calls.get());
        var diagnostic=Required.value(result.diagnostics().policy()); assertEquals(fairness,diagnostic.fairness());
        if (capacity==60) {
            assertEquals(90,RouteEvaluator.evaluate(baseline).overtimeMinutes());
            assertEquals(20,diagnostic.candidateOvertimeMinutes()); assertEquals(20,diagnostic.referenceOvertimeMinutes());
            assertFalse(result.accepted()); assertEquals(DailyPolicyDiagnostics.Decision.REJECTED_OVERTIME,diagnostic.decision());
        }
        assertEquals(150,RouteEvaluator.evaluate(baseline).paidMinutes());
    }
}
