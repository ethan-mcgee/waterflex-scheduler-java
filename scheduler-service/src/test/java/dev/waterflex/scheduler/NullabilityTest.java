package dev.waterflex.scheduler;

import dev.waterflex.scheduler.optimizer.DayPlan;
import dev.waterflex.scheduler.optimizer.PlanVisit;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.sql.ResultSet;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NullabilityTest {
    @Test void missingRequiredQueryRowsReturnConflict() {
        var jdbc = mock(org.springframework.jdbc.core.JdbcTemplate.class);
        when(jdbc.queryForObject("required", Integer.class)).thenThrow(new org.springframework.dao.EmptyResultDataAccessException(1));
        assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class,
                () -> dev.waterflex.scheduler.DatabaseFacts.query(jdbc, "required", Integer.class)).getStatusCode());
        assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class,
                () -> dev.waterflex.scheduler.DatabaseFacts.query(jdbc, "null-result", Integer.class)).getStatusCode());
    }
    @Test void sqlNullIsDifferentFromLegitimateZeroAndFalse() throws Exception {
        ResultSet row = mock(ResultSet.class);
        when(row.wasNull()).thenReturn(false);
        assertEquals(0, dev.waterflex.scheduler.DatabaseFacts.integer(row, 1));
        assertFalse(dev.waterflex.scheduler.DatabaseFacts.bool(row, 1));
        when(row.wasNull()).thenReturn(true);
        assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class, () -> dev.waterflex.scheduler.DatabaseFacts.integer(row, 1)).getStatusCode());
        assertThrows(ResponseStatusException.class, () -> dev.waterflex.scheduler.DatabaseFacts.timestamp(row, 1));
    }

    @Test void solverLifecycleRequiresInitializedFacts() {
        DayPlan plan = new DayPlan();
        assertNull(plan.getScore());
        assertThrows(ResponseStatusException.class, plan::getRoutes);
        PlanVisit visit = new PlanVisit();
        assertNull(visit.getTechnician());
        assertThrows(ResponseStatusException.class, visit::getWindowStart);
    }
}
