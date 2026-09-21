package dev.waterflex.scheduler;

import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import static org.junit.jupiter.api.Assertions.*;

class TimeOffEligibilityTest {
    @Test
    void thirteenDaysNeedsReviewAndFourteenDaysAppliesAutomatically() {
        LocalDate today = Required.value(LocalDate.parse("2026-09-17"));
        assertFalse(TimeOffService.automaticEligible(Required.value(today.plusDays(13)), today));
        assertTrue(TimeOffService.automaticEligible(Required.value(today.plusDays(14)), today));
    }
}
