package dev.waterflex.benchmark;

import dev.waterflex.scheduler.CalculationJson;
import dev.waterflex.scheduler.Required;
import dev.waterflex.scheduler.optimizer.*;

/** Setup evidence is outside measured solves and shared unchanged across Layer A treatments. */
public record FrozenTarget(int version, String datasetHash, String scoreVersion, String costVersion,
        String routingIdentity, SchedulingPolicy.Rules policy, SnapshotFileIO.Proposal reference,
        long costCeilingCents, String configurationHash, String configurationXml, long seed, long setupBudgetMs, long setupElapsedMs) {
    public FrozenTarget {
        if (version != 1 || seed < 0 || setupBudgetMs < 1 || setupElapsedMs < 0) throw new IllegalArgumentException("Invalid target setup");
        Required.value(datasetHash); Required.value(scoreVersion); Required.value(costVersion);
        Required.value(routingIdentity); Required.value(policy); Required.value(reference); Required.value(configurationHash);
        Required.value(configurationXml);
        if(!configurationHash.equals(CalculationJson.hash(configurationXml+"|score="+DailyDataset.SCORE_MODEL))) throw new IllegalArgumentException("Frozen setup configuration hash differs");
    }
    public DayPlan bind(DailyDataset dataset) {
        var node = CalculationJson.tree(dataset.json(DailyDataset.Encoding.SPARSE));
        if (!datasetHash.equals(dataset.contentHash()) || !scoreVersion.equals(DailyDataset.SCORE_MODEL)
                || !costVersion.equals(DailyDataset.COST_MODEL)
                || !routingIdentity.equals(Required.value(Required.value(node.get("routing")).get("identity")).textValue()))
            throw new IllegalArgumentException("Frozen target model/input mismatch");
        DayPlan input = dataset.toDayPlan();
        DayPlan restored = SnapshotFileIO.importProposal(input,reference);
        if (!DailyOutcome.assess(restored).policyEligible()) throw new IllegalArgumentException("Frozen reference must be complete with zero overtime");
        long cost = RouteEvaluator.evaluate(restored).costCents();
        if (costCeilingCents != policy.costCeiling(cost)) throw new IllegalArgumentException("Frozen ceiling differs from declared policy");
        if (restored.getMode() == DayPlan.Mode.COLD) restored.setMode(DayPlan.Mode.PARTIAL);
        restored.setScoringFacts(restored.getScoringFacts().withTarget(restored,costCeilingCents));
        return restored;
    }
}
