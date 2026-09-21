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
    private @org.jspecify.annotations.Nullable MockMvc http;

    @BeforeEach
    void setup() throws Exception {
        // Use Spring MVC's HTTP converters, not the controller's Jackson 2 mapper.
        http = MockMvcBuilders.standaloneSetup(new DispatchGeometryController(jdbc, roads)).build();
        when(roads.activeIdentity()).thenReturn("test-roads");
        Required.value(doAnswer(call -> {
            ResultSet row = mock(ResultSet.class);
            when(row.getString(1)).thenReturn("tech");
            when(row.getDouble(2)).thenReturn(41.25);
            when(row.getDouble(3)).thenReturn(-95.93);
            Required.value(call.<@org.jspecify.annotations.Nullable RowCallbackHandler>getArgument(1)).processRow(row);
            return null;
        }).<@org.jspecify.annotations.Nullable JdbcTemplate>when(jdbc)).query(MockArguments.startsText("SELECT id,"), MockArguments.callback(), MockArguments.equalText("metro"));
        when(jdbc.query(MockArguments.startsText("SELECT a.id"), MockArguments.<@org.jspecify.annotations.Nullable Object>rowMapper(), MockArguments.equalText("metro"), any(Timestamp.class)))
                .thenAnswer(call -> {
                    ResultSet row = mock(ResultSet.class);
                    when(row.getString(1)).thenReturn("visit");
                    when(row.getString(2)).thenReturn("tech");
                    when(row.getInt(3)).thenReturn(0);
                    when(row.getTimestamp(4)).thenReturn(Timestamp.from(Required.value(Instant.parse("2026-09-22T15:00:00Z"))));
                    when(row.getDouble(5)).thenReturn(41.27);
                    when(row.getDouble(6)).thenReturn(-95.95);
                    RowMapper<Object> mapper = call.getArgument(1);
                    return Required.value(List.of(Required.value(mapper.mapRow(row, 0))));
                });
        when(roads.routeGeometry(Required.value(anyList()), MockArguments.equalText("test-roads"))).thenReturn(json.readTree("""
                {"legs":[
                  {"seconds":120,"meters":800,"geometry":{"type":"LineString","coordinates":[[-95.93,41.25],[-95.94,41.26],[-95.95,41.27]]}},
                  {"seconds":130,"meters":900,"geometry":{"type":"LineString","coordinates":[[-95.95,41.27],[-95.96,41.26],[-95.93,41.25]]}}
                ]}
                """));
    }

    @Test
    void serializesActualGeoJsonThroughHttpIncludingHomeReturn() throws Exception {
        JsonNode body = json.readTree(Required.value(http).perform(Required.value(get("/v1/dispatch/geometry")
                        .param("metro_id", "metro").param("date", "2026-09-22")))
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
        verify(roads).routeGeometry(Required.value(List.<RoadClient.Point>of(new RoadClient.Point(41.25, -95.93),
                new RoadClient.Point(41.27, -95.95), new RoadClient.Point(41.25, -95.93))), "test-roads");
    }

    @Test
    void emptyScheduleReturnsEmptyFeaturesAndStops() throws Exception {
        when(jdbc.query(MockArguments.startsText("SELECT a.id"), MockArguments.<@org.jspecify.annotations.Nullable Object>rowMapper(), MockArguments.equalText("metro"), any(Timestamp.class)))
                .thenReturn(Required.value(List.of()));
        JsonNode body = json.readTree(Required.value(http).perform(Required.value(get("/v1/dispatch/geometry")
                        .param("metro_id", "metro").param("date", "2026-09-23")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertTrue(body.path("features").isEmpty());
        assertTrue(body.path("stops").isEmpty());
        verify(roads, never()).routeGeometry(Required.value(anyList()), Required.value(anyString()));
    }

    @Test
    void routingFailureReturnsUnavailableWithoutInventingGeometry() throws Exception {
        when(roads.routeGeometry(Required.value(anyList()), Required.value(anyString()))).thenThrow(new RoadClient.RoadUnavailable("Routing offline"));
        Required.value(http).perform(Required.value(get("/v1/dispatch/geometry").param("metro_id", "metro").param("date", "2026-09-22")))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void malformedCoordinatesReturnUnavailable() throws Exception {
        for (String coordinates : Required.value(List.of("[]", "[[1,2]]", "[[1,2],[181,3]]", "[[1,2],[3,91]]", "[[1,2],[\"3\",4]]"))) {
            when(roads.routeGeometry(Required.value(anyList()), Required.value(anyString()))).thenReturn(json.readTree(
                    "{\"legs\":[{\"geometry\":{\"type\":\"LineString\",\"coordinates\":" + coordinates + "}}]}"));
            Required.value(http).perform(Required.value(get("/v1/dispatch/geometry").param("metro_id", "metro").param("date", "2026-09-22")))
                    .andExpect(status().isServiceUnavailable());
        }
    }
}
