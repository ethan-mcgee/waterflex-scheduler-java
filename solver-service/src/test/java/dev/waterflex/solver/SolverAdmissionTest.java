package dev.waterflex.solver;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** A pool's daily lane count follows its setting, and by default every slot is open to daily work. */
class SolverAdmissionTest {
    @Test void everySlotTakesDailyWorkUnlessTheBackgroundLimitIsSet() {
        var application = new SolverApplication();
        assertEquals(4, application.admission(4, 16, 0).backgroundLimit(), "0 means the capacity: a pool protects nothing else");
        assertEquals(2, application.admission(4, 16, 2).backgroundLimit());
        assertThrows(IllegalArgumentException.class, () -> application.admission(4, 16, -1));
        assertThrows(IllegalArgumentException.class, () -> application.admission(2, 16, 3), "at most the capacity, as in the scheduler");
    }
}
