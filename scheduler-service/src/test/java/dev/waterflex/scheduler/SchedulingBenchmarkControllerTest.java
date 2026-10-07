package dev.waterflex.scheduler;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SchedulingBenchmarkControllerTest {
    @Test void auditRejectsOtherDatabasesAndMalformedRequestsBeforeLoadingSchedules() throws Exception {
        var jdbc = mock(JdbcTemplate.class); var loader = mock(BookingSnapshotLoader.class);
        var routing = mock(SnapshotRouting.class); var roads = mock(RoadClient.class);
        var controller = new SchedulingBenchmarkController(jdbc, loader, routing, roads, new org.springframework.mock.env.MockEnvironment(),
                mock(SearchAdmission.class),mock(dev.waterflex.scheduler.optimizer.DailySolver.class),mock(dev.waterflex.scheduler.optimizer.OptimizationService.class));
        when(jdbc.queryForObject("SELECT current_database()", String.class, new Object[0])).thenReturn("waterflex");
        assertEquals(403, assertThrows(ResponseStatusException.class, () -> controller.audit(new SchedulingBenchmarkController.Request("metro",
                Required.value(List.of("2026-10-26"))))).getStatusCode().value());
        assertEquals(403, assertThrows(ResponseStatusException.class,
                () -> controller.statistics(new SchedulingBenchmarkController.StatisticsRequest(true))).getStatusCode().value());
        assertEquals(403, assertThrows(ResponseStatusException.class,
                () -> controller.dataset(new dev.waterflex.scheduler.optimizer.OptimizationService.Request("metro","2026-10-26","export"))).getStatusCode().value());
        when(jdbc.queryForObject("SELECT current_database()", String.class, new Object[0])).thenReturn("waterflex_test");
        assertEquals(400, assertThrows(ResponseStatusException.class,
                () -> controller.dataset(new dev.waterflex.scheduler.optimizer.OptimizationService.Request("metro","2026-10-26"))).getStatusCode().value());
        assertEquals(400, assertThrows(ResponseStatusException.class,
                () -> controller.dataset(new dev.waterflex.scheduler.optimizer.OptimizationService.Request("metro","2026-10-26"," "))).getStatusCode().value());
        var http = MockMvcBuilders.standaloneSetup(controller).setMessageConverters(new JsonConfiguration().strictJsonConverter()).build();
        for (String body : List.of("null", "{}", "{\"metroId\":\"m\",\"dates\":null}", "{\"metroId\":\"m\",\"dates\":[null]}",
                "{\"metroId\":\"m\",\"dates\":[\"2026-10-26\",\"2026-10-26\"]}"))
            http.perform(Required.value(post("/internal/benchmark/audit").contentType("application/json").content(Required.value(body))))
                    .andExpect(status().isBadRequest());
        for (String body : List.of("null", "{}", "{\"resetPeak\":null}", "{\"resetPeak\":\"true\"}"))
            http.perform(Required.value(post("/internal/benchmark/statistics").contentType("application/json").content(Required.value(body))))
                    .andExpect(status().isBadRequest());
        verifyNoInteractions(loader, routing, roads);
    }
}
