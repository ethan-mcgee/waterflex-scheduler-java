package dev.waterflex.scheduler;
import dev.waterflex.scheduler.optimizer.*;
/** The identical protocol can be replayed locally with no database or routing connection. */
public final class EmbeddedCalculation implements CalculationAdapter {
    private final DailySolver solver;
    public EmbeddedCalculation(DailySolver solver) { this.solver = solver; }
    @Override public CalculationProtocol.Response calculate(CalculationProtocol.Request request) {
        long started = System.nanoTime(); SearchDeadline.checkpoint();
        Object output;
        if (request.operation().equals("DAILY")) {
            var input = CalculationJson.read(request.payload(),CalculationProtocol.DailyInput.class);
            DailyDataset dataset = DailyDataset.parse(input.dataset());
            var search = CalculationJson.tree(dataset.json(DailyDataset.Encoding.SPARSE)).path("search");
            if (!solver.matches(search.path("variant").textValue(),search.path("seed").longValue()) || request.remainingMillis() > search.path("remainingMillis").longValue())
                throw new IllegalArgumentException("Daily search configuration or allowance mismatch");
            output = CalculationProtocol.DailyOutput.of(DailyCalculation.run(dataset.toDayPlan(),input.policy(),solver));
        } else {
            var input = BookingDataset.parse(request.payload());
            output = BookingCalculation.run(input);
        }
        return CalculationProtocol.Response.of(request,(System.nanoTime()-started)/1_000_000,output);
    }
}
