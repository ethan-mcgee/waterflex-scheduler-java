package dev.waterflex.scheduler;

import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** The retention period is the operator's choice: never assumed, and refused when it is not a sensible number of days. */
class RetentionTest {
    @Test void anUnsetPeriodStopsStartupNamingTheVariable() {
        var unset = assertThrows(IllegalStateException.class, () -> Retention.days(""));
        assertTrue(String.valueOf(unset.getMessage()).contains("SCHEDULER_RETENTION_DAYS"));
        assertThrows(IllegalStateException.class, () -> Retention.days("   "));
        assertThrows(IllegalStateException.class, () -> Retention.days("90d"));
        assertEquals(90, Retention.days(" 90 "));
    }

    @Test void thePeriodIsOneToTenYears() {
        Clock clock = Required.value(Clock.systemUTC());
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        assertThrows(IllegalArgumentException.class, () -> new Retention(jdbc, 0, clock));
        assertThrows(IllegalArgumentException.class, () -> new Retention(jdbc, Retention.MAX_DAYS + 1, clock));
        assertEquals(1, new Retention(jdbc, 1, clock).days());
    }
}
