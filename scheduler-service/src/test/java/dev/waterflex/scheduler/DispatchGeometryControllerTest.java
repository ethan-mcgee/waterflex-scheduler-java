package dev.waterflex.scheduler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DispatchGeometryControllerTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final RoadClient roads = mock(RoadClient.class);
    private final ObjectMapper json = new ObjectMapper();
    private MockMvc http;

    @BeforeEach
    void setup() throws Exception {
        // Use Spring MVC's HTTP converters, not the controller's Jackson 2 mapper.
        http = MockMvcBuilders.standaloneSetup(new DispatchGeometryController(jdbc, roads)).build();
        when(roads.activeIdentity()).thenReturn("test-roads");
        doAnswer(call -> {
            ResultSet row = mock(ResultSet.class);
            when(row.getString(1)).thenReturn("tech");
            when(row.getDouble(2)).thenReturn(41.25);
            when(row.getDouble(3)).thenReturn(-95.93);
            call.getArgument(1, RowCallbackHandler.class).processRow(row);
            return null;
        }).when(jdbc).query(startsWith("SELECT id,"), any(RowCallbackHandler.class), eq("metro"));
        when(jdbc.query(startsWith("SELECT a.id"), any(RowMapper.class), eq("metro"), any(Timestamp.class)))
                .thenAnswer(call -> {
                    ResultSet row = mock(ResultSet.class);
                    when(row.getString(1)).thenReturn("visit");
                    when(row.getString(2)).thenReturn("tech");
                    when(row.getInt(3)).thenReturn(0);
                    when(row.getTimestamp(4)).thenReturn(Timestamp.from(Instant.parse("2026-09-22T15:00:00Z")));
                    when(row.getDouble(5)).thenReturn(41.27);
                    when(row.getDouble(6)).thenReturn(-95.95);
                    return List.of(call.getArgument(1, RowMapper.class).mapRow(row, 0));
                });
        when(roads.routeGeometry(anyList(), eq("test-roads"))).thenReturn(json.readTree("""
                {"legs":[
                  {"seconds":120,"meters":800,"geometry":{"type":"LineString","coordinates":[[-95.93,41.25],[-95.94,41.26],[-95.95,41.27]]}},
                  {"seconds":130,"meters":900,"geometry":{"type":"LineString","coordinates":[[-95.95,41.27],[-95.96,41.26],[-95.93,41.25]]}}
                ]}
                """));
    }

    @Test
    void serializesActualGeoJsonThroughHttpIncludingHomeReturn() throws Exception {
        JsonNode body = json.readTree(http.perform(get("/v1/dispatch/geometry")
                        .param("metro_id", "metro").param("date", "2026-09-22"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertEquals("FeatureCollection", body.path("type").asText());
        assertEquals(2, body.path("features").size());
        assertEquals(json.readTree("""
                {"type":"LineString","coordinates":[[-95.93,41.25],[-95.94,41.26],[-95.95,41.27]]}
                """), body.at("/features/0/geometry"));
        assertEquals(json.readTree("[-95.93,41.25]"), body.at("/features/1/geometry/coordinates/2"));
        assertEquals("tech", body.at("/features/0/properties/technicianId").asText());
        assertEquals("tech:0", body.at("/features/0/properties/interval").asText());
        assertEquals(1, body.at("/features/1/properties/legIndex").asInt());
        assertEquals(800, body.at("/features/0/properties/meters").asInt());
        assertEquals("visit", body.at("/stops/0/id").asText());
        verify(roads).routeGeometry(List.of(new RoadClient.Point(41.25, -95.93),
                new RoadClient.Point(41.27, -95.95), new RoadClient.Point(41.25, -95.93)), "test-roads");
    }

    @Test
    void emptyScheduleReturnsEmptyFeaturesAndStops() throws Exception {
        when(jdbc.query(startsWith("SELECT a.id"), any(RowMapper.class), eq("metro"), any(Timestamp.class)))
                .thenReturn(List.of());
        JsonNode body = json.readTree(http.perform(get("/v1/dispatch/geometry")
                        .param("metro_id", "metro").param("date", "2026-09-23"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertTrue(body.path("features").isEmpty());
        assertTrue(body.path("stops").isEmpty());
        verify(roads, never()).routeGeometry(anyList(), anyString());
    }

    @Test
    void routingFailureReturnsUnavailableWithoutInventingGeometry() throws Exception {
        when(roads.routeGeometry(anyList(), anyString())).thenThrow(new RoadClient.RoadUnavailable("Routing offline"));
        http.perform(get("/v1/dispatch/geometry").param("metro_id", "metro").param("date", "2026-09-22"))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void malformedCoordinatesReturnUnavailable() throws Exception {
        for (String coordinates : List.of("[]", "[[1,2]]", "[[1,2],[181,3]]", "[[1,2],[3,91]]", "[[1,2],[\"3\",4]]")) {
            when(roads.routeGeometry(anyList(), anyString())).thenReturn(json.readTree(
                    "{\"legs\":[{\"geometry\":{\"type\":\"LineString\",\"coordinates\":" + coordinates + "}}]}"));
            http.perform(get("/v1/dispatch/geometry").param("metro_id", "metro").param("date", "2026-09-22"))
                    .andExpect(status().isServiceUnavailable());
        }
    }
}
