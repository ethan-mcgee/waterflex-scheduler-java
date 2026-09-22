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

    @Test
    void categoryContractMatchesPersistedOptions() {
        assertTrue(TimeOffService.allowedCategory("Vacation / personal travel"));
        assertTrue(TimeOffService.allowedCategory("Medical appointment"));
        assertTrue(TimeOffService.allowedCategory("Illness"));
        assertTrue(TimeOffService.allowedCategory("Family emergency"));
        assertTrue(TimeOffService.allowedCategory("Bereavement"));
        assertTrue(TimeOffService.allowedCategory("Jury duty / civic obligation"));
        assertTrue(TimeOffService.allowedCategory("Other"));
        assertFalse(TimeOffService.allowedCategory("Unvalidated category"));
        assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> new TimeOffService.Request("tech", "2026-10-01", "2026-10-01", 480, 1020, "Unvalidated category", "Reason"));
    }
}
