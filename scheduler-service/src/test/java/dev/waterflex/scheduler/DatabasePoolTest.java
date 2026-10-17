package dev.waterflex.scheduler;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** The database pool must leave room beyond the search slots, or refinements lose their connection under load. */
class DatabasePoolTest {
    @Test void thePoolExceedsTheSlotsOrTheReplicaRefusesToStart() {
        assertEquals(10, new DatabasePool(10, 2, 1).poolSize(), "the default pool for the default capacity");
        assertEquals(10, new DatabasePool(10, new SearchAdmission(2, 16)).poolSize(), "read from the admission's own capacity and confirm slots");
        assertEquals(9, new DatabasePool(9, 3, 1).poolSize(), "a study's capacity plus six");
        assertThrows(IllegalArgumentException.class, () -> new DatabasePool(1, 1, 0), "below the minimum of two");
        assertThrows(IllegalArgumentException.class, () -> new DatabasePool(10, 10, 0), "equal to the slots: a search's commit has no connection");
        assertThrows(IllegalArgumentException.class, () -> new DatabasePool(10, 9, 1), "slots and confirm slots fill it");
        assertEquals(12, new DatabasePool(12, 9, 1).poolSize(), "one beyond them is allowed, with the headroom the operator chose");
    }
}
