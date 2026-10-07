package dev.waterflex.scheduler.optimizer;
import dev.waterflex.scheduler.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class DailyProposalBoundaryTest {
    @Test void callerRestorationRejectsDuplicateMissingForeignAndMovedPinnedDemand() {
        DayPlan plan=DailyConstructionTest.fixture(3,6,2,1,DayPlan.Mode.PARTIAL);
        var input=CalculationProtocol.Proposal.of(plan);
        assertEquals(DailyOutcome.assess(plan),DailyOutcome.assess(input.restore(plan)));
        List<String> unresolved=new ArrayList<>(input.unassigned()); unresolved.add(Required.value(unresolved.getFirst()));
        assertThrows(RuntimeException.class,() -> new CalculationProtocol.Proposal(input.routes(),unresolved).restore(plan));
        var missing=new ArrayList<>(input.unassigned()); missing.removeFirst();
        assertThrows(IllegalArgumentException.class,() -> new CalculationProtocol.Proposal(input.routes(),missing).restore(plan));
        var foreign=new ArrayList<>(input.unassigned()); foreign.set(0,"foreign-demand");
        assertThrows(RuntimeException.class,() -> new CalculationProtocol.Proposal(input.routes(),foreign).restore(plan));
        Map<String,List<String>> changed=new TreeMap<>(input.routes());
        var route=plan.getRoutes().getFirst(); var reversed=new ArrayList<>(Required.value(changed.get(route.getId()))); Collections.reverse(reversed); changed.put(route.getId(),reversed);
        assertThrows(IllegalArgumentException.class,() -> new CalculationProtocol.Proposal(changed,input.unassigned()).restore(plan));
        assertEquals(input,CalculationProtocol.Proposal.of(plan));
    }
}
