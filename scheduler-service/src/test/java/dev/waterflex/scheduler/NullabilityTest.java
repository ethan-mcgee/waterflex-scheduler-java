package dev.waterflex.scheduler;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.waterflex.scheduler.optimizer.DayPlan;
import dev.waterflex.scheduler.optimizer.PlanVisit;
import dev.waterflex.scheduler.optimizer.OptimizationController;
import dev.waterflex.scheduler.optimizer.OptimizationService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;
import java.sql.ResultSet;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class NullabilityTest {
    @Test void missingRequiredQueryRowsReturnConflict() {
        var jdbc = mock(org.springframework.jdbc.core.JdbcTemplate.class);
        when(jdbc.queryForObject("required", Integer.class)).thenThrow(new org.springframework.dao.EmptyResultDataAccessException(1));
        assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class,
                () -> dev.waterflex.scheduler.DatabaseFacts.query(jdbc, "required", Integer.class)).getStatusCode());
        assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class,
                () -> dev.waterflex.scheduler.DatabaseFacts.query(jdbc, "null-result", Integer.class)).getStatusCode());
    }
    @Test void malformedDispatchAndAbsenceRequestsNeverReachServices() throws Exception {
        OptimizationService optimization = mock(OptimizationService.class);
        TimeOffService timeOff = mock(TimeOffService.class);
        ScheduleGuardService guard = mock(ScheduleGuardService.class);
        var http = MockMvcBuilders.standaloneSetup(new OptimizationController(optimization), new TimeOffController(timeOff), new ScheduleGuardController(guard))
                .setMessageConverters(new JsonConfiguration().strictJsonConverter()).build();
        for (String path : List.of("/v1/optimize/day/preview", "/v1/optimize/runs/x/apply", "/v1/time-off/request", "/v1/time-off/x/approve", "/v1/time-off/x/retry", "/v1/time-off/x/deny", "/v1/dispatch/availability", "/v1/dispatch/qualification"))
            for (String body : List.of("null", "[]", "{", "false", "\"text\""))
                http.perform(Required.value(post(Required.value(path)).contentType("application/json").content(Required.value(body)))).andExpect(status().isBadRequest());
        verifyNoInteractions(optimization, timeOff, guard);
    }
    @Test void approvalCannotRequestOvertime() throws Exception {
        // Overtime is never assigned, so time-off approval accepts only an empty body.
        TimeOffService timeOff = mock(TimeOffService.class);
        var http = MockMvcBuilders.standaloneSetup(new TimeOffController(timeOff))
                .setMessageConverters(new JsonConfiguration().strictJsonConverter()).build();
        for (String body : List.of("{\"allowAdditionalOvertime\":true}",
                "{\"allowAdditionalOvertime\":false}",
                "{\"allowAdditionalOvertime\":true,\"approvedRepairIds\":[\"repair\"]}",
                "{\"approvedRepairIds\":[]}", "null"))
            http.perform(Required.value(post("/v1/time-off/request/approve").contentType("application/json").content(Required.value(body))))
                    .andExpect(status().isBadRequest());
        verifyNoInteractions(timeOff);
        http.perform(Required.value(post("/v1/time-off/request/approve").contentType("application/json").content("{}"))).andExpect(status().isOk());
        verify(timeOff).approve("request");
    }

    @Test void sqlNullIsDifferentFromLegitimateZeroAndFalse() throws Exception {
        ResultSet row = mock(ResultSet.class);
        when(row.wasNull()).thenReturn(false);
        assertEquals(0, dev.waterflex.scheduler.DatabaseFacts.integer(row, 1));
        assertEquals(0, dev.waterflex.scheduler.DatabaseFacts.number(row, 1));
        assertFalse(dev.waterflex.scheduler.DatabaseFacts.bool(row, 1));
        assertEquals(new RoadPoint(0, 0), dev.waterflex.scheduler.DatabaseFacts.location(row, 1, 2, HttpStatus.CONFLICT));
        when(row.wasNull()).thenReturn(true);
        assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class, () -> dev.waterflex.scheduler.DatabaseFacts.integer(row, 1)).getStatusCode());
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, assertThrows(ResponseStatusException.class,
                () -> dev.waterflex.scheduler.DatabaseFacts.location(row, 1, 2, HttpStatus.UNPROCESSABLE_ENTITY)).getStatusCode());
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

    @Test void malformedSavedAssignmentsAndVersionsFailClosed() throws Exception {
        ObjectMapper json = new ObjectMapper();
        for (String body : List.of("null", "{}", "[]", "{\"version\":\"overtime-fairness-v1\",\"costChangeCents\":null}",
                "{\"version\":\"overtime-fairness-v1\",\"costChangeCents\":0,\"rules\":null}"))
            assertThrows(ResponseStatusException.class, () -> SavedJson.policyAnalysis(Required.value(json.readTree(body))));
        for (String body : List.of("null", "{}", "[null]", "[{\"appointmentId\":\"a\"}]"))
            assertThrows(ResponseStatusException.class, () -> SavedJson.assignments(Required.value(json.readTree(body))));
        for (String body : List.of("null", "[]", "{\"t\":null}", "{\"t\":\"0\"}", "{\"t\":-1}"))
            assertThrows(ResponseStatusException.class, () -> SavedJson.versions(Required.value(json.readTree(body))));
        assertTrue(SavedJson.versions(Required.value(json.readTree("{\"t\":0}"))).isObject());
        assertThrows(ResponseStatusException.class, () -> SavedJson.summary(Required.value(json.readTree("[{}]"))));
        for (String body : List.of("null", "{}", "{\"technician_id\":\"t\",\"days\":[]}", "{\"technician_id\":\"t\",\"days\":[null]}"))
            assertThrows(ResponseStatusException.class, () -> SavedJson.readyReport(Required.value(json.readTree(body))));
        String offDay = "{\"technician_id\":\"t\",\"reassigned_jobs\":0,\"total_before\":null,\"total_after\":null,\"days\":[{\"service_date\":\"2026-10-03\",\"start_min\":480,\"end_min\":1020,\"status\":\"NO_SHIFT\"}]}";
        assertTrue(SavedJson.readyReport(Required.value(json.readTree(offDay))).isObject());
        assertThrows(ResponseStatusException.class, () -> SavedJson.readyReport(Required.value(json.readTree(offDay.replace("\"status\":\"NO_SHIFT\"", "\"status\":\"NO_SHIFT\",\"run_id\":\"fake\"")))));
    }
}
