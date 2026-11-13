package dev.waterflex.scheduler;
import dev.waterflex.scheduler.optimizer.*;
import org.springframework.stereotype.Component;
/** Runs booking and daily calculations in this replica's JVM; tests replace it to inject a calculation's failures. */
@Component
public class CalculationTransport {
    public BookingCalculation.Output booking(BookingCalculation.Input input) { return BookingCalculation.run(input); }
    public DailyCalculation.Result daily(DayPlan plan,SchedulingPolicy.Rules policy,DailySolver solver,DailyCalculation.Allocation allocation) {
        return DailyCalculation.run(plan,policy,solver,allocation);
    }
}
