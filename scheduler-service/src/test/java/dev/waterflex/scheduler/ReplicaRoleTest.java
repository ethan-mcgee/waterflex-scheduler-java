package dev.waterflex.scheduler;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** The replica role is one of three names; anything else stops the replica at startup instead of guessing. */
class ReplicaRoleTest {
    @Test void readsEachRoleInAnyCase() {
        assertEquals(ReplicaRole.Role.BOOKING, new ReplicaRole("booking").role());
        assertEquals(ReplicaRole.Role.DAILY, new ReplicaRole(" DAILY ").role());
        assertEquals("all", new ReplicaRole("All").label());
    }

    @Test void onlyBookingReplicasLeaveTheDailyQueueAlone() {
        assertFalse(new ReplicaRole(ReplicaRole.Role.BOOKING).runsDailyQueue());
        assertTrue(new ReplicaRole(ReplicaRole.Role.DAILY).runsDailyQueue());
        assertTrue(new ReplicaRole(ReplicaRole.Role.ALL).runsDailyQueue());
    }

    @Test void anUnknownOrEmptyRoleFails() {
        assertThrows(IllegalArgumentException.class, () -> new ReplicaRole("bookings"));
        assertThrows(IllegalArgumentException.class, () -> new ReplicaRole(""));
    }
}
