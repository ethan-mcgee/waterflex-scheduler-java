package dev.waterflex.scheduler;

import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import static org.junit.jupiter.api.Assertions.*;

class TimeOffEligibilityTest {
    @Test
    void thirteenDaysNeedsReviewAndFourteenDaysAppliesAutomatically() {
        LocalDate today = LocalDate.parse("2026-09-17");
        assertFalse(TimeOffService.automaticEligible(today.plusDays(13), today));
        assertTrue(TimeOffService.automaticEligible(today.plusDays(14), today));
    }
}
