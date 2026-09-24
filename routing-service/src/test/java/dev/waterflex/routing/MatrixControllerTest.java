package dev.waterflex.routing;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MatrixControllerTest {
    @TempDir @org.jspecify.annotations.Nullable Path temp;
    private static final MatrixController.Point FIRST = new MatrixController.Point(43.7350, 7.4200);
    private static final MatrixController.Point SECOND = new MatrixController.Point(43.7350, 7.4220);
    private static final String CHECKSUM = "3ffcd8c6989bf9371df3959caaf2a721367dd8bc2f3a37d80835409ad614a4a2";

    @Test
    void missingOrIncorrectCoordinateTypesFailBeforeGraphAccess() throws Exception {
        MatrixController controller = new MatrixController("missing.osm", "missing-graph", "test", 10, 10);
        var http = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(new JsonConfiguration().strictJsonConverter()).build();
        try {
            for (String body : List.of("null", "{}", "{", "{\"origins\":[{}],\"destinations\":[{}]}",
                    "{\"origins\":[null],\"destinations\":[null]}",
                    "{\"origins\":[{\"lat\":null,\"lng\":0}],\"destinations\":[{\"lat\":0,\"lng\":0}]}",
                    "{\"origins\":[{\"lat\":\"0\",\"lng\":0}],\"destinations\":[{\"lat\":0,\"lng\":0}]}")) {
                http.perform(Required.value(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/internal/matrix")
                        .contentType("application/json").content(Required.value(body))))
                        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
            }
        } finally { controller.close(); }
    }

    @Test
    void pinnedGraphPreservesDirectionGeometryAndIdentity() throws Exception {
        Path osm = Required.value(temp).resolve("fixture-roads.osm");
        try (var source = getClass().getResourceAsStream("/fixture-roads.osm")) {
            assertNotNull(source);
            Files.copy(source, osm);
        }
        assertEquals(CHECKSUM, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(osm))));
        Files.writeString(Required.value(temp).resolve("manifest.json"), "{\"mergedSha256\":\"" + CHECKSUM + "\"}");
        Path graph = Required.value(temp).resolve("graph");
        MatrixController routing = new MatrixController(Required.value(osm.toString()), Required.value(graph.toString()), "fixture-v1", 100, 60);
        String identity = (String) routing.health().get("routingIdentity");
        assertEquals(64, identity.length());
        try {
            var matrix = routing.matrix(new MatrixController.Request(Required.value(List.<MatrixController.Point>of(FIRST, SECOND)), Required.value(List.<MatrixController.Point>of(FIRST, SECOND)), identity));
            assertEquals(identity, matrix.routingIdentity());
            assertTrue(matrix.legs().get(0).get(1).routable());
            assertTrue(matrix.legs().get(1).get(0).routable());
            assertTrue(Required.value(matrix.legs().get(1).get(0).meters()) > Required.value(matrix.legs().get(0).get(1).meters()));
            var sparse = routing.sparse(new MatrixController.SparseRequest(Required.value(List.<MatrixController.Pair>of(
                    new MatrixController.Pair("outbound", FIRST, SECOND), new MatrixController.Pair("inbound", SECOND, FIRST))), Required.value(identity)));
            assertEquals(matrix.legs().get(0).get(1), sparse.pairs().get(0).leg());
            assertEquals(matrix.legs().get(1).get(0), sparse.pairs().get(1).leg());
            assertThrows(ResponseStatusException.class, () -> routing.sparse(new MatrixController.SparseRequest(
                    Required.value(List.<MatrixController.Pair>of(new MatrixController.Pair("outbound", FIRST, SECOND))), "stale")));
            var geometry = routing.routeGeometry(new MatrixController.RouteRequest(Required.value(List.<MatrixController.Point>of(FIRST, SECOND)), identity));
            assertEquals(identity, geometry.routingIdentity());
            assertTrue(geometry.legs().getFirst().geometry().coordinates().size() >= 2);
            assertEquals(matrix.legs().get(0).get(1).meters(), geometry.legs().getFirst().meters());
            var coincident = routing.routeGeometry(new MatrixController.RouteRequest(Required.value(List.<MatrixController.Point>of(FIRST, FIRST)), identity)).legs().getFirst();
            assertEquals(0, coincident.meters());
            assertEquals(0, coincident.seconds());
            assertEquals(2, coincident.geometry().coordinates().size());
            assertEquals(coincident.geometry().coordinates().getFirst(), coincident.geometry().coordinates().getLast());
            assertFalse(routing.matrix(new MatrixController.Request(Required.value(List.<MatrixController.Point>of(FIRST)),
                    Required.value(List.<MatrixController.Point>of(new MatrixController.Point(0, 0))), identity)).legs().getFirst().getFirst().routable());
            assertEquals(400, assertThrows(ResponseStatusException.class, () -> routing.matrix(
                    new MatrixController.Request(Required.value(List.<MatrixController.Point>of(new MatrixController.Point(100, 0))), Required.value(List.<MatrixController.Point>of(FIRST)), identity)))
                    .getStatusCode().value());
            assertEquals(409, assertThrows(ResponseStatusException.class, () -> routing.matrix(
                    new MatrixController.Request(Required.value(List.<MatrixController.Point>of(FIRST)), Required.value(List.<MatrixController.Point>of(SECOND)), "old-graph")))
                    .getStatusCode().value());
        } finally { routing.close(); }
        MatrixController prepared = new MatrixController(Required.value(osm.toString()), Required.value(graph.toString()), "fixture-v1", 100, 60, true);
        try {
            assertNotEquals(identity, prepared.health().get("routingIdentity"));
            assertEquals("CH-car-v1", prepared.health().get("preparedConfiguration"));
            assertTrue(Files.isDirectory(Required.value(temp).resolve("graph-ch-car-v1")));
            var accelerated = prepared.matrix(new MatrixController.Request(Required.value(List.<MatrixController.Point>of(FIRST, SECOND)), Required.value(List.<MatrixController.Point>of(FIRST, SECOND)), null));
            MatrixController flexible = new MatrixController(Required.value(osm.toString()), Required.value(graph.toString()), "fixture-v1", 100, 60);
            try {
                assertEquals(accelerated.legs(), flexible.matrix(new MatrixController.Request(Required.value(List.<MatrixController.Point>of(FIRST, SECOND)), Required.value(List.<MatrixController.Point>of(FIRST, SECOND)), identity)).legs());
                assertEquals(prepared.routeGeometry(new MatrixController.RouteRequest(Required.value(List.<MatrixController.Point>of(SECOND, FIRST)), null)).legs(),
                        flexible.routeGeometry(new MatrixController.RouteRequest(Required.value(List.<MatrixController.Point>of(SECOND, FIRST)), identity)).legs());
            } finally { flexible.close(); }
        } finally { prepared.close(); }
        Files.writeString(Required.value(temp).resolve("manifest.json"), "{\"mergedSha256\":\"replacement-map\"}");
        MatrixController replacement = new MatrixController(Required.value(osm.toString()), Required.value(graph.toString()), "fixture-v1", 100, 60);
        try {
            assertNotEquals(identity, replacement.health().get("routingIdentity"));
            assertEquals(409, assertThrows(ResponseStatusException.class, () -> replacement.matrix(
                    new MatrixController.Request(Required.value(List.<MatrixController.Point>of(FIRST)), Required.value(List.<MatrixController.Point>of(SECOND)), identity)))
                    .getStatusCode().value());
        } finally { replacement.close(); }
    }
}
