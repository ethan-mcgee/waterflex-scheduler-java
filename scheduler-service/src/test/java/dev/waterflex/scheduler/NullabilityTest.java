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
    @Test void malformedDispatchAndAbsenceRequestsNeverReachServices() throws Exception {
        OptimizationService optimization = mock(OptimizationService.class);
        TimeOffService timeOff = mock(TimeOffService.class);
        ScheduleGuardService guard = mock(ScheduleGuardService.class);
        var http = MockMvcBuilders.standaloneSetup(new OptimizationController(optimization), new TimeOffController(timeOff), new ScheduleGuardController(guard))
                .setMessageConverters(new JsonConfiguration().strictJsonConverter()).build();
        for (String path : List.of("/v1/optimize/day/preview", "/v1/optimize/runs/x/apply", "/v1/time-off/request", "/v1/time-off/x/approve", "/v1/dispatch/availability", "/v1/dispatch/qualification"))
            for (String body : List.of("null", "[]", "{", "false", "\"text\""))
                http.perform(Required.value(post(Required.value(path)).contentType("application/json").content(Required.value(body)))).andExpect(status().isBadRequest());
        verifyNoInteractions(optimization, timeOff, guard);
    }
    @Test void malformedRequestsNeverReachBooking() throws Exception {
        BookingService booking = mock(BookingService.class);
        var http = MockMvcBuilders.standaloneSetup(new BookingController(booking))
                .setMessageConverters(new JsonConfiguration().strictJsonConverter()).build();
        for (String path : List.of("/v1/offers", "/v1/offers/select", "/v1/holds/confirm", "/v1/appointments/cancel")) {
            for (String body : List.of("null", "[]", "{}", "{", "false", "{\"jobId\":12,\"holdId\":false}", "{\"jobId\":\"\",\"holdId\":\" \"}"))
                http.perform(Required.value(post(Required.value(path)).contentType("application/json").content(Required.value(body))))
                        .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(booking);
    }

    @Test void sqlNullIsDifferentFromLegitimateZeroAndFalse() throws Exception {
        ResultSet row = mock(ResultSet.class);
        when(row.wasNull()).thenReturn(false);
        assertEquals(0, Required.integer(row, 1));
        assertEquals(0, Required.number(row, 1));
        assertFalse(Required.bool(row, 1));
        assertEquals(new RoadClient.Point(0, 0), Required.location(row, 1, 2, HttpStatus.CONFLICT));
        when(row.wasNull()).thenReturn(true);
        assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class, () -> Required.integer(row, 1)).getStatusCode());
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, assertThrows(ResponseStatusException.class,
                () -> Required.location(row, 1, 2, HttpStatus.UNPROCESSABLE_ENTITY)).getStatusCode());
        assertThrows(ResponseStatusException.class, () -> Required.timestamp(row, 1));
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
        for (String body : List.of("null", "{}", "[null]", "[{\"appointmentId\":\"a\"}]"))
            assertThrows(ResponseStatusException.class, () -> SavedJson.assignments(Required.value(json.readTree(body))));
        for (String body : List.of("null", "[]", "{\"t\":null}", "{\"t\":\"0\"}", "{\"t\":-1}"))
            assertThrows(ResponseStatusException.class, () -> SavedJson.versions(Required.value(json.readTree(body))));
        assertTrue(SavedJson.versions(Required.value(json.readTree("{\"t\":0}"))).isObject());
        assertThrows(ResponseStatusException.class, () -> SavedJson.summary(Required.value(json.readTree("[{}]"))));
        for (String body : List.of("null", "{}", "{\"technician_id\":\"t\",\"days\":[]}", "{\"technician_id\":\"t\",\"days\":[null]}"))
            assertThrows(ResponseStatusException.class, () -> SavedJson.readyReport(Required.value(json.readTree(body))));
    }
}
