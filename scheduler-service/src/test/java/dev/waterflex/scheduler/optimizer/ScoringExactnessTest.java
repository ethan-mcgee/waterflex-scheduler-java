package dev.waterflex.scheduler.optimizer;

import ai.timefold.solver.core.api.solver.SolverFactory;
import ai.timefold.solver.core.config.solver.SolverConfig;
import dev.waterflex.scheduler.Required;
import java.io.StringReader;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The scoring path must evaluate exactly what it evaluated before the CR07 treatment (indexed roads, precomputed
 * buffered minutes, score-only timing without arrival maps). A step-limited solve with a fixed seed is deterministic
 * in its move sequence, so the final score, the number of moves evaluated, the arrangement and the independent
 * evaluation of the result are the same for any exact scoring implementation and differ for any inexact one. The
 * expected values were captured on the code before the treatment (commit 7ca5c72); the two fairness-phase values were
 * captured on main at dcbdca6, before representation study 2.
 */
class ScoringExactnessTest {
    private static SolverEngine.Definition stepped(int steps) {
        var capped = SolverEngine.configuration(SolverEngine.Variant.CURRENT_CAPPED, 17);
        String xml = capped.configurationXml().replace("<stepCountLimit>1000</stepCountLimit>", "<stepCountLimit>" + steps + "</stepCountLimit>")
                .replace("<spentLimit>15s</spentLimit>", "<spentLimit>10m</spentLimit>");
        if (xml.equals(capped.configurationXml())) throw new IllegalStateException("The capped configuration changed shape");
        SolverConfig config = Required.value(SolverConfig.createFromXmlReader(new StringReader(xml)).withRandomSeed(17L));
        var definition = new SolverEngine.Definition(capped.variant(), 17, capped.environmentMode(), capped.construction(), xml,
                capped.fingerprint() + "|exactness", steps, Required.value(SolverFactory.create(config)));
        return definition;
    }
    private static String solve(int technicians, SolverBenchmarkData.Workload workload, long fixtureSeed, int steps) {
        DayPlan fixture = SolverBenchmarkData.create(technicians, workload, fixtureSeed);
        return describe(SolverExperiment.solve(stepped(steps), PlanCopies.copy(fixture), Required.value(Duration.ofMinutes(10))));
    }
    /**
     * The fairness phase as the daily calculation runs it: the cost phase's plan becomes the reference, its overtime the
     * target and its cost plus two percent the ceiling, so every level of the score is live (the overtime target and
     * cost ceiling on hard, the variance objective on soft) and the fleet streams that read the target are scored.
     */
    private static String fairness(int technicians, SolverBenchmarkData.Workload workload, long fixtureSeed, int steps) {
        DayPlan fixture = SolverBenchmarkData.create(technicians, workload, fixtureSeed);
        DayPlan reference = SolverExperiment.solve(stepped(steps), PlanCopies.copy(fixture), Required.value(Duration.ofMinutes(10))).plan();
        long cost = SchedulingPolicy.measure(reference).costCents();
        DayPlan seed = PlanCopies.copy(reference);
        seed.setScoringFacts(seed.getScoringFacts().withTarget(reference, Math.addExact(cost, cost / 50)));
        return describe(SolverExperiment.solve(stepped(steps), seed, Required.value(Duration.ofMinutes(10))));
    }
    private static String describe(SolverEngine.Result result) {
        var independent = RouteEvaluator.evaluate(result.plan());
        List<String> arrangement = new ArrayList<>();
        for (TechRoute route : result.plan().getRoutes()) {
            List<String> visits = new ArrayList<>();
            for (PlanVisit visit : route.getVisits()) visits.add(visit.getId());
            arrangement.add(route.getId() + "=" + String.join(",", visits));
        }
        return "score=" + DecimalScores.of(result.plan(), DecimalScores.VARIANCE_SCALE) + " moves=" + result.statistics().moveEvaluations() + " steps=" + result.statistics().steps()
                + " feasible=" + independent.feasible() + " cents=" + independent.costCents() + " arrivals=" + independent.arrivals().hashCode()
                + " arrangement=" + arrangement.hashCode();
    }

    @Test void aSingleBlockDayScoresExactlyAsBefore() {
        assertEquals("score=[0/0/0/0/0]hard/[0/-168659]soft moves=3000 steps=30 feasible=true cents=168659 arrivals=997203392 arrangement=665513981", solve(20, SolverBenchmarkData.Workload.CLUSTERED, 7001, 30));
    }

    @Test void aDayWithAbsencesScoresExactlyAsBefore() {
        assertEquals("score=[0/0/0/0/0]hard/[0/-170875]soft moves=3000 steps=30 feasible=true cents=170875 arrivals=997337048 arrangement=-307532887", solve(20, SolverBenchmarkData.Workload.ABSENCE, 7002, 30));
    }

    @Test void aLargeDispersedDayScoresExactlyAsBefore() {
        assertEquals("score=[0/0/0/0/0]hard/[0/-497848]soft moves=2000 steps=20 feasible=true cents=497848 arrivals=2094978950 arrangement=1569652034", solve(50, SolverBenchmarkData.Workload.DISPERSED, 7003, 20));
    }

    @Test void aFairnessPhaseOverClusteredRoutesScoresExactlyAsBefore() {
        assertEquals("score=[0/0/0/0/0]hard/[-0.010622599/-173608]soft moves=2000 steps=20 feasible=true cents=173608 arrivals=-1196828960 arrangement=1008159967", fairness(20, SolverBenchmarkData.Workload.CLUSTERED, 7004, 20));
    }

    @Test void aFairnessPhaseWithAbsencesScoresExactlyAsBefore() {
        assertEquals("score=[0/0/0/0/0]hard/[-0.013611233/-266719]soft moves=2000 steps=20 feasible=true cents=266719 arrivals=-1561050366 arrangement=-564114250", fairness(30, SolverBenchmarkData.Workload.ABSENCE, 7005, 20));
    }
}
