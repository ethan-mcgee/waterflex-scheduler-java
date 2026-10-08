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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
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
        clients(1);
        Required.value(doAnswer(call -> {
            ResultSet row = mock(ResultSet.class);
            when(row.getString(1)).thenReturn("tech");
            when(row.getDouble(2)).thenReturn(41.25);
            when(row.getDouble(3)).thenReturn(-95.93);
            when(row.getString(4)).thenReturn("HOME");
            when(row.getString(5)).thenReturn("HOME");
            Required.value(call.<@org.jspecify.annotations.Nullable RowCallbackHandler>getArgument(1)).processRow(row);
            return null;
        }).<@org.jspecify.annotations.Nullable JdbcTemplate>when(jdbc)).query(MockArguments.startsText("SELECT t.id,"), MockArguments.callback(), any(Timestamp.class), any(Timestamp.class), MockArguments.equalText("metro"));
        when(jdbc.query(MockArguments.startsText("SELECT a.id"), MockArguments.<@org.jspecify.annotations.Nullable Object>rowMapper(), MockArguments.equalText("metro"), any(Timestamp.class)))
                .thenAnswer(call -> {
                    ResultSet row = mock(ResultSet.class);
                    when(row.getString(1)).thenReturn("visit");
                    when(row.getString(2)).thenReturn("tech");
                    when(row.getInt(3)).thenReturn(0);
                    when(row.getTimestamp(4)).thenReturn(Timestamp.from(Required.value(Instant.parse("2026-09-22T15:00:00Z"))));
                    when(row.getDouble(5)).thenReturn(41.27);
                    when(row.getDouble(6)).thenReturn(-95.95);
                    when(row.getTimestamp(7)).thenReturn(Timestamp.from(Required.value(Instant.parse("2026-09-22T16:00:00Z"))));
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
        verify(roads).routeGeometry(Required.value(List.<RoadPoint>of(new RoadPoint(41.25, -95.93),
                new RoadPoint(41.27, -95.95), new RoadPoint(41.25, -95.93))), "test-roads");
    }

    @Test void currentGeometryUsesVersionedSegmentsAndRejectsMalformedCoverage() throws Exception {
        String saved = "{\"format\":1,\"scheduleVersion\":2,\"routingIdentity\":\"test-roads\",\"segments\":[{\"departure\":\"2026-09-22T14:50:00Z\",\"returnedAt\":\"2026-09-22T16:10:00Z\",\"appointmentIds\":[\"visit\"]}]}";
        for (String raw : List.of(saved, saved.replace("visit", "missing"), "null", saved.replace("test-roads", "old-roads"))) {
            stubCurrentTiming(Required.value(raw), 2);
            var response = Required.value(http).perform(Required.value(get("/v1/dispatch/geometry").param("metro_id", "metro").param("date", "2026-09-22")));
            if (raw.equals(saved)) response.andExpect(status().isOk()).andExpect(jsonPath("$.segments.tech[0].departure").value("2026-09-22T14:50:00Z"));
            else response.andExpect(status().isConflict());
        }
        stubCurrentTiming(saved, 3);
        Required.value(http).perform(Required.value(get("/v1/dispatch/geometry").param("metro_id", "metro").param("date", "2026-09-22")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.segments.tech").doesNotExist());
    }

    private void stubCurrentTiming(String raw, int version) {
        Required.value(doAnswer(call -> {
            ResultSet row = mock(ResultSet.class);
            when(row.getString(1)).thenReturn("tech"); when(row.getInt(2)).thenReturn(version); when(row.getString(3)).thenReturn(raw);
            Required.value(call.<@org.jspecify.annotations.Nullable RowCallbackHandler>getArgument(1)).processRow(row);
            return null;
        }).<@org.jspecify.annotations.Nullable JdbcTemplate>when(jdbc)).query(MockArguments.startsText("SELECT \"technicianId\",version"), MockArguments.callback(), any(Timestamp.class));
    }

    @Test
    void aMetroSharedByTwoClientsIsNotDrawnWhole() throws Exception {
        clients(2);
        Required.value(http).perform(Required.value(get("/v1/dispatch/geometry").param("metro_id", "metro").param("date", "2026-09-23")))
                .andExpect(status().isConflict());
        Required.value(http).perform(Required.value(get("/v1/dispatch/geometry").param("metro_id", "metro").param("date", "2026-09-23")
                        .param("phase", "before").param("run_id", "run").param("client_id", "acme")))
                .andExpect(status().isConflict());
        verify(roads, never()).routeGeometry(Required.value(anyList()), Required.value(anyString()));
    }

    /** How many clients' depots the test metro has. */
    private void clients(int count) {
        when(jdbc.queryForObject(MockArguments.startsText("SELECT count(DISTINCT d."), eq(Integer.class), MockArguments.equalText("metro"))).thenReturn(count);
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
    void savedBeforeGeometryRendersWithoutLiveAppointments() throws Exception {
        when(jdbc.query(MockArguments.startsText("SELECT a.id"), MockArguments.<@org.jspecify.annotations.Nullable Object>rowMapper(), MockArguments.equalText("metro"), any(Timestamp.class)))
                .thenReturn(Required.value(List.of()));
        stubSavedGeometry(savedAssignment("saved-before", "tech", 41.28, -95.97), savedAssignment("saved-after", "tech", 41.29, -95.98), "test-roads");

        JsonNode body = savedGeometry("before");

        assertEquals("saved-before", body.at("/stops/0/id").asText());
        assertEquals(41.28, body.at("/stops/0/lat").asDouble());
        verify(jdbc, never()).query(MockArguments.startsText("SELECT a.id"), MockArguments.<@org.jspecify.annotations.Nullable Object>rowMapper(), MockArguments.equalText("metro"), any(Timestamp.class));
        verify(roads).routeGeometry(Required.value(List.<RoadPoint>of(new RoadPoint(41.25, -95.93),
                new RoadPoint(41.28, -95.97), new RoadPoint(41.25, -95.93))), "test-roads");
    }

    @Test
    void savedAfterGeometryDoesNotDependOnChangedLiveAppointments() throws Exception {
        stubSavedGeometry(savedAssignment("saved-before", "tech", 41.28, -95.97), savedAssignment("saved-after", "tech", 41.29, -95.98), "test-roads");

        JsonNode body = savedGeometry("after");

        assertEquals("saved-after", body.at("/stops/0/id").asText());
        assertEquals(-95.98, body.at("/stops/0/lng").asDouble());
        verify(jdbc, never()).query(MockArguments.startsText("SELECT a.id"), MockArguments.<@org.jspecify.annotations.Nullable Object>rowMapper(), MockArguments.equalText("metro"), any(Timestamp.class));
    }

    @Test
    void savedGeometryRejectsMalformedAssignmentsMissingTechniciansAndChangedRoutingIdentity() throws Exception {
        stubSavedGeometry("[{\"appointmentId\":\"visit\"}]", savedAssignment("visit", "tech", 41.27, -95.95), "test-roads");
        Required.value(http).perform(Required.value(savedGeometryRequest("before"))).andExpect(status().isConflict());

        stubSavedGeometry(savedAssignment("visit", "missing-tech", 41.27, -95.95), savedAssignment("visit", "tech", 41.27, -95.95), "test-roads");
        Required.value(http).perform(Required.value(savedGeometryRequest("before"))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Saved route technician is unavailable"));

        stubSavedGeometry(savedAssignment("visit", "tech", 41.27, -95.95), savedAssignment("visit", "tech", 41.27, -95.95), "old-roads");
        Required.value(http).perform(Required.value(savedGeometryRequest("before"))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Routing graph changed; generate a new preview"));
    }

    @Test
    void olderPreviewWithoutEndpointSnapshotsReportsUnavailableGeometry() throws Exception {
        stubSavedGeometry(savedAssignment("visit", "tech", 41.27, -95.95), savedAssignment("visit", "tech", 41.27, -95.95), "test-roads");
        when(jdbc.query(MockArguments.startsText("SELECT \"baselineAssignments\""), MockArguments.<@org.jspecify.annotations.Nullable Object>rowMapper(),
                MockArguments.equalText("saved-run"), MockArguments.equalText("metro"), any(Timestamp.class))).thenAnswer(call -> {
                    ResultSet row = mock(ResultSet.class);
                    when(row.getString(1)).thenReturn(savedAssignment("visit", "tech", 41.27, -95.95));
                    when(row.getString(2)).thenReturn(savedAssignment("visit", "tech", 41.27, -95.95));
                    when(row.getString(3)).thenReturn("{\"mapVersion\":\"test-roads\"}");
                    when(row.getString(5)).thenReturn("[]"); when(row.getString(6)).thenReturn("[]");
                    RowMapper<Object> mapper = call.getArgument(1);
                    return Required.value(List.of(Required.value(mapper.mapRow(row, 0))));
                });
        Required.value(http).perform(Required.value(savedGeometryRequest("before"))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Historical endpoint geometry is unavailable for this preview"));
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

    private JsonNode savedGeometry(String phase) throws Exception {
        return Required.value(json.readTree(Required.value(http).perform(Required.value(savedGeometryRequest(phase)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder savedGeometryRequest(String phase) {
        return Required.value(get("/v1/dispatch/geometry").param("metro_id", "metro").param("date", "2026-09-22")
                .param("run_id", "saved-run").param("phase", phase));
    }

    private static String savedAssignment(String appointmentId, String technicianId, double lat, double lng) {
        return "[{\"appointmentId\":\"" + appointmentId + "\",\"technicianId\":\"" + technicianId
                + "\",\"sequence\":0,\"plannedStart\":\"2026-09-22T15:00:00Z\",\"windowStart\":\"2026-09-22T14:00:00Z\","
                + "\"windowEnd\":\"2026-09-22T18:00:00Z\",\"locationLat\":" + lat + ",\"locationLng\":" + lng + "}]";
    }

    private void stubSavedGeometry(String before, String after, String mapVersion) {
        when(jdbc.query(MockArguments.startsText("SELECT \"baselineAssignments\""), MockArguments.<@org.jspecify.annotations.Nullable Object>rowMapper(),
                MockArguments.equalText("saved-run"), MockArguments.equalText("metro"), any(Timestamp.class))).thenAnswer(call -> {
                    ResultSet row = mock(ResultSet.class);
                    when(row.getString(1)).thenReturn(before);
                    when(row.getString(2)).thenReturn(after);
                    when(row.getString(3)).thenReturn("{\"mapVersion\":\"" + mapVersion + "\",\"configVersion\":\"test-config\"}");
                    when(row.getString(4)).thenReturn("{\"tech\":{\"departure\":{\"lat\":41.25,\"lng\":-95.93},\"returnTo\":{\"lat\":41.25,\"lng\":-95.93}}}");
                    when(row.getString(5)).thenReturn("[]"); when(row.getString(6)).thenReturn("[]");
                    RowMapper<Object> mapper = call.getArgument(1);
                    return Required.value(List.of(Required.value(mapper.mapRow(row, 0))));
                });
    }
}
